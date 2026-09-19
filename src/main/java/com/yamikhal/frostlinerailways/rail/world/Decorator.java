package com.yamikhal.frostlinerailways.rail.world;

import com.yamikhal.frostlinerailways.rail.decor.BiomeFilter;
import com.yamikhal.frostlinerailways.rail.decor.Envelope;
import com.yamikhal.frostlinerailways.rail.decor.RailAddition;
import com.yamikhal.frostlinerailways.rail.decor.RailContext;
import com.yamikhal.frostlinerailways.rail.decor.RailDecorData;
import com.yamikhal.frostlinerailways.rail.decor.RailStation;
import com.yamikhal.frostlinerailways.rail.decor.RailTemplates;
import com.yamikhal.frostlinerailways.rail.decor.StationPlanner;
import com.yamikhal.frostlinerailways.rail.decor.StructurePlanner;
import com.yamikhal.frostlinerailways.rail.layout.RailLayout;
import com.yamikhal.frostlinerailways.rail.sites.RailSites;
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
 * Places the line's additions, termini, tiled structures and stations in one chunk (RAILWAYS.md §A8.5,
 * §A8.8, §A8.9).
 *
 * Every decision — anchor rows, biome, chance, station sites, tiles — comes from {@link RailContext},
 * {@link StationPlanner} and {@link StructurePlanner}, i.e. layout, seed and noise, so anything that
 * straddles a chunk border is decided identically by both chunks and each writes only its own blocks.
 * Nothing but termini is placed inside the {@link Envelope} trains drive through.
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
        Envelope envelope = Envelope.of(ctx);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        int fromZ = Math.min(chunk.getMaxBlockZ() + REACH, layout.zSouthEnd());
        int toZ = Math.max(chunk.getMinBlockZ() - REACH, layout.zNorthEnd());
        for (int z = fromZ; z >= toZ; z--) {
            RailContext.Row row = ctx.row(z);
            List<RailAddition> styleAdditions = row.style().value().additions();
            for (int i = 0; i < styleAdditions.size(); i++) {
                String id = row.style().id() + "#" + i;
                slot(level, chunk, ctx, row, new Keyed(id, id.hashCode(), styleAdditions.get(i)), envelope, pos);
            }
            for (Keyed keyed : global) {
                slot(level, chunk, ctx, row, keyed, envelope, pos);
            }
        }

        ends(level, chunk, ctx, global, pos);

        tiles(level, chunk, ctx, envelope, pos);

        for (StationPlanner.Site site : StationPlanner.sites(ctx)) {
            if (site.zSouth() + REACH >= chunk.getMinBlockZ() && site.zNorth() - REACH <= chunk.getMaxBlockZ()) {
                station(level, chunk, ctx, site, envelope, pos);
            }
        }
    }

    // --- slotted additions and scatter ---------------------------------------------------------

    private static void slot(WorldGenLevel level, ChunkPos chunk, RailContext ctx, RailContext.Row row, Keyed keyed,
                             Envelope envelope, BlockPos.MutableBlockPos pos) {
        RailAddition a = keyed.addition();
        if (a.isEnd() || !matchesRow(ctx, a, row)) {
            return;
        }
        if (a.scatter().isPresent()) {
            if (row.z() >= chunk.getMinBlockZ() && row.z() <= chunk.getMaxBlockZ() && biomeAllows(ctx, a, row)) {
                scatter(level, chunk, ctx, row, keyed, envelope, pos);
            }
        }
        if (a.blocks().isEmpty() || Math.floorMod(row.z() - a.phase(), a.spacing()) != 0 || !biomeAllows(ctx, a, row)) {
            return;
        }
        for (int sign : a.signs()) {
            if (ctx.random(keyed.salt(), row.z(), sign) < a.chance()) {
                placeBlocks(level, chunk, a, row.trackX() + ("center".equals(a.side()) ? 0 : sign * a.offset()), row.bedY(), row.z(),
                        "center".equals(a.side()) ? 1 : sign, envelope, pos);
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
                                Envelope envelope, BlockPos.MutableBlockPos pos) {
        RailAddition.Scatter scatter = keyed.addition().scatter().get();
        int total = scatter.states().stream().mapToInt(RailAddition.WeightedState::weight).sum();
        for (int dx = -scatter.reach(); dx <= scatter.reach(); dx++) {
            int x = row.trackX() + dx;
            if (dx == 0 || x < chunk.getMinBlockX() || x > chunk.getMaxBlockX() || envelope.contains(x, row.bedY(), row.z())) {
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
     * goes +x; -1 is west: outward goes -x and every state is mirrored. Blocks inside the envelope (if
     * given) are left out.
     */
    private static void placeBlocks(WorldGenLevel level, ChunkPos chunk, RailAddition a, int anchorX, int trackY, int anchorZ,
                                    int sign, Envelope envelope, BlockPos.MutableBlockPos pos) {
        boolean any = "any".equals(a.replace());
        for (RailAddition.Placement p : a.blocks()) {
            int x = anchorX + sign * p.outward();
            int z = anchorZ - p.forward();
            int y = trackY + a.height() + p.up();
            if (x < chunk.getMinBlockX() || x > chunk.getMaxBlockX() || z < chunk.getMinBlockZ() || z > chunk.getMaxBlockZ()
                    || (envelope != null && envelope.contains(x, y, z))) {
                continue;
            }
            pos.set(x, y, z);
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
                // termini stop trains: they stand on the track on purpose, no envelope
                placeBlocks(level, chunk, oriented, row.trackX() + ("center".equals(a.side()) ? 0 : sign * a.offset()), row.bedY(), z,
                        "center".equals(a.side()) ? 1 : sign, null, pos);
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

    // --- tiled structures ----------------------------------------------------------------------

    /** Cut covers and bridge structures of this chunk (StructurePlanner), after the bed and track, before stations. */
    private static void tiles(WorldGenLevel level, ChunkPos chunk, RailContext ctx, Envelope envelope, BlockPos.MutableBlockPos pos) {
        StructurePlanner.Plan plan = StructurePlanner.plan(ctx, level.getServer());
        for (StructurePlanner.Tile tile : plan.touching(chunk.getMinBlockZ(), chunk.getMaxBlockZ())) {
            RailTemplates.Structure structure = RailTemplates.structure(level.getServer(), tile.template());
            if (structure != null) {
                TemplatePlacer.place(level, chunk, structure, tile.trackX(), tile.bedY(), tile.trackZ(), tile.trackY(),
                        tile.zOrigin(), tile.stepZ(), 1, false, tile.foundation(), tile.foundationDepth(),
                        tile.kind() == StructurePlanner.Kind.BRIDGE, envelope, pos);
            }
        }
        int fromZ = chunk.getMinBlockZ();
        int toZ = chunk.getMaxBlockZ();
        for (StructurePlanner.SliceRun run : plan.slicesTouching(fromZ, toZ)) {
            RailTemplates.Structure top = RailTemplates.structure(level.getServer(), run.top());
            RailTemplates.Structure curve = RailTemplates.structure(level.getServer(), run.topCurve());
            for (int z = Math.max(fromZ, run.zMin()); z <= Math.min(toZ, run.zMax()); z++) {
                RailContext.Row row = ctx.row(z);
                // top_curve from curveMargin rows before a curve to curveMargin rows after it, so walls open up in time
                RailTemplates.Structure slice = ctx.nearCurve(z) && curve != null ? curve : top;
                if (slice != null) {
                    TemplatePlacer.placeSlice(level, chunk, slice, Math.floorMod(run.zMax() - z, slice.sizeX()), row.trackX(), row.bedY(),
                            run.trackY(), z, envelope, pos);
                }
            }
        }
    }

    // --- stations ------------------------------------------------------------------------------

    private static void station(WorldGenLevel level, ChunkPos chunk, RailContext ctx, StationPlanner.Site site, Envelope envelope,
                                BlockPos.MutableBlockPos pos) {
        RailStation def = site.def();
        // a station district may replace the building (RAILWAYS.md §A8.15); platform, stops and train stay the station's
        StationPlanner.Building building = StationPlanner.building(ctx, level.getServer(), site,
                RailSites.stationOverride(ctx.randomState(), site));
        ResourceLocation file = building.file();
        RailTemplates.Structure structure = file == null ? null : RailTemplates.structure(level.getServer(), file);
        if (structure != null) {
            RailStation.Template spec = building.spec().get();
            // station templates run from the site's north end, lower template z outward on the site's side
            TemplatePlacer.place(level, chunk, structure, site.trackX(), site.bedY(), spec.trackZ(), spec.trackY(),
                    site.zNorth(), 1, site.sign(), spec.clear(), spec.foundation(), spec.foundationDepth(), false, envelope, pos);
        } else {
            platform(level, chunk, site, envelope, pos);
        }

        Block block = BuiltInRegistries.BLOCK.get(STATION_BLOCK);
        for (StationPlanner.Stop stop : block == Blocks.AIR ? List.<StationPlanner.Stop>of() : site.stops()) {
            BlockPos stationPos = stop.pos();
            if (!inChunk(chunk, stationPos.getX(), stationPos.getZ())) {
                continue;
            }
            level.setBlock(stationPos, block.defaultBlockState(), 2);
            CompoundTag tag = new CompoundTag();
            tag.putString("id", STATION_BLOCK.toString());
            tag.putInt("x", stationPos.getX());
            tag.putInt("y", stationPos.getY());
            tag.putInt("z", stationPos.getZ());
            long bits = (long) (ctx.random(site.id().hashCode(), stationPos.getZ(), 11) * Long.MAX_VALUE);
            tag.putUUID("Id", new UUID(bits, stationPos.getZ() ^ ((long) stationPos.getX() << 32)));
            tag.put("TargetTrack", NbtUtils.writeBlockPos(new BlockPos(site.trackX() - stationPos.getX(), 0, 0)));
            // positive = pointed south along the track: trains heading north stop here (StationPlanner.Stop)
            tag.putBoolean("TargetDirection", stop.northbound());
            tag.putBoolean("Ortho", true);
            level.getChunk(stationPos).setBlockEntityNbt(tag);
        }

        RailContext.Row row = ctx.row(site.zCentre());
        for (int i = 0; i < def.additions().size(); i++) {
            RailAddition a = def.additions().get(i);
            String id = site.id() + "#" + i;
            if (ctx.random(id.hashCode(), site.zCentre(), 3) < a.chance()) {
                placeBlocks(level, chunk, a, site.trackX() + site.sign() * a.offset(), row.bedY(), site.zCentre(), site.sign(), envelope, pos);
            }
        }
    }

    private static boolean inChunk(ChunkPos chunk, int x, int z) {
        return x >= chunk.getMinBlockX() && x <= chunk.getMaxBlockX() && z >= chunk.getMinBlockZ() && z <= chunk.getMaxBlockZ();
    }

    /** A plain platform level with the track, with air above it, outside the envelope. */
    private static void platform(WorldGenLevel level, ChunkPos chunk, StationPlanner.Site site, Envelope envelope,
                                 BlockPos.MutableBlockPos pos) {
        RailStation.Look look = site.def().look();
        int y = site.bedY();
        for (int z = site.zSouth(); z >= site.zNorth(); z--) {
            for (int w = 0; w < look.width(); w++) {
                int x = site.trackX() + site.sign() * (look.gap() + w);
                if (!inChunk(chunk, x, z) || envelope.contains(x, y, z)) {
                    continue;
                }
                BlockState surface = w == 0 && look.edge().isPresent() ? look.edge().get() : look.platform();
                level.setBlock(pos.set(x, y, z), surface, 2);
                for (int dy = 1; dy <= look.clearance(); dy++) {
                    if (!level.getBlockState(pos.set(x, y + dy, z)).isAir()) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
    }
}
