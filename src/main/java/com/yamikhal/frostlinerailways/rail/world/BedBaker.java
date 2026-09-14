package com.yamikhal.frostlinerailways.rail.world;

import com.yamikhal.frostlinerailways.rail.RailLineDef;
import com.yamikhal.frostlinerailways.rail.decor.RailContext;
import com.yamikhal.frostlinerailways.rail.decor.RailStyle;
import com.yamikhal.frostlinerailways.rail.layout.RailLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Shapes the terrain under and around the track in one chunk (RAILWAYS.md §A3.2, §A8.5), using the
 * row's kind and style from {@link RailContext}:
 *
 *   OPEN    air clearance blocks above the track; a gap of at most maxFill filled; ballast on top
 *   CUT     the ground above the track is cut away down to the track; ballast
 *   TUNNEL  a tube of the style's tunnel half width and height; the style's lining on its walls and
 *           ceiling; at a portal row the style's portal block instead; ballast floor
 *   BRIDGE  the style's deck under the track, railing at the deck's edge, piers every pierSpacing
 *
 * Only this chunk's columns are shaped. Columns up to MARGIN blocks outside it (inside the 3x3
 * feature region) only have loose blocks — leaves, logs, plants, snow, ice — cleared from the
 * clearance, so trees of neighbours decorated later cannot hang over the line.
 */
public final class BedBaker {

    static final int MARGIN = 8;
    /** Extra columns each side on S-bends and diagonal shifts, where the curve leaves the straight line between its ends. */
    private static final int CURVE_EXTRA = 2;
    private static final int GROUND_SCAN = 32;

    private BedBaker() {
    }

    static void bake(WorldGenLevel level, ChunkPos chunk, RailContext ctx) {
        RailLayout layout = ctx.layout;
        RailLineDef.Bed bed = ctx.def.bed();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        int fromZ = Math.min(chunk.getMaxBlockZ() + MARGIN, layout.zSouthEnd());
        int toZ = Math.max(chunk.getMinBlockZ() - MARGIN, layout.zNorthEnd());
        for (int z = fromZ; z >= toZ; z--) {
            RailContext.Row row = ctx.row(z);
            RailStyle style = row.style().value();
            byte type = layout.type(row.piece());
            int curveExtra = type == RailLayout.BEND || type == RailLayout.SHIFT ? CURVE_EXTRA : 0;
            int tubeHalf = row.kind() == RailContext.Kind.TUNNEL ? style.tunnel().halfWidth() : bed.halfWidth();
            int width = Math.max(tubeHalf + 1, bed.halfWidth()) + curveExtra;
            boolean zInChunk = z >= chunk.getMinBlockZ() && z <= chunk.getMaxBlockZ();
            int centreX = row.trackX();
            for (int dx = -width; dx <= width; dx++) {
                int x = centreX + dx;
                if (zInChunk && x >= chunk.getMinBlockX() && x <= chunk.getMaxBlockX()) {
                    column(level, pos, x, z, dx, curveExtra, row, style, bed);
                } else {
                    clearLoose(level, pos, x, z, row.bedY(), Math.max(bed.clearance(), style.tunnel().height()));
                }
            }
        }
    }

    private static void column(WorldGenLevel level, BlockPos.MutableBlockPos pos, int x, int z, int dx, int curveExtra,
                               RailContext.Row row, RailStyle style, RailLineDef.Bed bed) {
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
                if (!deckColumn) {
                    return;
                }
                RailStyle.Bridge bridge = style.bridge();
                level.setBlock(pos.set(x, bedY - 1, z), bridge.deck(), 2);
                if (abs == bed.halfWidth() + curveExtra && bridge.railing().isPresent()) {
                    level.setBlock(pos.set(x, bedY, z), bridge.railing().get(), 2);
                }
                if (abs <= 1 && Math.floorMod(z, bed.pierSpacing()) == 0) {
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

    private static void clearLoose(WorldGenLevel level, BlockPos.MutableBlockPos pos, int x, int z, int bedY, int clearance) {
        for (int y = bedY; y <= bedY + clearance; y++) {
            BlockState state = level.getBlockState(pos.set(x, y, z));
            if (!state.isAir() && (isLoose(state) || state.is(BlockTags.ICE))) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
            }
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
