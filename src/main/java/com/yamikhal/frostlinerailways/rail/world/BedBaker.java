package com.yamikhal.frostlinerailways.rail.world;

import com.yamikhal.frostlinerailways.RailwaysConfig;
import com.yamikhal.frostlinerailways.rail.RailLineDef;
import com.yamikhal.frostlinerailways.rail.decor.RailContext;
import com.yamikhal.frostlinerailways.rail.decor.RailStyle;
import com.yamikhal.frostlinerailways.rail.decor.StationPlanner;
import com.yamikhal.frostlinerailways.rail.decor.StructurePlanner;
import com.yamikhal.frostlinerailways.rail.layout.RailLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Shapes the terrain under and around the track in one chunk (RAILWAYS.md §A3.2, §A8.5, §A8.8–§A8.10), using
 * the row's kind and style from {@link RailContext}:
 *
 *   OPEN    air clearance blocks above the track; the gap below filled; ballast on top
 *   CUT     the ground above the track is cut away down to the track; ballast
 *   TUNNEL  a tube of the style's tunnel half width and height; the style's lining on its walls and
 *           ceiling; at a portal row the style's portal block instead; ballast floor
 *   BRIDGE  the style's deck under the track, one block wider than the train each side, railing on that
 *           outer row, piers every pierSpacing (railing and piers left out where a bridge structure stands)
 *
 * Blending, on OPEN and CUT rows (bed.berm_reach, bed.cut_slope_reach, bed.slope_step):
 *
 *   berm    beside the ballast, where the ground is lower than the bed, an embankment falls away
 *           slope_step blocks per block outward, built from the column's own surface block (top) and the
 *           block under it (inside) — grass over dirt, snow over stone — or the style's fill
 *   slope   beyond the bed, where the ground is higher than the track, the cut side is cut back rising
 *           slope_step blocks per block outward, and the column's own surface block is put on the new top
 *
 * With clearAboveTrack, outside tunnels, every bed column of the chunk is cleared above the track to at least
 * clearHeight (or the surface if higher); loose blocks — leaves, logs, plants, snow, ice — are cleared as high
 * in clearExtraWidth more columns each side.
 *
 * Only this chunk's columns are shaped. Columns up to MARGIN blocks outside it (inside the 3x3 feature
 * region) only have loose blocks cleared, so trees of neighbours decorated later cannot hang over the line.
 * Nothing further than MARGIN from the chunk is touched: WorldGenRegion only allows neighbours.
 */
public final class BedBaker {

    static final int MARGIN = 8;
    /** Extra columns each side on S-bends and diagonal shifts and curveMargin rows around them (RailContext#nearCurve). */
    private static final int CURVE_EXTRA = 2;
    private static final int GROUND_SCAN = 32;

    private BedBaker() {
    }

