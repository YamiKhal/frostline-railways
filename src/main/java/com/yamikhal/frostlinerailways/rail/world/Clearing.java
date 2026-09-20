package com.yamikhal.frostlinerailways.rail.world;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.function.IntPredicate;

/**
 * Every block {@link BedBaker} removes goes through here, so nothing is left half removed (RAILWAYS.md §A8.16):
 *
 *   trees     a log or leaf in the way takes its whole tree: the logs connected to it (26 neighbours; more than
 *             MAX_LOGS is a building, not a tree, and only the block itself goes) and the leaves those logs hold —
 *             reached from them through leaves within LEAF_REACH steps, and only where the leaf's own distance says
 *             no other tree's log is nearer, so a canopy touching the next tree's does not take that tree. A leaf
 *             first finds its tree (the nearest log through leaves); leaves with no log (bushes) go as one blob of up
 *             to MAX_BLOB.
 *   attached  at the end, blocks next to anything removed that can no longer stand go too, cascading: snow layers
 *             left in the air, vines, icicles, the top half of tall plants.
 *
 * Reads and writes stay one block inside the feature's 3x3 chunk region (WorldGenRegion only allows neighbour
 * chunks), so the rare tree that reaches past it keeps that part. Rows near a station are never touched: its moss
 * and leaves are not trees.
 */
final class Clearing {

    private static final int MAX_LOGS = 512;
    private static final int MAX_LEAVES = 8192;
    private static final int MAX_BLOB = 64;
    private static final int LEAF_REACH = 6;
    private static final int SETTLE_LIMIT = 16384;
    private static final Direction[] DIRECTIONS = Direction.values();

    private final WorldGenLevel level;
    private final int minX;
    private final int maxX;
    private final int minZ;
    private final int maxZ;
    private final int minY;
    private final int maxY;
    private final IntPredicate protectedZ;
    private final LongArrayList removed = new LongArrayList();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    Clearing(WorldGenLevel level, ChunkPos chunk, IntPredicate protectedZ) {
        this.level = level;
        this.minX = chunk.getMinBlockX() - 15;
        this.maxX = chunk.getMaxBlockX() + 15;
        this.minZ = chunk.getMinBlockZ() - 15;
        this.maxZ = chunk.getMaxBlockZ() + 15;
        this.minY = level.getMinBuildHeight() + 1;
        this.maxY = level.getMaxBuildHeight() - 2;
        this.protectedZ = protectedZ;
    }

    /** Air at (x, y, z); a log or leaf takes its whole tree with it. */
    void remove(int x, int y, int z) {
        BlockState state = level.getBlockState(cursor.set(x, y, z));
        if (state.isAir()) {
            return;
        }
        if ((isLog(state) || isLeaf(state)) && usable(x, y, z)) {
            fell(BlockPos.asLong(x, y, z), state);
            if (level.getBlockState(cursor.set(x, y, z)).isAir()) {
                return;
            }
        }
        air(x, y, z);
    }

    /** Removes what the removals left unable to stand, cascading. Call once, after the last remove. */
    void settle() {
        for (int i = 0; i < removed.size() && i < SETTLE_LIMIT; i++) {
            long at = removed.getLong(i);
            int x = BlockPos.getX(at);
            int y = BlockPos.getY(at);
            int z = BlockPos.getZ(at);
            for (Direction d : DIRECTIONS) {
                int nx = x + d.getStepX();
                int ny = y + d.getStepY();
                int nz = z + d.getStepZ();
                if (!usable(nx, ny, nz)) {
                    continue;
                }
                BlockState state = level.getBlockState(cursor.set(nx, ny, nz));
                if (!state.isAir() && fallen(state, nx, ny, nz)) {
                    air(nx, ny, nz);
                }
            }
        }
    }

    /**
     * Whether a block lost what held it. Snow layers only when the block under them is gone: vanilla's canSurvive
     * also refuses packed ice, which worldgen snow happily lies on.
     */
    private boolean fallen(BlockState state, int x, int y, int z) {
        if (state.is(Blocks.SNOW)) {
            return level.getBlockState(cursor.set(x, y - 1, z)).canBeReplaced();
        }
        return !state.canSurvive(level, cursor.set(x, y, z));
    }

    private void fell(long start, BlockState state) {
        long root = start;
        if (isLeaf(state)) {
            root = nearestLog(start);
            if (root == Long.MIN_VALUE) {
                blob(start);
                return;
            }
        }
        LongOpenHashSet logs = logs(root);
        if (logs == null) {
            return;
        }
        LongArrayList leaves = canopy(logs);
        for (long p : logs) {
            air(p);
        }
        for (int i = 0; i < leaves.size(); i++) {
            air(leaves.getLong(i));
        }
    }

