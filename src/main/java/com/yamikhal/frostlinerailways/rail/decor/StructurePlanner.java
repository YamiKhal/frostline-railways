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
import java.util.function.ToIntFunction;

/**
 * Where structures go along the line (RAILWAYS.md §A8.8, §A8.10, §A8.11): tunnel portals and interiors, covers
 * over deep cuts, bridges, built from a style's templates. Computed once per layout, style data, station data and
 * config, from the layout, seed and noise only, so every chunk agrees.
 *
 * Rows are classified, south to north:
 *
 *   tunnel       TUNNEL rows
 *   cover        CUT rows whose ground is at least the style's cut_cover.min_depth above the track; a cover
 *                stretch with a TUNNEL row within cut_cover.tunnel_margin of it is dropped (tunnels win)
 *   full bridge  BRIDGE rows whose track is at least bridge_structures.min_height above the ground
 *   flat bridge  lower BRIDGE rows
 *   blocked      within 8 blocks of a station, or off the line
 *
 * A stretch is a run of rows of one class on any track piece, in the style of its first row; up to merge_gap rows
 * of no class (full bridges: also flat-bridge rows) inside it do not end it. Its variant is picked once; every
 * template id is resolved through its numbered variations (RailTemplates#pick).
 *
 * Two kinds of parts:
 *
 *   tiles   whole templates (start, middle, end, flat), each aligned with the track at its middle row. They
 *           only go where the track is straight (straights and ramps), except start/end at a stretch's ends.
 *   slices  per-row parts (top on straight rows, top_curve on S-bends and diagonal shifts): each row places one
 *           x-slice of the template at that row's own track position, so they follow curves exactly
 *
 *   tunnel, cover  start at the south end and end at the north end (if the stretch is at least min_length and
 *                  both fit); middles fill the straight parts in between; slices (if the variant has a top) take
 *                  every other row of the stretch
 *   bridge         slices (top) on every row of the stretch — the deck and railing; below it, if the whole
 *                  stretch is straight, full bridge rows get start, whole middles, end (length within
 *                  max(min_length, bridgeFullMinLength) .. max_length, ≥ bridgeFullMinMiddles middles, ≥ min_gap
 *                  past the previous full bridge); otherwise flat tiles fill the straight parts
 *
 * Tiles never overlap; tiles of a kind and slices of that kind never share a row, except bridge slices, which sit
 * on top of the bridge's tiles.
 */
public final class StructurePlanner {

    public enum Kind { COVER, BRIDGE, TUNNEL }

    public record Tile(Kind kind, ResourceLocation template, int zMin, int zMax, int zOrigin, int stepZ, int trackX, int bedY,
                       int trackZ, int trackY, Optional<BlockState> foundation, int foundationDepth) {
    }

    /** Rows zMin..zMax each get one x-slice of top (straight rows) or topCurve (curved rows); slice x = (zMax - z) mod length. */
    public record SliceRun(Kind kind, ResourceLocation top, ResourceLocation topCurve, int zMin, int zMax, int trackY) {
    }

    /** The tiles and slices of a line. */
    public static final class Plan {
        private final List<Tile> tiles;
        private final List<SliceRun> slices;
        private final Intervals bridgeTiles;
        private final Intervals bridgeTops;
        private final Intervals covers;

        Plan(List<Tile> tiles, List<SliceRun> slices) {
            this.tiles = List.copyOf(tiles);
            this.slices = List.copyOf(slices);
            bridgeTiles = Intervals.of(tiles.stream().filter(t -> t.kind() == Kind.BRIDGE).toList(), Tile::zMin, Tile::zMax);
            bridgeTops = Intervals.of(slices.stream().filter(s -> s.kind() == Kind.BRIDGE).toList(), SliceRun::zMin, SliceRun::zMax);
            List<int[]> coverRows = new ArrayList<>();
            tiles.stream().filter(t -> t.kind() == Kind.COVER).forEach(t -> coverRows.add(new int[] {t.zMin(), t.zMax()}));
            slices.stream().filter(s -> s.kind() == Kind.COVER).forEach(s -> coverRows.add(new int[] {s.zMin(), s.zMax()}));
            covers = Intervals.of(coverRows, r -> r[0], r -> r[1]);
        }

