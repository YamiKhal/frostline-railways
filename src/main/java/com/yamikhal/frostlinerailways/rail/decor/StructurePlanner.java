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
 * Where tiled structures go along the line (RAILWAYS.md §A8.8, §A8.10): tunnel portals and interiors,
 * covers over deep cuts, bridge structures, built from a style's templates. Computed once per layout, style
 * data, station data and config, from the layout, seed and noise only, so every chunk agrees.
 *
 * Rows are classified, south to north:
 *
 *   tunnel       TUNNEL rows
 *   cover        CUT rows whose ground is at least the style's cut_cover.min_depth above the track
 *   full bridge  BRIDGE rows whose track is at least bridge_structures.min_height above the ground
 *   flat bridge  lower BRIDGE rows
 *   blocked      within 8 blocks of a station, or off the line
 *
 * A stretch is a run of rows of one class on any track piece (straights, S-bends, ramps, diagonal
 * shifts: each tile follows the track at its middle row), in the style of its first row; up to merge_gap
 * rows of no class (and, for full bridges, flat-bridge rows) inside it do not end it. Its variant is picked
 * once, its numbered template files per tile.
 *
 *   tunnel, cover  start at the south end, middles until the stretch is covered, end at the north end; the
 *                  set is centred and may overhang the stretch onto free rows, middles are dropped if it
 *                  cannot. Without a middle template: start and end at the stretch's ends only.
 *   full bridge    start, as many whole middles as fit (at least bridgeFullMinMiddles), end, centred inside
 *                  the stretch; only if min_length / bridgeFullMinLength ≤ length ≤ max_length and at least
 *                  min_gap blocks past the previous full bridge; otherwise flat
 *   flat bridge    flat tiles covering the stretch, overhanging onto free rows if needed
 *
 * Every template is placed with its x = 0 edge outward: the start's at the south, the end's at the north,
 * the middles' and flats' at their south edge. Tiles of one stretch never overlap another's.
 */
public final class StructurePlanner {

    public enum Kind { COVER, BRIDGE, TUNNEL }

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

    private enum RowKind { NONE, BLOCKED, COVER, BRIDGE_FULL, BRIDGE_FLAT, TUNNEL }

