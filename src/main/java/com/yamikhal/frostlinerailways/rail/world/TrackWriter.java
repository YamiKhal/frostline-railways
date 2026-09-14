package com.yamikhal.frostlinerailways.rail.world;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.decoration.girder.GirderBlock;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackShape;
import com.yamikhal.frostlinerailways.RailwaysConfig;
import com.yamikhal.frostlinerailways.rail.create.PieceGeometry;
import com.yamikhal.frostlinerailways.rail.layout.RailLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Writes Create track for one chunk during world generation (RAILWAYS.md §A3.2, §A8.4, §A8.14).
 *
 * Straights are plain z-axis track blockstates. Every other piece comes from {@link PieceGeometry}:
 * its blocks (z-axis or diagonal shapes, HAS_BE on curve ends) and its block entities, written as NBT
 * into the proto chunk (ChunkAccess#setBlockEntityNbt) with their curves and, on smoothed ramp blocks,
 * Create's tilt. Vanilla promotes that NBT on load like track read from disk. No onPlace, no
 * scheduled tick, no TrackPropagator. Only blocks inside the chunk are written.
 *
 * girderTracks: the metal girder variant, as Create builds it when track is placed with metal girders in the
 * offhand (TrackPaver): slopes get the Girder flag (Create renders their supports), their track blocks — and
 * with girderSlopesOnly off every straight block too — a metal girder under each rail, one block below on both
 * sides along axis × up. Never on S-bends and diagonal shifts (broken there). Unlike the player's pavement,
 * these replace the bed below.
 */
public final class TrackWriter {

    private TrackWriter() {
    }

    static void write(WorldGenLevel level, ChunkPos chunk, RailLayout layout) {
        int minZ = chunk.getMinBlockZ();
        int maxZ = chunk.getMaxBlockZ();
        // one row further south: a diagonal girder may reach one block past its track block
        int first = layout.pieceAt(Math.min(maxZ + 1, layout.zSouthEnd()));
        if (first < 0) {
            return;
        }
        boolean girderRamps = RailwaysConfig.girderTracks();
        boolean girderStraights = girderRamps && !RailwaysConfig.girderSlopesOnly();
        BlockState track = AllBlocks.TRACK.getDefaultState();
        BlockState zGirder = AllBlocks.METAL_GIRDER.getDefaultState()
                .setValue(GirderBlock.TOP, false).setValue(GirderBlock.BOTTOM, false)
                .setValue(GirderBlock.AXIS, Direction.Axis.Z).setValue(GirderBlock.Z, true);
        BlockState diagonalGirder = AllBlocks.METAL_GIRDER.getDefaultState();
        String entityId = BlockEntityType.getKey(AllBlockEntityTypes.TRACK.get()).toString();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        ChunkAccess access = level.getChunk(chunk.x, chunk.z);

        for (int i = first; i < layout.count() && layout.zSouth(i) >= minZ - 1; i++) {
            if (layout.type(i) == RailLayout.STRAIGHT) {
                int x = layout.xSouth(i);
                if (x < chunk.getMinBlockX() - 1 || x > chunk.getMaxBlockX() + 1) {
                    continue;
                }
                int y = layout.ySouth(i);
                for (int z = Math.min(layout.zSouth(i), maxZ); z >= Math.max(layout.zNorth(i), minZ); z--) {
                    if (x >= chunk.getMinBlockX() && x <= chunk.getMaxBlockX()) {
                        level.setBlock(pos.set(x, y, z), track, 2);
                        // WorldGenRegion leaves a DUMMY block entity for every track block (TrackBlock is an
                        // EntityBlock); plain track has none, so on load vanilla warns and wastes a lookup per block
                        access.removeBlockEntity(pos);
                    }
                    if (girderStraights) {
                        girder(level, chunk, pos, x - 1, y - 1, z, zGirder);
                        girder(level, chunk, pos, x + 1, y - 1, z, zGirder);
                    }
                }
                continue;
            }
            PieceGeometry.Geometry geometry = PieceGeometry.of(layout, i);
            // girders only on ramps: Create's girder supports render broken on S-bends and diagonal shifts
            boolean girders = girderRamps && layout.type(i) == RailLayout.RAMP;
            for (PieceGeometry.TrackBlock block : geometry.blocks()) {
                BlockPos p = block.pos();
                if (inChunk(chunk, p)) {
                    level.setBlock(p, track.setValue(TrackBlock.SHAPE, block.shape())
                            .setValue(TrackBlock.HAS_BE, block.hasBlockEntity()), 2);
                    if (!block.hasBlockEntity()) {
                        access.removeBlockEntity(p);
                    }
                }
                if (girders) {
                    // axis × up: z-axis track (0,0,1) → (±1, 0, 0); diagonal (a, 0, 1) → (-1, 0, a) and (1, 0, -a)
                    if (block.shape() == TrackShape.ZO) {
                        girder(level, chunk, pos, p.getX() - 1, p.getY() - 1, p.getZ(), zGirder);
                        girder(level, chunk, pos, p.getX() + 1, p.getY() - 1, p.getZ(), zGirder);
                    } else {
                        int a = block.shape() == TrackShape.ND ? -1 : 1;
                        girder(level, chunk, pos, p.getX() - 1, p.getY() - 1, p.getZ() + a, diagonalGirder);
                        girder(level, chunk, pos, p.getX() + 1, p.getY() - 1, p.getZ() - a, diagonalGirder);
                    }
                }
            }
            for (PieceGeometry.BlockEntity entity : geometry.entities()) {
                if (inChunk(chunk, entity.pos())) {
                    level.getChunk(entity.pos()).setBlockEntityNbt(nbt(entityId, entity, girders));
                }
            }
        }
    }

    /** A girder at (x, y, z) if that is in this chunk and not track. */
    private static void girder(WorldGenLevel level, ChunkPos chunk, BlockPos.MutableBlockPos pos, int x, int y, int z, BlockState state) {
        if (x < chunk.getMinBlockX() || x > chunk.getMaxBlockX() || z < chunk.getMinBlockZ() || z > chunk.getMaxBlockZ()) {
            return;
        }
        pos.set(x, y, z);
        if (!level.getBlockState(pos).is(AllBlocks.TRACK.get())) {
            level.setBlock(pos, state, 2);
        }
    }

    private static CompoundTag nbt(String entityId, PieceGeometry.BlockEntity entity, boolean girders) {
        BlockPos pos = entity.pos();
        CompoundTag tag = new CompoundTag();
        tag.putString("id", entityId);
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        ListTag connections = new ListTag();
        for (BezierConnection connection : entity.connections()) {
            CompoundTag curve = connection.write(pos);
            // Create renders girder supports under a curve whose Girder flag is set
            curve.putBoolean("Girder", girders);
            connections.add(curve);
        }
        tag.put("Connections", connections);
        if (!Double.isNaN(entity.tilt())) {
            tag.putDouble("Smoothing", entity.tilt());
        }
        return tag;
    }

    /** True if (x, y, z) holds a girder this writer placed under a rail (templates leave those alone). */
    static boolean isGirderColumn(WorldGenLevel level, BlockPos.MutableBlockPos pos, int x, int y, int z, int trackX, int bedY) {
        return y == bedY - 1 && Math.abs(x - trackX) == 1 && level.getBlockState(pos.set(x, y, z)).is(AllBlocks.METAL_GIRDER.get());
    }

    private static boolean inChunk(ChunkPos chunk, BlockPos pos) {
        return pos.getX() >= chunk.getMinBlockX() && pos.getX() <= chunk.getMaxBlockX()
                && pos.getZ() >= chunk.getMinBlockZ() && pos.getZ() <= chunk.getMaxBlockZ();
    }
}