    static void bake(WorldGenLevel level, ChunkPos chunk, RailContext ctx) {
        RailLayout layout = ctx.layout;
        RailLineDef.Bed bed = ctx.def.bed();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean clearAbove = RailwaysConfig.clearAboveTrack();
        int extra = clearAbove ? RailwaysConfig.clearExtraWidth() : 0;
        int trainHalf = RailwaysConfig.trainHalfWidth();
        int clearHeight = RailwaysConfig.clearHeight();
        int maxY = level.getMaxBuildHeight() - 1;
        StructurePlanner.Plan plan = StructurePlanner.plan(ctx, level.getServer());

        int fromZ = Math.min(chunk.getMaxBlockZ() + MARGIN, layout.zSouthEnd());
        int toZ = Math.max(chunk.getMinBlockZ() - MARGIN, layout.zNorthEnd());
        for (int z = fromZ; z >= toZ; z--) {
            RailContext.Row row = ctx.row(z);
            RailStyle style = row.style().value();
            byte type = layout.type(row.piece());
            int curveExtra = ctx.nearCurve(z) ? CURVE_EXTRA : 0;
            boolean tunnel = row.kind() == RailContext.Kind.TUNNEL;
            boolean bridge = row.kind() == RailContext.Kind.BRIDGE;
            boolean ground = !tunnel && !bridge;
            int tubeHalf = tunnel ? style.tunnel().halfWidth() : bed.halfWidth();
            int width = Math.max(Math.max(tubeHalf + 1, bed.halfWidth()), trainHalf + 1) + curveExtra;
            int shoulder = bed.halfWidth() + curveExtra;
            int reach = width + (tunnel ? 0 : extra);
            // an overhead cover keeps its cut walls: no smoothing under it
            boolean smooth = ground && !plan.coverAt(z);
            if (smooth) {
                reach = Math.max(reach, Math.max(shoulder + bed.bermReach(), width + bed.cutSlopeReach()));
            }
            boolean bridgeStructure = bridge && plan.bridgeAt(z);
            boolean bridgeTop = bridge && plan.bridgeTopAt(z);
            Block keep = style.coverLayer().map(layer -> layer.state().getBlock()).orElse(Blocks.SNOW);
            // a neighbour may already have built a station here: its moss, leaves and plants are not trees
            boolean station = atStation(ctx, z);
            boolean zInChunk = z >= chunk.getMinBlockZ() && z <= chunk.getMaxBlockZ();
            int centreX = row.trackX();
            for (int dx = -reach; dx <= reach; dx++) {
                int x = centreX + dx;
                if (x < chunk.getMinBlockX() - MARGIN || x > chunk.getMaxBlockX() + MARGIN) {
                    continue;
                }
                int abs = Math.abs(dx);
                boolean inChunk = zInChunk && x >= chunk.getMinBlockX() && x <= chunk.getMaxBlockX();
                boolean bedColumn = abs <= width;
                // at least clearHeight above the track, and up to the surface where that is higher
                int top = clearAbove && !tunnel ? Math.min(maxY, Math.max(row.bedY() + clearHeight, surfaceTop(level, x, z))) : 0;
                if (inChunk) {
                    int natural = smooth && abs > shoulder ? groundY(level, pos, x, z) : 0;
                    if (bedColumn) {
                        column(level, pos, x, z, dx, curveExtra, row, style, bed, bridgeStructure, bridgeTop, trainHalf);
                        if (clearAbove && !tunnel) {
                            clear(level, pos, x, z, row.bedY() + 1, top, null);
                        }
                    } else if (smooth && abs - width <= bed.cutSlopeReach()) {
                        slope(level, pos, x, z, row.bedY() + (abs - width) * bed.slopeStep(), natural, top);
                    }
                    if (smooth && abs > shoulder && abs - shoulder <= bed.bermReach()) {
                        berm(level, pos, x, z, row.bedY() - 1 - (abs - shoulder) * bed.slopeStep(), natural, style);
                    }
                    if (!bedColumn && clearAbove && !tunnel && !station) {
                        clear(level, pos, x, z, row.bedY(), top, keep);
                    }
                    continue;
                }
                if (station) {
                    continue;
                }
                if (clearAbove && !tunnel) {
                    clear(level, pos, x, z, row.bedY(), top, keep);
                } else if (bedColumn) {
                    clear(level, pos, x, z, row.bedY(), row.bedY() + Math.max(bed.clearance(), style.tunnel().height()), keep);
                }
            }
        }
    }

    private static void column(WorldGenLevel level, BlockPos.MutableBlockPos pos, int x, int z, int dx, int curveExtra,
                               RailContext.Row row, RailStyle style, RailLineDef.Bed bed, boolean bridgeStructure, boolean bridgeTop,
                               int trainHalf) {
        int bedY = row.bedY();
        int abs = Math.abs(dx);
        boolean deckColumn = abs <= bed.halfWidth() + curveExtra;
        switch (row.kind()) {
            case TUNNEL -> {
                RailStyle.Tunnel tunnel = style.tunnel();
                int half = tunnel.halfWidth() + curveExtra;
                int top = bedY + tunnel.height();
                BlockState wall = row.portal() ? tunnel.portal().or(tunnel::lining).orElse(null) : tunnel.lining().orElse(null);
                if (abs <= half) {
                    for (int y = bedY; y <= top; y++) {
                        setAir(level, pos.set(x, y, z));
                    }
                    if (wall != null) {
                        level.setBlock(pos.set(x, top + 1, z), wall, 2);
                    }
                    level.setBlock(pos.set(x, bedY - 1, z), style.bed().ballast(), 2);
                } else if (abs == half + 1 && wall != null) {
                    for (int y = bedY; y <= top + 1; y++) {
                        level.setBlock(pos.set(x, y, z), wall, 2);
                    }
                }
            }
            case CUT -> {
                int ground = groundY(level, pos, x, z);
                for (int y = bedY; y <= Math.max(ground + 2, bedY + bed.clearance()); y++) {
                    setAir(level, pos.set(x, y, z));
                }
                if (deckColumn) {
                    level.setBlock(pos.set(x, bedY - 1, z), edgeOrBallast(style, abs, bed), 2);
                }
            }
            case BRIDGE -> {
                for (int y = bedY; y <= bedY + bed.clearance(); y++) {
                    setAir(level, pos.set(x, y, z));
                }
                // the deck reaches one block past the train's width, so the railing stands outside it
                int deckHalf = Math.max(bed.halfWidth(), trainHalf + 1) + curveExtra;
                if (abs > deckHalf) {
                    return;
                }
                RailStyle.Bridge bridge = style.bridge();
                level.setBlock(pos.set(x, bedY - 1, z), bridge.deck(), 2);
                if (!bridgeTop && abs == deckHalf && bridge.railing().isPresent()) {
                    level.setBlock(pos.set(x, bedY, z), bridge.railing().get(), 2);
                }
                if (!bridgeStructure && abs <= 1 && Math.floorMod(z, bed.pierSpacing()) == 0) {
                    int ground = groundY(level, pos, x, z);
                    int bottom = Math.max(ground + 1, bedY - 1 - bed.maxPierDepth());
                    for (int y = bedY - 2; y >= bottom; y--) {
                        level.setBlock(pos.set(x, y, z), bridge.pier(), 2);
                    }
                }
            }
            case OPEN -> {
                int ground = groundY(level, pos, x, z);
                for (int y = bedY; y <= bedY + bed.clearance(); y++) {
                    setAir(level, pos.set(x, y, z));
                }
                if (!deckColumn) {
                    return;
                }
                for (int y = ground + 1; y <= bedY - 2; y++) {
                    level.setBlock(pos.set(x, y, z), style.bed().fill(), 2);
                }
                level.setBlock(pos.set(x, bedY - 1, z), edgeOrBallast(style, abs, bed), 2);
            }
        }
    }

