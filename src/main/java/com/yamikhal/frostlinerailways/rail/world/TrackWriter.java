package com.yamikhal.frostlinerailways.rail.world;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.yamikhal.frostlinerailways.rail.create.PieceGeometry;
import com.yamikhal.frostlinerailways.rail.layout.RailLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Writes Create track for one chunk during world generation (RAILWAYS.md §A3.2, §A8.4).
 *
 * Straights are plain z-axis track blockstates. Every other piece comes from {@link PieceGeometry}:
 * its blocks (z-axis or diagonal shapes, HAS_BE on curve ends) and its block entities, written as NBT
 * into the proto chunk (ChunkAccess#setBlockEntityNbt) with their curves and, on smoothed ramp blocks,
 * Create's tilt. Vanilla promotes that NBT on load like track read from disk. No onPlace, no
 * scheduled tick, no TrackPropagator. Only blocks inside the chunk are written.
 */
public final class TrackWriter {

    private TrackWriter() {
    }

    static void write(WorldGenLevel level, ChunkPos chunk, RailLayout layout) {
        int minZ = chunk.getMinBlockZ();
        int maxZ = chunk.getMaxBlockZ();
        int first = layout.pieceAt(Math.min(maxZ, layout.zSouthEnd()));
        if (first < 0) {
            return;
        }
        BlockState track = AllBlocks.TRACK.getDefaultState();
        String entityId = BlockEntityType.getKey(AllBlockEntityTypes.TRACK.get()).toString();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        ChunkAccess access = level.getChunk(chunk.x, chunk.z);

        for (int i = first; i < layout.count() && layout.zSouth(i) >= minZ; i++) {
            if (layout.type(i) == RailLayout.STRAIGHT) {
                int x = layout.xSouth(i);
                if (x < chunk.getMinBlockX() || x > chunk.getMaxBlockX()) {
                    continue;
                }
                int y = layout.ySouth(i);
                for (int z = Math.min(layout.zSouth(i), maxZ); z >= Math.max(layout.zNorth(i), minZ); z--) {
                    level.setBlock(pos.set(x, y, z), track, 2);
                    // WorldGenRegion leaves a DUMMY block entity for every track block (TrackBlock is an
                    // EntityBlock); plain track has none, so on load vanilla warns and wastes a lookup per block
                    access.removeBlockEntity(pos);
                }
                continue;
            }
            PieceGeometry.Geometry geometry = PieceGeometry.of(layout, i);
            for (PieceGeometry.TrackBlock block : geometry.blocks()) {
                if (inChunk(chunk, block.pos())) {
                    level.setBlock(block.pos(), track.setValue(TrackBlock.SHAPE, block.shape())
                            .setValue(TrackBlock.HAS_BE, block.hasBlockEntity()), 2);
                    if (!block.hasBlockEntity()) {
                        access.removeBlockEntity(block.pos());
                    }
                }
            }
            for (PieceGeometry.BlockEntity entity : geometry.entities()) {
                if (inChunk(chunk, entity.pos())) {
                    level.getChunk(entity.pos()).setBlockEntityNbt(nbt(entityId, entity));
                }
            }
        }
    }

    private static CompoundTag nbt(String entityId, PieceGeometry.BlockEntity entity) {
        BlockPos pos = entity.pos();
        CompoundTag tag = new CompoundTag();
        tag.putString("id", entityId);
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        ListTag connections = new ListTag();
        for (BezierConnection connection : entity.connections()) {
            connections.add(connection.write(pos));
        }
        tag.put("Connections", connections);
        if (!Double.isNaN(entity.tilt())) {
            tag.putDouble("Smoothing", entity.tilt());
        }
        return tag;
    }

    private static boolean inChunk(ChunkPos chunk, BlockPos pos) {
        return pos.getX() >= chunk.getMinBlockX() && pos.getX() <= chunk.getMaxBlockX()
                && pos.getZ() >= chunk.getMinBlockZ() && pos.getZ() <= chunk.getMaxBlockZ();
    }
}
