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
 * written, the line's own track block is never touched, nor the metal girders under its rails (girderTracks), and
 * nothing is placed inside the {@link Envelope} trains drive through (the bed baker has already cleared it).
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

    /**
     * One x-slice (tx) of a template at a single row z, centred on that row's track: template z = sizeZ / 2 is the
     * track, y = trackY the track's height. Oriented like a tile running north (x = 0 at its south edge). Used
     * for per-row parts (tops) that follow curves row by row.
     */
    static void placeSlice(WorldGenLevel level, ChunkPos chunk, RailTemplates.Structure t, int tx, int trackX, int bedY, int trackY,
                           int z, Envelope envelope, BlockPos.MutableBlockPos pos) {
        if (z < chunk.getMinBlockZ() || z > chunk.getMaxBlockZ()) {
            return;
        }
        int trackZ = t.sizeZ() / 2;
        int baseY = bedY - trackY;
        for (int tz = 0; tz < t.sizeZ(); tz++) {
            int x = trackX - (tz - trackZ);
            if (x < chunk.getMinBlockX() || x > chunk.getMaxBlockX()) {
                continue;
            }
            for (int ty = 0; ty < t.sizeY(); ty++) {
                int y = baseY + ty;
                if ((x == trackX && y == bedY) || envelope.contains(x, y, z) || TrackWriter.isGirderColumn(x, y, trackX, bedY)) {
                    continue;
                }
                BlockState state = t.state(tx, ty, tz);
                if (state == null) {
                    continue;
                }
                pos.set(x, y, z);
                if (state.isAir()) {
                    if (!level.getBlockState(pos).isAir()) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                    }
                    continue;
                }
                level.setBlock(pos, state.mirror(Mirror.LEFT_RIGHT).rotate(Rotation.COUNTERCLOCKWISE_90), 2);
                CompoundTag nbt = t.nbt(tx, ty, tz);
                if (nbt != null) {
                    CompoundTag tag = nbt.copy();
                    tag.putInt("x", x);
                    tag.putInt("y", y);
                    tag.putInt("z", z);
                    level.getChunk(pos).setBlockEntityNbt(tag);
                }
            }
        }
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
                    if ((trackColumn && y == bedY) || envelope.contains(x, y, z) || TrackWriter.isGirderColumn(x, y, trackX, bedY)) {
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