    /** The logs connected to start through logs (26 neighbours); null past MAX_LOGS. */
    private LongOpenHashSet logs(long start) {
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayList queue = new LongArrayList();
        seen.add(start);
        queue.add(start);
        for (int i = 0; i < queue.size(); i++) {
            long at = queue.getLong(i);
            int x = BlockPos.getX(at);
            int y = BlockPos.getY(at);
            int z = BlockPos.getZ(at);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int nx = x + dx;
                        int ny = y + dy;
                        int nz = z + dz;
                        long n = BlockPos.asLong(nx, ny, nz);
                        if (!usable(nx, ny, nz) || seen.contains(n) || !isLog(level.getBlockState(cursor.set(nx, ny, nz)))) {
                            continue;
                        }
                        if (seen.size() >= MAX_LOGS) {
                            return null;
                        }
                        seen.add(n);
                        queue.add(n);
                    }
                }
            }
        }
        return seen;
    }

    /** The leaves the logs hold: through leaves, up to LEAF_REACH steps, not nearer to another tree's log. */
    private LongArrayList canopy(LongOpenHashSet logs) {
        LongOpenHashSet seen = new LongOpenHashSet(logs);
        LongArrayList queue = new LongArrayList(logs);
        LongArrayList leaves = new LongArrayList();
        int layerEnd = queue.size();
        int depth = 1;
        for (int i = 0; i < queue.size() && depth <= LEAF_REACH && leaves.size() < MAX_LEAVES; i++) {
            if (i == layerEnd) {
                layerEnd = queue.size();
                depth++;
                if (depth > LEAF_REACH) {
                    break;
                }
            }
            long at = queue.getLong(i);
            for (Direction d : DIRECTIONS) {
                int nx = BlockPos.getX(at) + d.getStepX();
                int ny = BlockPos.getY(at) + d.getStepY();
                int nz = BlockPos.getZ(at) + d.getStepZ();
                long n = BlockPos.asLong(nx, ny, nz);
                if (!usable(nx, ny, nz) || seen.contains(n)) {
                    continue;
                }
                BlockState state = level.getBlockState(cursor.set(nx, ny, nz));
                if (!isLeaf(state) || !owned(state, depth)) {
                    continue;
                }
                seen.add(n);
                queue.add(n);
                leaves.add(n);
            }
        }
        return leaves;
    }

    /**
     * A leaf at depth steps from this tree belongs to it unless its distance (vanilla's, set by the tree feature) says
     * a log is nearer — that log is another tree's. Persistent leaves (templates) carry no usable distance.
     */
    private static boolean owned(BlockState state, int depth) {
        if (!state.hasProperty(BlockStateProperties.DISTANCE)) {
            return true;
        }
        if (state.hasProperty(BlockStateProperties.PERSISTENT) && state.getValue(BlockStateProperties.PERSISTENT)) {
            return true;
        }
        return state.getValue(BlockStateProperties.DISTANCE) >= depth;
    }

    /** The log nearest to a leaf through leaves, or Long.MIN_VALUE. */
    private long nearestLog(long start) {
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayList queue = new LongArrayList();
        seen.add(start);
        queue.add(start);
        int layerEnd = 1;
        int depth = 0;
        for (int i = 0; i < queue.size(); i++) {
            if (i == layerEnd) {
                layerEnd = queue.size();
                if (++depth > LEAF_REACH) {
                    break;
                }
            }
            long at = queue.getLong(i);
            for (Direction d : DIRECTIONS) {
                int nx = BlockPos.getX(at) + d.getStepX();
                int ny = BlockPos.getY(at) + d.getStepY();
                int nz = BlockPos.getZ(at) + d.getStepZ();
                long n = BlockPos.asLong(nx, ny, nz);
                if (!usable(nx, ny, nz) || !seen.add(n)) {
                    continue;
                }
                BlockState state = level.getBlockState(cursor.set(nx, ny, nz));
                if (isLog(state)) {
                    return n;
                }
                if (isLeaf(state)) {
                    queue.add(n);
                }
            }
        }
        return Long.MIN_VALUE;
    }

    /** Leaves with no log: the connected blob if it is small (a bush), else nothing (the caller takes the block). */
    private void blob(long start) {
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayList queue = new LongArrayList();
        seen.add(start);
        queue.add(start);
        for (int i = 0; i < queue.size(); i++) {
            long at = queue.getLong(i);
            for (Direction d : DIRECTIONS) {
                int nx = BlockPos.getX(at) + d.getStepX();
                int ny = BlockPos.getY(at) + d.getStepY();
                int nz = BlockPos.getZ(at) + d.getStepZ();
                long n = BlockPos.asLong(nx, ny, nz);
                if (!usable(nx, ny, nz) || seen.contains(n) || !isLeaf(level.getBlockState(cursor.set(nx, ny, nz)))) {
                    continue;
                }
                if (seen.size() >= MAX_BLOB) {
                    return;
                }
                seen.add(n);
                queue.add(n);
            }
        }
        for (int i = 0; i < queue.size(); i++) {
            air(queue.getLong(i));
        }
    }

    private boolean usable(int x, int y, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ && y >= minY && y <= maxY && !protectedZ.test(z);
    }

    private void air(long p) {
        air(BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p));
    }

    private void air(int x, int y, int z) {
        cursor.set(x, y, z);
        if (level.getBlockState(cursor).isAir()) {
            return;
        }
        level.setBlock(cursor, Blocks.AIR.defaultBlockState(), 2);
        removed.add(BlockPos.asLong(x, y, z));
    }

    private static boolean isLog(BlockState state) {
        return state.is(BlockTags.LOGS);
    }

    private static boolean isLeaf(BlockState state) {
        return state.is(BlockTags.LEAVES);
    }
}
