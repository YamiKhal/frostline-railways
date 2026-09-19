package com.yamikhal.frostlinerailways.rail.sites;

import com.yamikhal.frostlinerailways.RailwaysConfig;
import com.yamikhal.frostlinerailways.rail.RailLineDef;
import com.yamikhal.frostlinerailways.rail.decor.RailContext;
import com.yamikhal.frostlinerailways.rail.decor.RailDecorData;
import com.yamikhal.frostlinerailways.rail.decor.RailStyle;
import com.yamikhal.frostlinerailways.rail.decor.RailTemplates;
import com.yamikhal.frostlinerailways.rail.layout.RailLayout;
import com.yamikhal.frostlinerailways.rail.layout.RailLayoutService;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The ground the railway works on, as seen by structures (RAILWAYS.md §A8.15). All from layout rows (noise), styles
 * and template sizes, so the answer is the same in every thread and chunk order.
 *
 *   works half width  how far from the track centre the line changes the world at a row — the same numbers BedBaker
 *                     uses: the tube/bed/train width; on open and cut rows the cut slopes (cut_slope_reach), berms
 *                     (berm_reach) and the cleared width (clearExtraWidth); bridge decks and railings; and the widest
 *                     tile (cut cover, bridge, tunnel template, any variant) of the row's style
 *   vertical window   the heights the rail changes at a row: from below the lower of track and ground up to the cleared
 *                     height above the track (tunnels: the bore)
 *   ends              rows up to END_REACH past each end count as the end row (termini)
 */
final class LineBand {

    static final int END_REACH = 16;
    private static final int BELOW = 4;
    private static final int ABOVE = 4;
    /** Bridge deck one block past the train's space, railing one more (BedBaker). */
    private static final int BRIDGE_EXTRA = 2;

    /** Widest tile per style id, for the style entry list it was measured from. */
    private static final Map<ResourceLocation, Integer> TILE_REACH = new ConcurrentHashMap<>();
    private static volatile Object tileOwner;

    private LineBand() {
    }

    /** The row at z, or the end row within END_REACH past an end; null farther away. */
    static RailContext.Row row(RailContext ctx, int z) {
        RailLayout layout = ctx.layout;
        if (z > layout.zSouthEnd() + END_REACH || z < layout.zNorthEnd() - END_REACH) {
            return null;
        }
        return ctx.row(Math.max(layout.zNorthEnd(), Math.min(layout.zSouthEnd(), z)));
    }

    /** How far from the track centre the line changes the world at this row (see class doc). */
    static int halfWidth(RailContext ctx, RailContext.Row row) {
        RailLineDef.Bed bed = ctx.def.bed();
        RailStyle style = row.style().value();
        byte type = ctx.layout.type(row.piece());
        int curve = type == RailLayout.BEND || type == RailLayout.SHIFT ? 1 : 0;
        boolean tunnel = row.kind() == RailContext.Kind.TUNNEL;
        boolean bridge = row.kind() == RailContext.Kind.BRIDGE;
        int tube = tunnel ? style.tunnel().halfWidth() : bed.halfWidth();
        int width = Math.max(Math.max(tube + 1, bed.halfWidth()), RailwaysConfig.trainHalfWidth() + 1) + curve;
        int works = width;
        if (!tunnel) {
            if (RailwaysConfig.clearAboveTrack()) {
                works = Math.max(works, width + RailwaysConfig.clearExtraWidth());
            }
            if (bridge) {
                works = Math.max(works, width + BRIDGE_EXTRA);
            } else {
                works = Math.max(works, Math.max(width + bed.cutSlopeReach(), bed.halfWidth() + curve + bed.bermReach()));
            }
        }
        return Math.max(works, tileReach(row.style().id(), style) + curve);
    }

    /** The widest works half width any row of the line can have (every style), for coarse range tests. */
    static int maxHalfWidth(RailLineDef def) {
        RailLineDef.Bed bed = def.bed();
        int widest = 0;
        int tube = bed.halfWidth();
        for (RailDecorData.Entry<RailStyle> entry : RailDecorData.STYLES.entries()) {
            tube = Math.max(tube, entry.value().tunnel().halfWidth());
            widest = Math.max(widest, tileReach(entry.id(), entry.value()));
        }
        int width = Math.max(tube + 1, RailwaysConfig.trainHalfWidth() + 1) + 1;
        int works = Math.max(width + Math.max(bed.cutSlopeReach(), Math.max(BRIDGE_EXTRA, RailwaysConfig.clearExtraWidth())),
                bed.halfWidth() + 1 + bed.bermReach());
        return Math.max(works, widest + 1);
    }

