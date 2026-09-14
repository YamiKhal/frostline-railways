package com.yamikhal.frostlinerailways.rail.decor;

import com.yamikhal.frostlinerailways.RailwaysConfig;
import com.yamikhal.frostlinerailways.rail.layout.RailLayout;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Where tiled structures go along the line (RAILWAYS.md §A8.8): covers over deep cuts and bridge
 * structures, built from a style's templates. Computed once per layout, style data, station data and
 * config toggles, from the layout, seed and noise only, so every chunk agrees.
 *
 * A stretch is a run of rows on one straight piece, in one style, of one kind:
 *
 *   cover   CUT rows whose ground is at least the style's cut_cover.min_depth above the track
 *   bridge  BRIDGE rows
 *
 * never within 8 blocks of a station. A full stretch gets the start template at its south end, as many
 * middle templates as fit, and the end template at its north end, centred (left-over rows keep the plain
 * bed). A bridge stretch only gets the full set if it is between min_length and max_length long and at
 * least min_gap blocks past the previous full bridge; otherwise it is tiled with the flat template, if
 * the style has one.
 *
 * Every template is placed with its x = 0 edge outward: the start's at the stretch's south end, the end's
 * at its north end, the middles' and flats' at their south edge.
 */
public final class StructurePlanner {

    public enum Kind { COVER, BRIDGE }

    public record Tile(Kind kind, ResourceLocation template, int zMin, int zMax, int zOrigin, int stepZ, int trackX, int bedY,
                       int trackZ, int trackY, Optional<BlockState> foundation, int foundationDepth) {
    }

    /** The tiles of a line, by z, never overlapping. */
    public static final class Plan {
        private final List<Tile> tiles;
        private final int[] bridgeMin;
        private final int[] bridgeMax;

        Plan(List<Tile> tiles) {
            this.tiles = List.copyOf(tiles);
            List<Tile> bridges = tiles.stream().filter(t -> t.kind() == Kind.BRIDGE).toList();
            bridgeMin = bridges.stream().mapToInt(Tile::zMin).toArray();
            bridgeMax = bridges.stream().mapToInt(Tile::zMax).toArray();
        }

        public List<Tile> tiles() {
            return tiles;
        }

        /** Tiles with any row in [minZ, maxZ]. */
        public List<Tile> touching(int minZ, int maxZ) {
            int lo = 0;
            int hi = tiles.size();
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (tiles.get(mid).zMax() < minZ) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            List<Tile> out = new ArrayList<>();
            for (int i = lo; i < tiles.size() && tiles.get(i).zMin() <= maxZ; i++) {
                out.add(tiles.get(i));
            }
            return out;
        }

        /** True if row z is under a bridge structure (the plain bridge's piers and railing are left out there). */
        public boolean bridgeAt(int z) {
            int lo = 0;
            int hi = bridgeMax.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (bridgeMax[mid] < z) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return lo < bridgeMin.length && bridgeMin[lo] <= z;
        }
    }

    private static final Plan EMPTY = new Plan(List.of());
    private static final int STATION_MARGIN = 8;

    private static volatile Object ownerLayout;
    private static volatile Object ownerStyles;
    private static volatile Object ownerStations;
    private static volatile String ownerConfig = "";
    private static volatile Plan plan = EMPTY;

    private StructurePlanner() {
    }

    public static Plan plan(RailContext ctx, MinecraftServer server) {
        Object styles = RailDecorData.STYLES.entries();
        Object stations = RailDecorData.STATIONS.entries();
        int flags = (RailwaysConfig.cutCovers() ? 1 : 0) | (RailwaysConfig.bridgeStructures() ? 2 : 0);
        String config = flags + "|" + RailwaysConfig.bridgeFullMinLength() + "|" + RailwaysConfig.bridgeFullMinMiddles();
        if (ownerLayout == ctx.layout && ownerStyles == styles && ownerStations == stations && config.equals(ownerConfig)) {
            return plan;
        }
        synchronized (StructurePlanner.class) {
            if (ownerLayout != ctx.layout || ownerStyles != styles || ownerStations != stations || !config.equals(ownerConfig)) {
                plan = flags == 0 || server == null ? EMPTY : compute(ctx, server, (flags & 1) != 0, (flags & 2) != 0);
                ownerStyles = styles;
                ownerStations = stations;
                ownerConfig = config;
                ownerLayout = ctx.layout;
            }
            return plan;
        }
    }

