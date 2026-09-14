package com.yamikhal.frostlinerailways.rail.world;

import com.yamikhal.frostlinerailways.RailwaysConfig;
import com.yamikhal.frostlinerailways.rail.decor.BiomeFilter;
import com.yamikhal.frostlinerailways.rail.decor.Envelope;
import com.yamikhal.frostlinerailways.rail.decor.RailContext;
import com.yamikhal.frostlinerailways.rail.decor.RailStyle;
import com.yamikhal.frostlinerailways.rail.decor.RailTemplates;
import com.yamikhal.frostlinerailways.rail.decor.StationPlanner;
import com.yamikhal.frostlinerailways.rail.decor.StructurePlanner;
import com.yamikhal.frostlinerailways.rail.layout.RailLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * The styles' cover_layer (RAILWAYS.md §A8.10), last thing the line does in a chunk: on every column within the
 * layer's reach of the track, the top block — ballast, berm, cut slope, bank, even inside the train's space —
 * gets the layer block on top if it is solid on top, has air above, and the layer block can stand there.
 *
 * Skipped: tunnel rows, stations (with 8 blocks margin), bridge decks and railings, the footprint of structures
 * (tiles and per-row tops), and unless inside_train_space the space trains drive through. Configured per style (state, chance, reach, biomes), switched off globally with coverLayer.
 */
final class CoverLayer {

    private static final long SALT = 0xC0FE_1A7EL;
    private static final int STATION_MARGIN = 8;
    private static final int SCAN_BELOW = 24;

    private CoverLayer() {
    }

    static void apply(WorldGenLevel level, ChunkPos chunk, RailContext ctx) {
        if (!RailwaysConfig.coverLayer()) {
            return;
        }
        RailLayout layout = ctx.layout;
        List<StationPlanner.Site> sites = StationPlanner.sites(ctx);
        StructurePlanner.Plan plan = StructurePlanner.plan(ctx, level.getServer());
        List<StructurePlanner.Tile> tiles = plan.touching(chunk.getMinBlockZ(), chunk.getMaxBlockZ());
        List<StructurePlanner.SliceRun> slices = plan.slicesTouching(chunk.getMinBlockZ(), chunk.getMaxBlockZ());
        Envelope envelope = Envelope.of(ctx);
        int deckHalf = Math.max(ctx.def.bed().halfWidth(), RailwaysConfig.trainHalfWidth() + 1) + 1;
        int scanAbove = RailwaysConfig.clearHeight() + 8;
        int maxY = level.getMaxBuildHeight() - 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        int fromZ = Math.min(chunk.getMaxBlockZ(), layout.zSouthEnd());
        int toZ = Math.max(chunk.getMinBlockZ(), layout.zNorthEnd());
        rows:
        for (int z = fromZ; z >= toZ; z--) {
            RailContext.Row row = ctx.row(z);
            RailStyle.CoverLayer layer = row.style().value().coverLayer().orElse(null);
            if (layer == null || row.kind() == RailContext.Kind.TUNNEL) {
                continue;
            }
            for (StationPlanner.Site site : sites) {
                if (z >= site.zNorth() - STATION_MARGIN && z <= site.zSouth() + STATION_MARGIN) {
                    continue rows;
                }
            }
            if ((!layer.biomes().isEmpty() || !layer.excludeBiomes().isEmpty())
                    && !BiomeFilter.allows(layer.biomes(), layer.excludeBiomes(), ctx.biome(row.trackX(), row.bedY(), z))) {
                continue;
            }
            boolean bridge = row.kind() == RailContext.Kind.BRIDGE;
            int sliceHalf = sliceHalfWidth(level, slices, z);
            for (int dx = -layer.reach(); dx <= layer.reach(); dx++) {
                int x = row.trackX() + dx;
                if (x < chunk.getMinBlockX() || x > chunk.getMaxBlockX() || (bridge && Math.abs(dx) <= deckHalf)
                        || Math.abs(dx) <= sliceHalf || underTile(level, tiles, x, z) || ctx.random(SALT, x, z) >= layer.chance()) {
                    continue;
                }
                int top = Math.min(maxY, row.bedY() + scanAbove);
                int bottom = Math.max(level.getMinBuildHeight(), row.bedY() - SCAN_BELOW);
                int y = top;
                while (y > bottom && level.getBlockState(pos.set(x, y, z)).isAir()) {
                    y--;
                }
                if (y <= bottom || y >= top) {
                    continue;
                }
                BlockState below = level.getBlockState(pos.set(x, y, z));
                if (below.is(layer.state().getBlock()) || !below.getFluidState().isEmpty() || !below.isFaceSturdy(level, pos, Direction.UP)) {
                    continue;
                }
                if (!layer.insideTrainSpace() && envelope.contains(x, y + 1, z)) {
                    continue;
                }
                pos.set(x, y + 1, z);
                if (layer.state().canSurvive(level, pos)) {
                    level.setBlock(pos, layer.state(), 2);
                }
            }
        }
    }

    /** Half width of the widest per-row template on row z, or -1 if none. */
    private static int sliceHalfWidth(WorldGenLevel level, List<StructurePlanner.SliceRun> slices, int z) {
        int half = -1;
        for (StructurePlanner.SliceRun run : slices) {
            if (z < run.zMin() || z > run.zMax()) {
                continue;
            }
            for (ResourceLocation id : List.of(run.top(), run.topCurve())) {
                RailTemplates.Structure structure = RailTemplates.structure(level.getServer(), id);
                if (structure != null) {
                    half = Math.max(half, structure.sizeZ() / 2);
                }
            }
        }
        return half;
    }

    private static boolean underTile(WorldGenLevel level, List<StructurePlanner.Tile> tiles, int x, int z) {
        for (StructurePlanner.Tile tile : tiles) {
            if (z < tile.zMin() || z > tile.zMax()) {
                continue;
            }
            RailTemplates.Structure structure = RailTemplates.structure(level.getServer(), tile.template());
            if (structure == null) {
                continue;
            }
            // placed with sign +1: template z = trackZ - (x - trackX)
            int tz = tile.trackZ() - (x - tile.trackX());
            if (tz >= 0 && tz < structure.sizeZ()) {
                return true;
            }
        }
        return false;
    }
}
