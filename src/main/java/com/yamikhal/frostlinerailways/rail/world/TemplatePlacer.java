package com.yamikhal.frostlinerailways.rail.world;

import com.yamikhal.frostlinerailways.rail.decor.Envelope;
import com.yamikhal.frostlinerailways.rail.decor.RailTemplates;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

/**
 * Writes a structure template into one chunk along the track (RAILWAYS.md §A8.6, §A8.8, §A8.9): stations,
 * cut covers and bridges.
 *
 * Template axes: x along the track, z across it, y up; the track runs through it at z = trackZ,
 * y = trackY. Template (tx, ty, tz) lands at
 *
 *   z = z0 + stepZ * tx                  stepZ +1: template x runs south, -1: north
 *   x = trackX - sign * (tz - trackZ)    sign +1: lower template z lies east of the track, -1: west
 *   y = bedY - trackY + ty
 *
 * with block states rotated (and mirrored where the axes flip) to match. Only this chunk's blocks are
 * written, the line's own track block is never touched, and nothing is placed inside the {@link Envelope}
 * trains drive through (the bed baker has already cleared it).
 *
 *   air in the template       becomes air
 *   nothing in the template   keeps the world; with clearEmpty, becomes air at or above track height
 *   foundation                fills from below a column's lowest block down to solid ground (at most
 *                             foundationDepth); with floorOnly only for columns whose lowest block is on
 *                             the template's floor (y = 0), so arches stay open
 */
final class TemplatePlacer {

    private TemplatePlacer() {
    }

    static void place(WorldGenLevel level, ChunkPos chunk, RailTemplates.Structure t, int trackX, int bedY, int trackZ, int trackY,
                      int z0, int stepZ, int sign, boolean clearEmpty, Optional<BlockState> foundation, int foundationDepth,
                      boolean floorOnly, Envelope envelope, BlockPos.MutableBlockPos pos) {
        int zEnd = z0 + stepZ * (t.sizeX() - 1);
        int zFrom = Math.max(chunk.getMinBlockZ(), Math.min(z0, zEnd));
        int zTo = Math.min(chunk.getMaxBlockZ(), Math.max(z0, zEnd));
        if (zFrom > zTo) {
            return;
        }
        Rotation rotation = stepZ > 0 ? Rotation.CLOCKWISE_90 : Rotation.COUNTERCLOCKWISE_90;
        boolean mirror = stepZ > 0 ? sign < 0 : sign > 0;
        int baseY = bedY - trackY;
        for (int z = zFrom; z <= zTo; z++) {
            int tx = (z - z0) * stepZ;
            for (int x = chunk.getMinBlockX(); x <= chunk.getMaxBlockX(); x++) {
                int tz = trackZ - sign * (x - trackX);
                if (tz < 0 || tz >= t.sizeZ()) {
                    continue;
                }
                boolean trackColumn = x == trackX;
                for (int ty = 0; ty < t.sizeY(); ty++) {
                    int y = baseY + ty;
                    if ((trackColumn && y == bedY) || envelope.contains(x, y, z)) {
                        continue;
                    }
                    pos.set(x, y, z);
                    BlockState state = t.state(tx, ty, tz);
                    if (state == null || state.isAir()) {
                        if ((state != null || (clearEmpty && ty >= trackY)) && !level.getBlockState(pos).isAir()) {
                            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                        }
                        continue;
                    }
                    level.setBlock(pos, (mirror ? state.mirror(Mirror.LEFT_RIGHT) : state).rotate(rotation), 2);
                    CompoundTag nbt = t.nbt(tx, ty, tz);
                    if (nbt != null) {
                        CompoundTag tag = nbt.copy();
                        tag.putInt("x", x);
                        tag.putInt("y", y);
                        tag.putInt("z", z);
                        level.getChunk(pos).setBlockEntityNbt(tag);
                    }
                }
                int bottom = t.bottom(tx, tz);
                if (bottom < 0 || foundation.isEmpty() || (floorOnly && bottom != 0)) {
                    continue;
                }
                for (int y = baseY + bottom - 1, n = 0; n < foundationDepth && y > level.getMinBuildHeight(); y--, n++) {
                    BlockState existing = level.getBlockState(pos.set(x, y, z));
                    if (!BedBaker.isLoose(existing) && existing.getFluidState().isEmpty()) {
                        break;
                    }
                    level.setBlock(pos, foundation.get(), 2);
                }
            }
        }
    }
}
