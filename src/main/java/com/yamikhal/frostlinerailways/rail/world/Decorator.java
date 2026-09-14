package com.yamikhal.frostlinerailways.rail.world;

import com.yamikhal.frostlinerailways.rail.decor.BiomeFilter;
import com.yamikhal.frostlinerailways.rail.decor.RailAddition;
import com.yamikhal.frostlinerailways.rail.decor.RailContext;
import com.yamikhal.frostlinerailways.rail.decor.RailDecorData;
import com.yamikhal.frostlinerailways.rail.decor.RailStation;
import com.yamikhal.frostlinerailways.rail.decor.StationPlanner;
import com.yamikhal.frostlinerailways.rail.layout.RailLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Places the line's additions, termini and stations in one chunk (RAILWAYS.md §A8.5).
 *
 * Every decision — anchor rows, biome, chance, station sites — comes from {@link RailContext} and
 * {@link StationPlanner}, i.e. layout, seed and noise, so an addition that straddles a chunk border is
 * decided identically by both chunks and each writes only its own blocks.
 */
public final class Decorator {

    /** How far along z an addition's blocks may reach from its anchor. */
    static final int REACH = 16;
    private static final ResourceLocation STATION_BLOCK = new ResourceLocation("create", "track_station");

    private record Keyed(String id, long salt, RailAddition addition) {
    }

    private Decorator() {
    }