    private static Plan compute(RailContext ctx, MinecraftServer server, boolean covers, boolean bridges) {
        RailLayout layout = ctx.layout;
        List<StationPlanner.Site> sites = StationPlanner.sites(ctx);
        List<Tile> tiles = new ArrayList<>();
        int lastFullNorth = Integer.MAX_VALUE;
        int z = layout.zSouthEnd();
        while (z >= layout.zNorthEnd()) {
            Kind kind = kindAt(ctx, z, covers, bridges, sites);
            if (kind == null) {
                z--;
                continue;
            }
            RailContext.Row first = ctx.row(z);
            int north = z;
            while (north - 1 >= layout.zNorthEnd()) {
                RailContext.Row next = ctx.row(north - 1);
                if (next.piece() != first.piece() || !next.style().id().equals(first.style().id())
                        || kindAt(ctx, north - 1, covers, bridges, sites) != kind) {
                    break;
                }
                north--;
            }
            RailStyle style = first.style().value();
            if (kind == Kind.COVER) {
                placeFull(tiles, kind, style.cutCover().get(), server, z, north, first);
            } else {
                RailStyle.Tiles spec = style.bridgeTiles().get();
                boolean gapOk = lastFullNorth == Integer.MAX_VALUE || lastFullNorth - z - 1 >= spec.minGap();
                if (gapOk && placeFull(tiles, kind, spec, server, z, north, first)) {
                    lastFullNorth = tiles.get(tiles.size() - 1).zMin();
                } else {
                    placeFlat(tiles, spec, server, z, north, first);
                }
            }
            z = north - 1;
        }
        tiles.sort(Comparator.comparingInt(Tile::zMin));
        return new Plan(tiles);
    }

    private static Kind kindAt(RailContext ctx, int z, boolean covers, boolean bridges, List<StationPlanner.Site> sites) {
        RailContext.Row row = ctx.row(z);
        if (ctx.layout.type(row.piece()) != RailLayout.STRAIGHT) {
            return null;
        }
        for (StationPlanner.Site site : sites) {
            if (z >= site.zNorth() - STATION_MARGIN && z <= site.zSouth() + STATION_MARGIN) {
                return null;
            }
        }
        RailStyle style = row.style().value();
        if (bridges && row.kind() == RailContext.Kind.BRIDGE && style.bridgeTiles().isPresent()) {
            return Kind.BRIDGE;
        }
        if (covers && row.kind() == RailContext.Kind.CUT && style.cutCover().isPresent()
                && ctx.layout.groundAt(z) - row.bedY() >= style.cutCover().get().minDepth()) {
            return Kind.COVER;
        }
        return null;
    }

    private static RailTemplates.Structure load(MinecraftServer server, Optional<ResourceLocation> id) {
        return id.map(r -> RailTemplates.structure(server, r)).orElse(null);
    }

    /** Start, middles, end over [north, south]; false (nothing added) if a template is missing or the stretch does not fit. */
    private static boolean placeFull(List<Tile> out, Kind kind, RailStyle.Tiles spec, MinecraftServer server, int south, int north,
                                     RailContext.Row row) {
        RailTemplates.Structure start = load(server, spec.start());
        RailTemplates.Structure middle = load(server, spec.middle());
        RailTemplates.Structure end = load(server, spec.end());
        if (start == null || middle == null || end == null) {
            return false;
        }
        int length = south - north + 1;
        int ls = start.sizeX();
        int lm = middle.sizeX();
        int le = end.sizeX();
        int minLength = Math.max(spec.minLength(), ls + le);
        if (kind == Kind.BRIDGE) {
            // config minimums: short bridges get flat tiles instead of a squeezed start + end
            minLength = Math.max(minLength, Math.max(RailwaysConfig.bridgeFullMinLength(),
                    ls + le + RailwaysConfig.bridgeFullMinMiddles() * lm));
        }
        if (length < minLength || (kind == Kind.BRIDGE && length > spec.maxLength())) {
            return false;
        }
        int k = (length - ls - le) / lm;
        int top = south - (length - ls - le - k * lm) / 2;
        out.add(tile(kind, spec.start().get(), top - ls + 1, top, top, -1, row, spec));
        top -= ls;
        for (int j = 0; j < k; j++) {
            out.add(tile(kind, spec.middle().get(), top - lm + 1, top, top, -1, row, spec));
            top -= lm;
        }
        int endNorth = top - le + 1;
        out.add(tile(kind, spec.end().get(), endNorth, top, endNorth, 1, row, spec));
        return true;
    }

    private static void placeFlat(List<Tile> out, RailStyle.Tiles spec, MinecraftServer server, int south, int north, RailContext.Row row) {
        RailTemplates.Structure flat = load(server, spec.flat());
        if (flat == null) {
            return;
        }
        int length = south - north + 1;
        int lf = flat.sizeX();
        int k = length / lf;
        int top = south - (length - k * lf) / 2;
        for (int j = 0; j < k; j++) {
            out.add(tile(Kind.BRIDGE, spec.flat().get(), top - lf + 1, top, top, -1, row, spec));
            top -= lf;
        }
    }

    private static Tile tile(Kind kind, ResourceLocation id, int zMin, int zMax, int zOrigin, int stepZ, RailContext.Row row,
                             RailStyle.Tiles spec) {
        return new Tile(kind, id, zMin, zMax, zOrigin, stepZ, row.trackX(), row.bedY(), spec.trackZ(), spec.trackY(),
                spec.foundation(), spec.foundationDepth());
    }
}