    private static final Plan EMPTY = new Plan(List.of());
    private static final int STATION_MARGIN = 8;
    private static final long VARIANT_SALT = 0x7A11_7E57L;
    private static final long TEMPLATE_SALT = 0x7E3A_1A7EL;

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
        boolean covers = RailwaysConfig.cutCovers();
        boolean bridges = RailwaysConfig.bridgeStructures();
        boolean tunnels = RailwaysConfig.tunnelStructures();
        String config = covers + "|" + bridges + "|" + tunnels + "|" + RailwaysConfig.bridgeFullMinLength() + "|"
                + RailwaysConfig.bridgeFullMinMiddles();
        if (ownerLayout == ctx.layout && ownerStyles == styles && ownerStations == stations && config.equals(ownerConfig)) {
            return plan;
        }
        synchronized (StructurePlanner.class) {
            if (ownerLayout != ctx.layout || ownerStyles != styles || ownerStations != stations || !config.equals(ownerConfig)) {
                plan = (!covers && !bridges && !tunnels) || server == null ? EMPTY
                        : new Builder(ctx, server, covers, bridges, tunnels).build();
                ownerStyles = styles;
                ownerStations = stations;
                ownerConfig = config;
                ownerLayout = ctx.layout;
            }
            return plan;
        }
    }

    private static final class Builder {
        private final RailContext ctx;
        private final MinecraftServer server;
        private final boolean covers;
        private final boolean bridges;
        private final boolean tunnels;
        private final List<StationPlanner.Site> sites;
        private final List<Tile> tiles = new ArrayList<>();
        /** Tiles of later stretches stay at or north of this z (north = smaller z). */
        private int ceiling = Integer.MAX_VALUE;
        private int lastFullNorth = Integer.MAX_VALUE;

        Builder(RailContext ctx, MinecraftServer server, boolean covers, boolean bridges, boolean tunnels) {
            this.ctx = ctx;
            this.server = server;
            this.covers = covers;
            this.bridges = bridges;
            this.tunnels = tunnels;
            this.sites = StationPlanner.sites(ctx);
        }

        Plan build() {
            RailLayout layout = ctx.layout;
            int z = layout.zSouthEnd();
            while (z >= layout.zNorthEnd()) {
                RowKind kind = rowKind(z);
                RailStyle.Tiles spec = spec(z, kind);
                if (spec == null) {
                    z--;
                    continue;
                }
                int north = z;
                int misses = 0;
                for (int probe = z - 1; probe >= layout.zNorthEnd(); probe--) {
                    RowKind k = rowKind(probe);
                    if (k == kind) {
                        north = probe;
                        misses = 0;
                        continue;
                    }
                    boolean absorbable = k == RowKind.NONE || (kind == RowKind.BRIDGE_FULL && k == RowKind.BRIDGE_FLAT);
                    if (!absorbable || ++misses > spec.mergeGap()) {
                        break;
                    }
                }
                int south = Math.min(z, ceiling);
                if (south >= north) {
                    RailStyle.Variant variant = spec.pick(ctx.random(VARIANT_SALT, z, kind.ordinal()));
                    switch (kind) {
                        case TUNNEL -> sequence(Kind.TUNNEL, spec, variant, south, north);
                        case COVER -> sequence(Kind.COVER, spec, variant, south, north);
                        case BRIDGE_FULL -> {
                            boolean gapOk = lastFullNorth == Integer.MAX_VALUE || lastFullNorth - south - 1 >= spec.minGap();
                            if (!(gapOk && fullBridge(spec, variant, south, north))) {
                                flat(spec, variant, south, north);
                            }
                        }
                        case BRIDGE_FLAT -> flat(spec, variant, south, north);
                        default -> { }
                    }
                }
                z = north - 1;
            }
            tiles.sort(Comparator.comparingInt(Tile::zMin));
            return new Plan(tiles);
        }

        private RowKind rowKind(int z) {
            RailLayout layout = ctx.layout;
            if (z > layout.zSouthEnd() || z < layout.zNorthEnd()) {
                return RowKind.BLOCKED;
            }
            for (StationPlanner.Site site : sites) {
                if (z >= site.zNorth() - STATION_MARGIN && z <= site.zSouth() + STATION_MARGIN) {
                    return RowKind.BLOCKED;
                }
            }
            RailContext.Row row = ctx.row(z);
            RailStyle style = row.style().value();
            return switch (row.kind()) {
                case TUNNEL -> tunnels && style.tunnelTiles().isPresent() ? RowKind.TUNNEL : RowKind.NONE;
                case BRIDGE -> !bridges || style.bridgeTiles().isEmpty() ? RowKind.NONE
                        : row.bedY() - 1 - row.groundTop() >= style.bridgeTiles().get().minHeight() ? RowKind.BRIDGE_FULL : RowKind.BRIDGE_FLAT;
                case CUT -> covers && style.cutCover().isPresent() && layout.groundAt(z) - row.bedY() >= style.cutCover().get().minDepth()
                        ? RowKind.COVER : RowKind.NONE;
                default -> RowKind.NONE;
            };
        }

        private RailStyle.Tiles spec(int z, RowKind kind) {
            if (kind == RowKind.NONE || kind == RowKind.BLOCKED) {
                return null;
            }
            RailStyle style = ctx.row(z).style().value();
            return switch (kind) {
                case TUNNEL -> style.tunnelTiles().orElse(null);
                case COVER -> style.cutCover().orElse(null);
                default -> style.bridgeTiles().orElse(null);
            };
        }

        private ResourceLocation pick(Optional<ResourceLocation> base, int z, int slot) {
            return base.map(id -> RailTemplates.pick(server, id, ctx.random(TEMPLATE_SALT, z, slot))).orElse(null);
        }

        private RailTemplates.Structure load(ResourceLocation id) {
            return id == null ? null : RailTemplates.structure(server, id);
        }

        /** Consecutive rows from {@code from} in direction {@code dir} a tile may overhang onto, at most {@code max}. */
        private int freeRun(int from, int dir, int max) {
            int n = 0;
            for (int z = from; n < max && z <= ceiling && rowKind(z) != RowKind.BLOCKED; z += dir) {
                n++;
            }
            return n;
        }

        private void sequence(Kind kind, RailStyle.Tiles spec, RailStyle.Variant v, int south, int north) {
            int length = south - north + 1;
            if (length < spec.minLength()) {
                return;
            }
            ResourceLocation startId = pick(v.start(), south, 1);
            ResourceLocation endId = pick(v.end(), north, 2);
            RailTemplates.Structure start = load(startId);
            RailTemplates.Structure end = load(endId);
            if (start == null || end == null) {
                return;
            }
            if (v.middle().isEmpty() && start.sizeX() + end.sizeX() <= length) {
                add(kind, spec, startId, south - start.sizeX() + 1, south, south, -1);
                add(kind, spec, endId, north, north + end.sizeX() - 1, north, 1);
                ceiling = Math.min(ceiling, north - 1);
                return;
            }
            List<ResourceLocation> ids = new ArrayList<>(List.of(startId));
            List<RailTemplates.Structure> parts = new ArrayList<>(List.of(start));
            int total = start.sizeX() + end.sizeX();
            for (int i = 0; v.middle().isPresent() && total < length; i++) {
                ResourceLocation id = pick(v.middle(), south, 10 + i);
                RailTemplates.Structure middle = load(id);
                if (middle == null) {
                    break;
                }
                ids.add(id);
                parts.add(middle);
                total += middle.sizeX();
            }
            ids.add(endId);
            parts.add(end);
            while (!lay(kind, spec, ids, parts, true, south, north, true)) {
                if (parts.size() <= 2) {
                    return;
                }
                ids.remove(ids.size() - 2);
                parts.remove(parts.size() - 2);
            }
        }

        private boolean fullBridge(RailStyle.Tiles spec, RailStyle.Variant v, int south, int north) {
            int length = south - north + 1;
            if (length < Math.max(spec.minLength(), RailwaysConfig.bridgeFullMinLength()) || length > spec.maxLength() || v.middle().isEmpty()) {
                return false;
            }
            ResourceLocation startId = pick(v.start(), south, 1);
            ResourceLocation endId = pick(v.end(), north, 2);
            RailTemplates.Structure start = load(startId);
            RailTemplates.Structure end = load(endId);
            if (start == null || end == null) {
                return false;
            }
            List<ResourceLocation> ids = new ArrayList<>(List.of(startId));
            List<RailTemplates.Structure> parts = new ArrayList<>(List.of(start));
            int total = start.sizeX() + end.sizeX();
            int middles = 0;
            for (int i = 0; ; i++) {
                ResourceLocation id = pick(v.middle(), south, 10 + i);
                RailTemplates.Structure middle = load(id);
                if (middle == null || total + middle.sizeX() > length) {
                    break;
                }
                ids.add(id);
                parts.add(middle);
                total += middle.sizeX();
                middles++;
            }
            if (middles < RailwaysConfig.bridgeFullMinMiddles()) {
                return false;
            }
            ids.add(endId);
            parts.add(end);
            if (!lay(Kind.BRIDGE, spec, ids, parts, true, south, north, false)) {
                return false;
            }
            lastFullNorth = tiles.get(tiles.size() - 1).zMin();
            return true;
        }

        private void flat(RailStyle.Tiles spec, RailStyle.Variant v, int south, int north) {
            if (v.flat().isEmpty()) {
                return;
            }
            int length = south - north + 1;
            List<ResourceLocation> ids = new ArrayList<>();
            List<RailTemplates.Structure> parts = new ArrayList<>();
            int total = 0;
            for (int i = 0; total < length; i++) {
                ResourceLocation id = pick(v.flat(), south, 40 + i);
                RailTemplates.Structure flat = load(id);
                if (flat == null) {
                    return;
                }
                ids.add(id);
                parts.add(flat);
                total += flat.sizeX();
            }
            while (!lay(Kind.BRIDGE, spec, ids, parts, false, south, north, true)) {
                if (parts.size() <= 1) {
                    return;
                }
                ids.remove(ids.size() - 1);
                parts.remove(parts.size() - 1);
            }
        }

        /**
         * Lays the parts south to north over [north, south]: centred inside the stretch if they fit, else
         * (with extend) overhanging onto free rows either side; false if they cannot be placed.
         */
        private boolean lay(Kind kind, RailStyle.Tiles spec, List<ResourceLocation> ids, List<RailTemplates.Structure> parts,
                            boolean endLast, int south, int north, boolean extend) {
            int length = south - north + 1;
            int total = parts.stream().mapToInt(RailTemplates.Structure::sizeX).sum();
            int southEdge;
            if (total > length) {
                if (!extend) {
                    return false;
                }
                int extra = total - length;
                int southFree = freeRun(south + 1, 1, extra);
                int northFree = freeRun(north - 1, -1, extra);
                if (southFree + northFree < extra) {
                    return false;
                }
                southEdge = south + Math.min(southFree, Math.max(extra - northFree, extra / 2));
            } else {
                southEdge = south - (length - total) / 2;
            }
            int top = southEdge;
            for (int i = 0; i < parts.size(); i++) {
                int size = parts.get(i).sizeX();
                boolean end = endLast && i == parts.size() - 1;
                int zMin = top - size + 1;
                add(kind, spec, ids.get(i), zMin, top, end ? zMin : top, end ? 1 : -1);
                top -= size;
            }
            ceiling = Math.min(ceiling, top);
            return true;
        }

        private void add(Kind kind, RailStyle.Tiles spec, ResourceLocation id, int zMin, int zMax, int zOrigin, int stepZ) {
            RailLayout layout = ctx.layout;
            int middle = Math.max(layout.zNorthEnd(), Math.min(layout.zSouthEnd(), (zMin + zMax) / 2));
            RailContext.Row row = ctx.row(middle);
            tiles.add(new Tile(kind, id, zMin, zMax, zOrigin, stepZ, row.trackX(), row.bedY(), spec.trackZ(), spec.trackY(),
                    spec.foundation(), spec.foundationDepth()));
        }
    }
}