    static void decorate(WorldGenLevel level, ChunkPos chunk, RailContext ctx) {
        RailLayout layout = ctx.layout;
        List<Keyed> global = new ArrayList<>();
        for (RailDecorData.Entry<RailAddition> entry : RailDecorData.ADDITIONS.entries()) {
            global.add(new Keyed(entry.id().toString(), entry.id().toString().hashCode(), entry.value()));
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        int fromZ = Math.min(chunk.getMaxBlockZ() + REACH, layout.zSouthEnd());
        int toZ = Math.max(chunk.getMinBlockZ() - REACH, layout.zNorthEnd());
        for (int z = fromZ; z >= toZ; z--) {
            RailContext.Row row = ctx.row(z);
            List<RailAddition> styleAdditions = row.style().value().additions();
            for (int i = 0; i < styleAdditions.size(); i++) {
                String id = row.style().id() + "#" + i;
                slot(level, chunk, ctx, row, new Keyed(id, id.hashCode(), styleAdditions.get(i)), pos);
            }
            for (Keyed keyed : global) {
                slot(level, chunk, ctx, row, keyed, pos);
            }
        }

        ends(level, chunk, ctx, global, pos);

        for (StationPlanner.Site site : StationPlanner.sites(ctx)) {
            if (site.zSouth() + REACH >= chunk.getMinBlockZ() && site.zNorth() - REACH <= chunk.getMaxBlockZ()) {
                station(level, chunk, ctx, site, pos);
            }
        }
    }

    // --- slotted additions and scatter ---------------------------------------------------------

    private static void slot(WorldGenLevel level, ChunkPos chunk, RailContext ctx, RailContext.Row row, Keyed keyed,
                             BlockPos.MutableBlockPos pos) {
        RailAddition a = keyed.addition();
        if (a.isEnd() || !matchesRow(ctx, a, row)) {
            return;
        }
        if (a.scatter().isPresent()) {
            if (row.z() >= chunk.getMinBlockZ() && row.z() <= chunk.getMaxBlockZ() && biomeAllows(ctx, a, row)) {
                scatter(level, chunk, ctx, row, keyed, pos);
            }
        }
        if (a.blocks().isEmpty() || Math.floorMod(row.z() - a.phase(), a.spacing()) != 0 || !biomeAllows(ctx, a, row)) {
            return;
        }
        for (int sign : a.signs()) {
            if (ctx.random(keyed.salt(), row.z(), sign) < a.chance()) {
                placeBlocks(level, chunk, a, row.trackX() + ("center".equals(a.side()) ? 0 : sign * a.offset()), row.bedY(), row.z(),
                        "center".equals(a.side()) ? 1 : sign, pos);
            }
        }
    }

    private static boolean matchesRow(RailContext ctx, RailAddition a, RailContext.Row row) {
        if (!a.where().isEmpty()) {
            String kind = row.kind().name().toLowerCase();
            if (!a.where().contains(kind) && !(row.portal() && a.where().contains("portal"))) {
                return false;
            }
        }
        String piece = switch (ctx.layout.type(row.piece())) {
            case RailLayout.BEND -> "bend";
            case RailLayout.RAMP -> "ramp";
            case RailLayout.SHIFT -> "shift";
            default -> "straight";
        };
        return a.pieces().contains(piece);
    }

    private static boolean biomeAllows(RailContext ctx, RailAddition a, RailContext.Row row) {
        return (a.biomes().isEmpty() && a.excludeBiomes().isEmpty())
                || BiomeFilter.allows(a.biomes(), a.excludeBiomes(), ctx.biome(row.trackX(), row.bedY(), row.z()));
    }

    private static void scatter(WorldGenLevel level, ChunkPos chunk, RailContext ctx, RailContext.Row row, Keyed keyed,
                                BlockPos.MutableBlockPos pos) {
        RailAddition.Scatter scatter = keyed.addition().scatter().get();
        int total = scatter.states().stream().mapToInt(RailAddition.WeightedState::weight).sum();
        for (int dx = -scatter.reach(); dx <= scatter.reach(); dx++) {
            int x = row.trackX() + dx;
            if (dx == 0 || x < chunk.getMinBlockX() || x > chunk.getMaxBlockX()) {
                continue;
            }
            if (ctx.random(keyed.salt(), x, row.z()) >= scatter.chance()) {
                continue;
            }
            pos.set(x, row.bedY(), row.z());
            if (!level.getBlockState(pos).isAir()) {
                continue;
            }
            double roll = ctx.random(keyed.salt() ^ 0x5CA7L, x, row.z()) * total;
            for (RailAddition.WeightedState weighted : scatter.states()) {
                roll -= weighted.weight();
                if (roll < 0) {
                    level.setBlock(pos, weighted.state(), 2);
                    break;
                }
            }
        }
    }

    /**
     * Blocks of an addition anchored at (anchorX, track height, anchorZ). sign +1 is east: outward
     * goes +x; -1 is west: outward goes -x and every state is mirrored.
     */
    private static void placeBlocks(WorldGenLevel level, ChunkPos chunk, RailAddition a, int anchorX, int trackY, int anchorZ,
                                    int sign, BlockPos.MutableBlockPos pos) {
        boolean any = "any".equals(a.replace());
        for (RailAddition.Placement p : a.blocks()) {
            int x = anchorX + sign * p.outward();
            int z = anchorZ - p.forward();
            if (x < chunk.getMinBlockX() || x > chunk.getMaxBlockX() || z < chunk.getMinBlockZ() || z > chunk.getMaxBlockZ()) {
                continue;
            }
            pos.set(x, trackY + a.height() + p.up(), z);
            BlockState existing = level.getBlockState(pos);
            if (!any && !existing.isAir() && !existing.canBeReplaced()) {
                continue;
            }
            level.setBlock(pos, sign < 0 ? p.state().mirror(Mirror.FRONT_BACK) : p.state(), 2);
        }
    }

    // --- termini -------------------------------------------------------------------------------

    private static void ends(WorldGenLevel level, ChunkPos chunk, RailContext ctx, List<Keyed> global, BlockPos.MutableBlockPos pos) {
        RailLayout layout = ctx.layout;
        endAt(level, chunk, ctx, global, "south_end", layout.zSouthEnd(), -1, pos);
        endAt(level, chunk, ctx, global, "north_end", layout.zNorthEnd(), 1, pos);
    }

    /** {@code outwardZ}: forward (+) in an end addition points away from the line, south past the south end, north past the north end. */
    private static void endAt(WorldGenLevel level, ChunkPos chunk, RailContext ctx, List<Keyed> global, String where, int z,
                              int outwardZ, BlockPos.MutableBlockPos pos) {
        if (z + REACH < chunk.getMinBlockZ() || z - REACH > chunk.getMaxBlockZ()) {
            return;
        }
        RailContext.Row row = ctx.row(z);
        List<Keyed> candidates = new ArrayList<>(global);
        List<RailAddition> styleAdditions = row.style().value().additions();
        for (int i = 0; i < styleAdditions.size(); i++) {
            String id = row.style().id() + "#" + i;
            candidates.add(new Keyed(id, id.hashCode(), styleAdditions.get(i)));
        }
        for (Keyed keyed : candidates) {
            RailAddition a = keyed.addition();
            if (!a.where().contains(where) || !biomeAllows(ctx, a, row) || ctx.random(keyed.salt(), z, 7) >= a.chance()) {
                continue;
            }
            // mirror forward for the south end so "forward" always means past the end of the track
            RailAddition oriented = outwardZ > 0 ? a : flipForward(a);
            for (int sign : a.signs()) {
                placeBlocks(level, chunk, oriented, row.trackX() + ("center".equals(a.side()) ? 0 : sign * a.offset()), row.bedY(), z,
                        "center".equals(a.side()) ? 1 : sign, pos);
            }
        }
    }

    private static RailAddition flipForward(RailAddition a) {
        List<RailAddition.Placement> flipped = new ArrayList<>();
        for (RailAddition.Placement p : a.blocks()) {
            flipped.add(new RailAddition.Placement(List.of(p.outward(), p.up(), -p.forward()), p.state().mirror(Mirror.LEFT_RIGHT)));
        }
        return new RailAddition(a.biomes(), a.excludeBiomes(), a.where(), a.pieces(), a.spacing(), a.phase(), a.chance(),
                a.side(), a.offset(), a.height(), flipped, a.replace(), a.scatter());
    }

    // --- stations ------------------------------------------------------------------------------

    private static void station(WorldGenLevel level, ChunkPos chunk, RailContext ctx, StationPlanner.Site site, BlockPos.MutableBlockPos pos) {
        RailStation def = site.def();
        int y = site.bedY();
        for (int z = site.zSouth(); z >= site.zNorth(); z--) {
            if (z < chunk.getMinBlockZ() || z > chunk.getMaxBlockZ()) {
                continue;
            }
            for (int w = 0; w < def.width(); w++) {
                int x = site.trackX() + site.sign() * (def.gap() + w);
                if (x < chunk.getMinBlockX() || x > chunk.getMaxBlockX()) {
                    continue;
                }
                BlockState surface = w == 0 && def.edge().isPresent() ? def.edge().get() : def.platform();
                level.setBlock(pos.set(x, y, z), surface, 2);
                for (int dy = 1; dy <= def.clearance(); dy++) {
                    if (!level.getBlockState(pos.set(x, y + dy, z)).isAir()) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }

        int stationX = site.trackX() + site.sign() * def.gap();
        if (def.stationBlock() && stationX >= chunk.getMinBlockX() && stationX <= chunk.getMaxBlockX()
                && site.zCentre() >= chunk.getMinBlockZ() && site.zCentre() <= chunk.getMaxBlockZ()) {
            Block block = BuiltInRegistries.BLOCK.get(STATION_BLOCK);
            if (block != Blocks.AIR) {
                BlockPos stationPos = new BlockPos(stationX, y, site.zCentre());
                level.setBlock(stationPos, block.defaultBlockState(), 2);
                CompoundTag tag = new CompoundTag();
                tag.putString("id", STATION_BLOCK.toString());
                tag.putInt("x", stationPos.getX());
                tag.putInt("y", stationPos.getY());
                tag.putInt("z", stationPos.getZ());
                long bits = (long) (ctx.random(site.id().hashCode(), site.zCentre(), 11) * Long.MAX_VALUE);
                tag.putUUID("Id", new UUID(bits, site.zCentre() ^ ((long) site.trackX() << 32)));
                tag.put("TargetTrack", NbtUtils.writeBlockPos(new BlockPos(site.trackX() - stationX, 0, 0)));
                tag.putBoolean("TargetDirection", "south".equals(def.direction()));
                tag.putBoolean("Ortho", false);
                level.getChunk(stationPos).setBlockEntityNbt(tag);
            }
        }

        RailContext.Row row = ctx.row(site.zCentre());
        for (int i = 0; i < def.additions().size(); i++) {
            RailAddition a = def.additions().get(i);
            String id = site.id() + "#" + i;
            if (ctx.random(id.hashCode(), site.zCentre(), 3) < a.chance()) {
                placeBlocks(level, chunk, a, site.trackX() + site.sign() * a.offset(), row.bedY(), site.zCentre(), site.sign(), pos);
            }
        }
    }
}