    /**
     * The widest a style's tiles reach from the track: whole templates (start, middle, end, flat) run from track_z,
     * so max(track_z, size_z - 1 - track_z); per-row tops are centred, so size_z / 2 rounded up. Every variant and
     * numbered file counts. Measured once per style per style data; 0 without a server or tiles.
     */
    private static int tileReach(ResourceLocation id, RailStyle style) {
        List<RailDecorData.Entry<RailStyle>> owner = RailDecorData.STYLES.entries();
        if (tileOwner != owner) {
            TILE_REACH.clear();
            tileOwner = owner;
        }
        Integer cached = TILE_REACH.get(id);
        if (cached != null) {
            return cached;
        }
        ServerLevel level = RailLayoutService.level();
        MinecraftServer server = level == null ? null : level.getServer();
        if (server == null) {
            return 0;
        }
        int reach = 0;
        for (Optional<RailStyle.Tiles> tiles : List.of(style.cutCover(), style.bridgeTiles(), style.tunnelTiles())) {
            if (tiles.isEmpty()) {
                continue;
            }
            int trackZ = tiles.get().params().trackZ();
            List<RailStyle.Parts> sets = new java.util.ArrayList<>(tiles.get().variants());
            sets.add(tiles.get().parts());
            for (RailStyle.Parts parts : sets) {
                for (Optional<ResourceLocation> whole : List.of(parts.start(), parts.middle(), parts.end(), parts.flat())) {
                    if (whole.isPresent()) {
                        for (ResourceLocation file : RailTemplates.files(server, whole.get())) {
                            RailTemplates.Structure t = RailTemplates.structure(server, file);
                            if (t != null) {
                                reach = Math.max(reach, Math.max(trackZ, t.sizeZ() - 1 - trackZ));
                            }
                        }
                    }
                }
                for (Optional<ResourceLocation> top : List.of(parts.top(), parts.topCurve())) {
                    if (top.isPresent()) {
                        for (ResourceLocation file : RailTemplates.files(server, top.get())) {
                            RailTemplates.Structure t = RailTemplates.structure(server, file);
                            if (t != null) {
                                reach = Math.max(reach, (t.sizeZ() + 1) / 2);
                            }
                        }
                    }
                }
            }
        }
        TILE_REACH.put(id, reach);
        return reach;
    }

    static int windowLow(RailContext.Row row) {
        return Math.min(row.bedY(), row.groundTop()) - BELOW;
    }

    static int windowHigh(RailContext.Row row) {
        int bed = row.bedY();
        int ground = row.groundTop();
        if (row.kind() == RailContext.Kind.TUNNEL) {
            return bed + row.style().value().tunnel().height() + ABOVE;
        }
        int top = RailwaysConfig.clearAboveTrack()
                ? Math.max(ground + 1, bed + RailwaysConfig.clearHeight())
                : Math.max(ground, bed + RailwaysConfig.trainHeight());
        return top + ABOVE;
    }

    /**
     * True if the box comes within works half width + {@code extra} of the track centre at any row beside it; with
     * {@code vertical} only where it also overlaps that row's vertical window.
     */
    static boolean hits(RailContext ctx, BoundingBox box, int extra, boolean vertical) {
        for (int z = box.minZ(); z <= box.maxZ(); z++) {
            RailContext.Row row = row(ctx, z);
            if (row == null) {
                continue;
            }
            double half = halfWidth(ctx, row) + extra;
            if (box.maxX() < row.centreX() - half || box.minX() > row.centreX() + half) {
                continue;
            }
            if (!vertical || (box.maxY() >= windowLow(row) && box.minY() <= windowHigh(row))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Smallest distance, over the rows beside the box, from the track centre to the box's nearest column on side
     * {@code sign} (+1 east, -1 west); negative if the box reaches the track or past it. Integer.MAX_VALUE if no row is
     * beside the box.
     */
    static int nearest(RailContext ctx, BoundingBox box, int sign) {
        int best = Integer.MAX_VALUE;
        for (int z = box.minZ(); z <= box.maxZ(); z++) {
            RailContext.Row row = row(ctx, z);
            if (row == null) {
                continue;
            }
            double gap = sign > 0 ? box.minX() - row.centreX() : row.centreX() - box.maxX();
            best = Math.min(best, (int) Math.floor(gap));
        }
        return best;
    }

    /**
     * True if the box stays clear of the band on side {@code sign}: at every row beside it, its nearest column is more
     * than works half width + {@code extra} from the track centre on that side.
     */
    static boolean clearOn(RailContext ctx, BoundingBox box, int sign, int extra) {
        for (int z = box.minZ(); z <= box.maxZ(); z++) {
            RailContext.Row row = row(ctx, z);
            if (row == null) {
                continue;
            }
            double gap = sign > 0 ? box.minX() - row.centreX() : row.centreX() - box.maxX();
            if (gap <= halfWidth(ctx, row) + extra) {
                return false;
            }
        }
        return true;
    }

    /** Blocks between two boxes horizontally (Chebyshev); negative when they overlap. */
    static int gap(BoundingBox a, BoundingBox b) {
        int dx = Math.max(a.minX() - b.maxX(), b.minX() - a.maxX()) - 1;
        int dz = Math.max(a.minZ() - b.maxZ(), b.minZ() - a.maxZ()) - 1;
        return Math.max(dx, dz);
    }
}