        /** Tiles with any row in [minZ, maxZ]. */
        public List<Tile> touching(int minZ, int maxZ) {
            return within(tiles, Tile::zMin, Tile::zMax, minZ, maxZ);
        }

        /** Slice runs with any row in [minZ, maxZ]. */
        public List<SliceRun> slicesTouching(int minZ, int maxZ) {
            return within(slices, SliceRun::zMin, SliceRun::zMax, minZ, maxZ);
        }

        /** True if row z is under a bridge tile (the plain bridge's piers are left out there). */
        public boolean bridgeAt(int z) {
            return bridgeTiles.contains(z);
        }

        /** True if row z has a bridge top (the plain bridge's railing is left out there). */
        public boolean bridgeTopAt(int z) {
            return bridgeTops.contains(z);
        }

        /** True if row z has a cover tile or slice (terrain is not smoothed there). */
        public boolean coverAt(int z) {
            return covers.contains(z);
        }

        private static <T> List<T> within(List<T> sorted, ToIntFunction<T> min, ToIntFunction<T> max, int minZ, int maxZ) {
            List<T> out = new ArrayList<>();
            for (T item : sorted) {
                if (min.applyAsInt(item) > maxZ) {
                    break;
                }
                if (max.applyAsInt(item) >= minZ) {
                    out.add(item);
                }
            }
            return out;
        }
    }

    /** Sorted row intervals, answering "is z in one of them". */
    private static final class Intervals {
        private final int[] min;
        private final int[] max;

        private Intervals(int[] min, int[] max) {
            this.min = min;
            this.max = max;
        }

        static <T> Intervals of(List<T> items, ToIntFunction<T> lo, ToIntFunction<T> hi) {
            List<T> sorted = new ArrayList<>(items);
            sorted.sort(Comparator.comparingInt(lo));
            return new Intervals(sorted.stream().mapToInt(lo).toArray(), sorted.stream().mapToInt(hi).toArray());
        }

        boolean contains(int z) {
            // largest start at or below z, then check its end
            int lo = 0;
            int hi = min.length - 1;
            int found = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (min[mid] <= z) {
                    found = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            return found >= 0 && max[found] >= z;
        }
    }

    private enum RowKind { NONE, BLOCKED, COVER, BRIDGE_FULL, BRIDGE_FLAT, TUNNEL }