    /** An embankment column up to bermTop over natural ground, from the column's own surface and subsurface blocks. */
    private static void berm(WorldGenLevel level, BlockPos.MutableBlockPos pos, int x, int z, int bermTop, int natural, RailStyle style) {
        if (bermTop <= natural) {
            return;
        }
        BlockState surface = level.getBlockState(pos.set(x, natural, z));
        if (!solidGround(level, pos, surface)) {
            surface = style.bed().fill();
        }
        BlockState inner = level.getBlockState(pos.set(x, natural - 1, z));
        if (!solidGround(level, pos, inner)) {
            inner = surface;
        }
        if (solidGround(level, pos.set(x, natural, z), level.getBlockState(pos))) {
            level.setBlock(pos, inner, 2); // the old surface is buried now
        }
        for (int y = natural + 1; y <= bermTop; y++) {
            level.setBlock(pos.set(x, y, z), y == bermTop ? surface : inner, 2);
        }
    }

    /** A cut side: everything from floor up is cleared where the ground reaches floor, and the old surface block goes on the new top. */
    private static void slope(WorldGenLevel level, BlockPos.MutableBlockPos pos, int x, int z, int floor, int natural, int top) {
        if (natural < floor) {
            return;
        }
        BlockState surface = level.getBlockState(pos.set(x, natural, z));
        clear(level, pos, x, z, floor, Math.max(top, natural), null);
        BlockState below = level.getBlockState(pos.set(x, floor - 1, z));
        if (solidGround(level, pos, surface) && solidGround(level, pos, below)) {
            level.setBlock(pos, surface, 2);
        }
    }

    private static boolean solidGround(WorldGenLevel level, BlockPos pos, BlockState state) {
        return !isLoose(state) && state.getFluidState().isEmpty() && state.isCollisionShapeFullBlock(level, pos);
    }

    private static BlockState edgeOrBallast(RailStyle style, int abs, RailLineDef.Bed bed) {
        return abs == bed.halfWidth() && style.bed().shoulder().isPresent() ? style.bed().shoulder().get() : style.bed().ballast();
    }

    /** Highest solid ground in the column, ignoring leaves, logs, snow layers and plants. */
    private static int groundY(WorldGenLevel level, BlockPos.MutableBlockPos pos, int x, int z) {
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
        int floor = Math.max(level.getMinBuildHeight(), y - GROUND_SCAN);
        while (y > floor && isLoose(level.getBlockState(pos.set(x, y, z)))) {
            y--;
        }
        return y;
    }

    private static boolean atStation(RailContext ctx, int z) {
        for (StationPlanner.Site site : StationPlanner.sites(ctx)) {
            if (z >= site.zNorth() - MARGIN && z <= site.zSouth() + MARGIN) {
                return true;
            }
        }
        return false;
    }

    private static int surfaceTop(WorldGenLevel level, int x, int z) {
        return level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
    }

    /**
     * Air from fromY to toY. keep null: every block. Otherwise only loose blocks (leaves, logs, plants, snow, ice),
     * bottom up, but a keep block (the style's cover layer, snow) still standing on something solid stays: a
     * neighbouring chunk's cover layer must survive this chunk's clearing, a snow layer left floating on removed
     * leaves must not.
     */
    private static void clear(WorldGenLevel level, BlockPos.MutableBlockPos pos, int x, int z, int fromY, int toY, Block keep) {
        for (int y = fromY; y <= toY; y++) {
            BlockState state = level.getBlockState(pos.set(x, y, z));
            if (state.isAir()) {
                continue;
            }
            if (keep != null) {
                if (!isLoose(state) && !state.is(BlockTags.ICE)) {
                    continue;
                }
                if (state.is(keep) || state.is(Blocks.SNOW)) {
                    BlockState below = level.getBlockState(pos.set(x, y - 1, z));
                    if (!below.isAir() && below.isFaceSturdy(level, pos, Direction.UP)) {
                        continue;
                    }
                    pos.set(x, y, z);
                }
            }
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
        }
    }

    static boolean isLoose(BlockState state) {
        return state.isAir() || state.canBeReplaced() || state.is(Blocks.SNOW)
                || state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS);
    }

    private static void setAir(WorldGenLevel level, BlockPos pos) {
        if (!level.getBlockState(pos).isAir()) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
        }
    }
}
