package com.yamikhal.frostlinerailways.rail.sites;

import com.yamikhal.frostlinerailways.RailwaysConfig;
import com.yamikhal.frostlinerailways.rail.decor.RailContext;
import com.yamikhal.frostlinerailways.rail.layout.RailLayout;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * The ground the railway works on, as seen by structures (RAILWAYS.md §A8.15). All from layout rows (noise), so the
 * answer is the same in every thread and chunk order.
 *
 *   bed half width  the widest the line reaches from its centre at a row: bed or tunnel half width, one more on
 *                   S-bends and diagonal shifts (their rails cross rows at an angle)
 *   vertical window the heights the rail changes at a row: from below the lower of track and ground up to the cleared
 *                   height above the track (tunnels: the bore)
 *   ends            rows up to END_REACH past each end count as the end row (termini)
 */
final class LineBand {

    static final int END_REACH = 16;
    private static final int BELOW = 4;
    private static final int ABOVE = 4;

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

    static int halfWidth(RailContext ctx, RailContext.Row row) {
        byte type = ctx.layout.type(row.piece());
        int curve = type == RailLayout.BEND || type == RailLayout.SHIFT ? 1 : 0;
        return Math.max(ctx.def.bed().halfWidth(), row.style().value().tunnel().halfWidth()) + curve;
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
     * True if the box comes within bed half width + {@code extra} of the track centre at any row beside it; with
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
     * than bed half width + {@code extra} from the track centre on that side.
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