    private static final Plan EMPTY = new Plan(List.of(), List.of());
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
        private final List<SliceRun> slices = new ArrayList<>();
        private int lastFullNorth = Integer.MAX_VALUE;
        private int[] tunnelPrefix;

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
                boolean other = false;
                boolean sequence = kind == RowKind.COVER || kind == RowKind.TUNNEL;
                for (int probe = z - 1; probe >= layout.zNorthEnd(); probe--) {
                    RowKind k = rowKind(probe);
                    if (k == kind) {
                        north = probe;
                        misses = 0;
                        other = false;
                        continue;
                    }
                    misses++;
                    boolean absorbable = k == RowKind.NONE || (kind == RowKind.BRIDGE_FULL && k == RowKind.BRIDGE_FLAT);
                    // covers and tunnels also connect across a short gap of any rows but stations (covers: not tunnels)
                    boolean crossable = sequence && k != RowKind.BLOCKED && !(kind == RowKind.COVER && k == RowKind.TUNNEL);
                    other |= !absorbable;
                    if ((!absorbable && !crossable) || misses > (other ? spec.connectGap() : Math.max(spec.mergeGap(), spec.connectGap()))) {
                        break;
                    }
                }
                if (kind == RowKind.COVER && tunnelWithin(north - spec.tunnelMargin(), z + spec.tunnelMargin())) {
                    // mountain tunnels take priority: no overhead cover in the cuts leading into (or between) them
                    z = north - 1;
                    continue;
                }
                RailStyle.Parts parts = spec.pick(ctx.random(VARIANT_SALT, z, kind.ordinal()));
                switch (kind) {
                    case TUNNEL -> sequence(Kind.TUNNEL, spec, parts, z, north);
                    case COVER -> sequence(Kind.COVER, spec, parts, z, north);
                    case BRIDGE_FULL, BRIDGE_FLAT -> bridge(spec, parts, z, north, kind == RowKind.BRIDGE_FULL);
                    default -> { }
                }
                z = north - 1;
            }
            tiles.sort(Comparator.comparingInt(Tile::zMin));
            slices.sort(Comparator.comparingInt(SliceRun::zMin));
            return new Plan(tiles, slices);
        }

        /** True if any row in [minZ, maxZ] is a tunnel row (whether or not tunnel structures are on). */
        private boolean tunnelWithin(int minZ, int maxZ) {
            RailLayout layout = ctx.layout;
            if (tunnelPrefix == null) {
                // tunnelPrefix[i] = tunnel rows among the i northernmost rows
                int rows = layout.zSouthEnd() - layout.zNorthEnd() + 1;
                tunnelPrefix = new int[rows + 1];
                for (int i = 0; i < rows; i++) {
                    tunnelPrefix[i + 1] = tunnelPrefix[i] + (ctx.row(layout.zNorthEnd() + i).kind() == RailContext.Kind.TUNNEL ? 1 : 0);
                }
            }
            int lo = Math.max(minZ, layout.zNorthEnd()) - layout.zNorthEnd();
            int hi = Math.min(maxZ, layout.zSouthEnd()) - layout.zNorthEnd();
            return hi >= lo && tunnelPrefix[hi + 1] - tunnelPrefix[lo] > 0;
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

        private boolean straight(int z) {
            byte type = ctx.layout.type(ctx.row(z).piece());
            return type == RailLayout.STRAIGHT || type == RailLayout.RAMP;
        }

        private ResourceLocation pick(Optional<ResourceLocation> base, int z, int slot) {
            return base.map(id -> RailTemplates.pick(server, id, ctx.random(TEMPLATE_SALT, z, slot))).orElse(null);
        }

        private RailTemplates.Structure load(ResourceLocation id) {
            return id == null ? null : RailTemplates.structure(server, id);
        }

        /** Tunnels and covers: start and end at the ends, middles on straight parts, slices on the rest. */
        private void sequence(Kind kind, RailStyle.Tiles spec, RailStyle.Parts parts, int south, int north) {
            int bodySouth = south;
            int bodyNorth = north;
            int length = south - north + 1;
            ResourceLocation startId = pick(parts.start(), south, 1);
            ResourceLocation endId = pick(parts.end(), north, 2);
            RailTemplates.Structure start = load(startId);
            RailTemplates.Structure end = load(endId);
            if (start != null && end != null && length >= spec.minLength() && start.sizeX() + end.sizeX() <= length) {
                add(kind, spec, startId, south - start.sizeX() + 1, south, south, -1);
                add(kind, spec, endId, north, north + end.sizeX() - 1, north, 1);
                bodySouth = south - start.sizeX();
                bodyNorth = north + end.sizeX();
            }
            List<int[]> taken = new ArrayList<>();
            if (parts.middle().isPresent()) {
                for (int[] run : straightRuns(bodySouth, bodyNorth)) {
                    int[] filled = fill(kind, spec, parts.middle(), run[0], run[1], 10);
                    if (filled != null) {
                        taken.add(filled);
                    }
                }
            }
            sliceGaps(kind, spec, parts, bodySouth, bodyNorth, taken);
        }

        /** Bridges: tops on every row; below, the full set on straight full-height stretches, else flat tiles on straight parts. */
        private void bridge(RailStyle.Tiles spec, RailStyle.Parts parts, int south, int north, boolean full) {
            sliceGaps(Kind.BRIDGE, spec, parts, south, north, List.of());
            boolean allStraight = true;
            for (int z = south; z >= north && allStraight; z--) {
                allStraight = straight(z);
            }
            if (full && allStraight) {
                boolean gapOk = lastFullNorth == Integer.MAX_VALUE || lastFullNorth - south - 1 >= spec.minGap();
                if (gapOk && fullBridge(spec, parts, south, north)) {
                    return;
                }
            }
            if (parts.flat().isPresent()) {
                for (int[] run : straightRuns(south, north)) {
                    fill(Kind.BRIDGE, spec, parts.flat(), run[0], run[1], 40);
                }
            }
        }

        private boolean fullBridge(RailStyle.Tiles spec, RailStyle.Parts parts, int south, int north) {
            int length = south - north + 1;
            if (length < Math.max(spec.minLength(), RailwaysConfig.bridgeFullMinLength()) || length > spec.maxLength() || parts.middle().isEmpty()) {
                return false;
            }
            ResourceLocation startId = pick(parts.start(), south, 1);
            ResourceLocation endId = pick(parts.end(), north, 2);
            RailTemplates.Structure start = load(startId);
            RailTemplates.Structure end = load(endId);
            if (start == null || end == null) {
                return false;
            }
            List<ResourceLocation> ids = new ArrayList<>(List.of(startId));
            List<RailTemplates.Structure> chain = new ArrayList<>(List.of(start));
            int total = start.sizeX() + end.sizeX();
            int middles = 0;
            for (int i = 0; ; i++) {
                ResourceLocation id = pick(parts.middle(), south, 10 + i);
                RailTemplates.Structure middle = load(id);
                if (middle == null || total + middle.sizeX() > length) {
                    break;
                }
                ids.add(id);
                chain.add(middle);
                total += middle.sizeX();
                middles++;
            }
            if (middles < RailwaysConfig.bridgeFullMinMiddles()) {
                return false;
            }
            ids.add(endId);
            chain.add(end);
            int top = south - (length - total) / 2;
            for (int i = 0; i < chain.size(); i++) {
                int size = chain.get(i).sizeX();
                boolean last = i == chain.size() - 1;
                int zMin = top - size + 1;
                add(Kind.BRIDGE, spec, ids.get(i), zMin, top, last ? zMin : top, last ? 1 : -1);
                top -= size;
            }
            lastFullNorth = top + 1;
            return true;
        }

        /** Whole templates of one part laid south to north inside [rn, rs], centred; the rows they take, or null. */
        private int[] fill(Kind kind, RailStyle.Tiles spec, Optional<ResourceLocation> base, int rs, int rn, int slot) {
            int length = rs - rn + 1;
            List<ResourceLocation> ids = new ArrayList<>();
            List<RailTemplates.Structure> chain = new ArrayList<>();
            int total = 0;
            for (int i = 0; ; i++) {
                ResourceLocation id = pick(base, rs, slot + i);
                RailTemplates.Structure part = load(id);
                if (part == null || total + part.sizeX() > length) {
                    break;
                }
                ids.add(id);
                chain.add(part);
                total += part.sizeX();
            }
            if (chain.isEmpty()) {
                return null;
            }
            int top = rs - (length - total) / 2;
            int first = top;
            for (int i = 0; i < chain.size(); i++) {
                int size = chain.get(i).sizeX();
                add(kind, spec, ids.get(i), top - size + 1, top, top, -1);
                top -= size;
            }
            return new int[] {top + 1, first};
        }

        /** Runs of straight rows within [north, south], south first, as {south, north}. */
        private List<int[]> straightRuns(int south, int north) {
            List<int[]> runs = new ArrayList<>();
            int runSouth = Integer.MIN_VALUE;
            for (int z = south; z >= north - 1; z--) {
                boolean ok = z >= north && straight(z);
                if (ok && runSouth == Integer.MIN_VALUE) {
                    runSouth = z;
                } else if (!ok && runSouth != Integer.MIN_VALUE) {
                    runs.add(new int[] {runSouth, z + 1});
                    runSouth = Integer.MIN_VALUE;
                }
            }
            return runs;
        }

        /** Slice runs over the rows of [north, south] not in taken ({zMin, zMax} ranges), if the parts have a top. */
        private void sliceGaps(Kind kind, RailStyle.Tiles spec, RailStyle.Parts parts, int south, int north, List<int[]> taken) {
            if (parts.top().isEmpty() || south < north) {
                return;
            }
            ResourceLocation top = pick(parts.top(), south, 5);
            ResourceLocation curve = parts.topCurve().isPresent() ? pick(parts.topCurve(), south, 6) : top;
            if (top == null) {
                return;
            }
            int runSouth = Integer.MIN_VALUE;
            for (int z = south; z >= north - 1; z--) {
                boolean free = z >= north;
                for (int[] range : taken) {
                    if (z >= range[0] && z <= range[1]) {
                        free = false;
                        break;
                    }
                }
                if (free && runSouth == Integer.MIN_VALUE) {
                    runSouth = z;
                } else if (!free && runSouth != Integer.MIN_VALUE) {
                    slices.add(new SliceRun(kind, top, curve == null ? top : curve, z + 1, runSouth, spec.topTrackY()));
                    runSouth = Integer.MIN_VALUE;
                }
            }
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
