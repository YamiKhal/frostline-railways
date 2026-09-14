package com.yamikhal.frostlinerailways.rail;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.content.trains.station.StationBlockEntity;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.yamikhal.frostlinerailways.RailwaysConfig;
import com.yamikhal.frostlinerailways.rail.decor.RailContext;
import com.yamikhal.frostlinerailways.rail.decor.RailTemplates;
import com.yamikhal.frostlinerailways.rail.decor.StationPlanner;
import com.yamikhal.frostlinerailways.rail.graph.RailGraphService;
import com.yamikhal.frostlinerailways.rail.layout.RailLayout;
import com.yamikhal.frostlinerailways.rail.layout.RailLayoutService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The runtime side of the line's stations (RAILWAYS.md §A8.6–§A8.8), on the server thread:
 *
 *   names           a generated station block, once Create has bound it to the graph, gets its stop's
 *                   name (unless a player already renamed it)
 *   starting train  the first time the spawn station's northbound stop is loaded with the graph built, its
 *                   train template is built on the track behind it and assembled by the station itself,
 *                   exactly as a player would; recorded in data/frostline_railways_trains.dat so it happens
 *                   once per world
 *   first join      a player joining for the first time is put on the spawn station's platform
 *
 * The train is assembled by Create's own station (not constructed from NBT) because that is the path other
 * train mods hook: with create_interactive (Valkyrien Skies) a train built from raw contraption NBT is
 * registered but its carriage never appears ("The ship was not loaded").
 *
 * Stops are queued when their chunk loads and dropped once handled or unloaded: nothing scans the
 * world, and the only waiting is for Create to bind a station (R5, R7).
 */
public final class StationRuntime {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final UUID OWNER = UUID.nameUUIDFromBytes("frostline_railways:starting_train".getBytes(StandardCharsets.UTF_8));
    private static final String TRAINS_FILE = "frostline_railways_trains.dat";
    private static final String SPAWNED_TAG = "frostline_railways_spawned";
    private static final String DEFAULT_NAME = "Track Station";
    private static final int INTERVAL = 10;
    private static final int PLACE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private record Pending(StationPlanner.Site site, StationPlanner.Stop stop) {
    }

    private enum Result { WAIT, DONE }

    private static final Map<BlockPos, Pending> PENDING = new ConcurrentHashMap<>();
    private static Set<String> trainsPlaced;
    private static int ticks;

    private StationRuntime() {
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING.clear();
        trainsPlaced = null;
    }

    private static RailContext context(ServerLevel level) {
        RailLayout layout = RailLayoutService.layout(level.dimension(), false);
        RailLineDef def = RailLayoutService.definition();
        return layout == null || def == null ? null : new RailContext(level, layout, def);
    }

    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getChunk() instanceof LevelChunk chunk) || !(event.getLevel() instanceof ServerLevel level)
                || level.dimension() != RailLayoutService.dimension()) {
            return;
        }
        RailContext ctx = context(level);
        ChunkPos cp = chunk.getPos();
        if (ctx == null || !ctx.layout.touches(cp.getMinBlockX(), cp.getMaxBlockX(), cp.getMinBlockZ(), cp.getMaxBlockZ(), 16)) {
            return;
        }
        for (StationPlanner.Site site : StationPlanner.sites(ctx)) {
            if (site.zSouth() < cp.getMinBlockZ() || site.zNorth() > cp.getMaxBlockZ()) {
                continue;
            }
            for (StationPlanner.Stop stop : site.stops()) {
                if (SectionPos.blockToSectionCoord(stop.pos().getX()) == cp.x && SectionPos.blockToSectionCoord(stop.pos().getZ()) == cp.z) {
                    PENDING.put(stop.pos(), new Pending(site, stop));
                }
            }
        }
    }

    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING.isEmpty() || ++ticks % INTERVAL != 0
                || RailLayoutService.dimension() == null) {
            return;
        }
        ServerLevel level = event.getServer().getLevel(RailLayoutService.dimension());
        if (level == null) {
            return;
        }
        Iterator<Map.Entry<BlockPos, Pending>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Pending> entry = it.next();
            BlockPos pos = entry.getKey();
            if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof StationBlockEntity be)) {
                it.remove();
                continue;
            }
            GlobalStation station = be.getStation();
            if (station == null) {
                continue; // Create binds it once the graph reaches this track
            }
            Pending pending = entry.getValue();
            if (DEFAULT_NAME.equals(station.name)) {
                be.updateName(pending.stop().name());
            }
            Optional<ResourceLocation> train = pending.site().def().service().train();
            if (pending.stop().northbound() && train.isPresent() && RailwaysConfig.startingTrain()) {
                // the graph around the spawn station is built first, so this does not wait for the whole line
                if (!RailGraphService.readyAround(pending.site().zNorth() - 32, pending.site().zSouth() + 32)) {
                    continue;
                }
                String key = pending.site().id() + "@" + pending.site().zCentre();
                Set<String> placed = trainsPlaced(level.getServer());
                if (!placed.contains(key)) {
                    Result result;
                    try {
                        result = placeTrain(level, be, station, pending, train.get());
                    } catch (RuntimeException e) {
                        LOGGER.error("[FrostlineRailways] starting train {} at {} failed", train.get(), pos, e);
                        result = Result.DONE;
                    }
                    if (result == Result.WAIT) {
                        continue;
                    }
                    placed.add(key);
                    writeTrains(level.getServer(), placed);
                }
            }
            it.remove();
        }
    }

    // --- starting train ---------------------------------------------------------------------------

    /**
     * Builds the template's carriage on the track behind the stop — first bogey one block past the station,
     * where the station's assembly looks for it — glues it and lets the station assemble it. The northbound
     * stop assembles towards the south, so the train stands on the platform with its front at the station,
     * facing north.
     *
     * Glue: one box over everything at bogey height and up, and below that two boxes either side of the track
     * column, so the track under the bogeys is never glued into the train (which took the station's track
     * and crashed Create when it read it back). Station blocks inside the train's box are lifted out for the
     * assembly and put back; track Create still took is put back before the station leaves assembly mode.
     */
    private static Result placeTrain(ServerLevel level, StationBlockEntity be, GlobalStation station, Pending pending, ResourceLocation id) {
        if (station.getPresentTrain() != null) {
            return Result.DONE;
        }
        RailTemplates.Train template = RailTemplates.train(level.getServer(), id);
        if (template == null) {
            return Result.DONE;
        }
        Direction direction = be.getAssemblyDirection();
        if (direction == null || !be.edgePoint.hasValidTrack()) {
            return Result.WAIT;
        }
        BlockPos track = be.edgePoint.getGlobalPosition();
        BlockPos anchor = track.above().relative(direction);
        Rotation rotation = Rotation.NONE;
        for (Rotation candidate : Rotation.values()) {
            if (candidate.rotate(template.assembly()) == direction) {
                rotation = candidate;
            }
        }
        boolean alongZ = direction.getAxis() == Direction.Axis.Z;
        int trackAcross = alongZ ? track.getX() : track.getZ();

        List<BlockPos> positions = new ArrayList<>();
        List<RailTemplates.TrainBlock> blocks = new ArrayList<>();
        int skipped = 0;
        for (RailTemplates.TrainBlock block : template.blocks()) {
            BlockPos p = anchor.offset(block.pos().rotate(rotation));
            if (!level.isLoaded(p)) {
                return Result.WAIT;
            }
            if (p.getY() < anchor.getY() && (alongZ ? p.getX() : p.getZ()) == trackAcross) {
                skipped++; // would stand on the track itself
                continue;
            }
            positions.add(p);
            blocks.add(block);
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : positions) {
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
            maxZ = Math.max(maxZ, p.getZ());
        }

        // the track in the train's box, to put back whatever the assembly takes
        Map<BlockPos, BlockState> trackStates = new LinkedHashMap<>();
        Set<BlockPos> own = new HashSet<>(positions);
        List<Map.Entry<BlockPos, BlockState>> lifted = new ArrayList<>();
        List<CompoundTag> liftedData = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(minX, Math.min(minY, track.getY()), minZ, maxX, maxY, maxZ)) {
            BlockState state = level.getBlockState(p);
            if (state.getBlock() instanceof ITrackBlock) {
                trackStates.put(p.immutable(), state);
                continue;
            }
            if (own.contains(p) || state.isAir()) {
                continue;
            }
            BlockEntity blockEntity = level.getBlockEntity(p);
            lifted.add(Map.entry(p.immutable(), state));
            liftedData.add(blockEntity == null ? null : blockEntity.saveWithFullMetadata());
            level.removeBlockEntity(p);
            level.setBlock(p, Blocks.AIR.defaultBlockState(), PLACE_FLAGS);
        }

        for (int i = 0; i < positions.size(); i++) {
            RailTemplates.TrainBlock block = blocks.get(i);
            BlockPos p = positions.get(i);
            level.setBlock(p, block.state().rotate(rotation), PLACE_FLAGS);
            if (block.data() != null) {
                BlockEntity blockEntity = level.getBlockEntity(p);
                if (blockEntity != null) {
                    CompoundTag tag = block.data().copy();
                    tag.putInt("x", p.getX());
                    tag.putInt("y", p.getY());
                    tag.putInt("z", p.getZ());
                    blockEntity.load(tag);
                    blockEntity.setChanged();
                }
            }
        }

        int top = anchor.getY();
        level.addFreshEntity(new SuperGlueEntity(level, new AABB(minX, top, minZ, maxX + 1, maxY + 1, maxZ + 1)));
        if (minY < top) {
            if (alongZ) {
                if (minX < trackAcross) {
                    level.addFreshEntity(new SuperGlueEntity(level, new AABB(minX, minY, minZ, trackAcross, top + 1, maxZ + 1)));
                }
                if (maxX > trackAcross) {
                    level.addFreshEntity(new SuperGlueEntity(level, new AABB(trackAcross + 1, minY, minZ, maxX + 1, top + 1, maxZ + 1)));
                }
            } else {
                if (minZ < trackAcross) {
                    level.addFreshEntity(new SuperGlueEntity(level, new AABB(minX, minY, minZ, maxX + 1, top + 1, trackAcross)));
                }
                if (maxZ > trackAcross) {
                    level.addFreshEntity(new SuperGlueEntity(level, new AABB(minX, minY, trackAcross + 1, maxX + 1, top + 1, maxZ + 1)));
                }
            }
        }

        be.enterAssemblyMode(null);
        be.assemble(OWNER);
        Train train = station.getPresentTrain();

        int restored = 0;
        for (Map.Entry<BlockPos, BlockState> entry : trackStates.entrySet()) {
            if (!(level.getBlockState(entry.getKey()).getBlock() instanceof ITrackBlock)) {
                level.setBlock(entry.getKey(), entry.getValue(), PLACE_FLAGS);
                restored++;
            }
        }
        if (be.edgePoint.hasValidTrack()) {
            be.exitAssemblyMode();
        } else {
            LOGGER.warn("[FrostlineRailways] station at {} lost its track during assembly; left in assembly mode", be.getBlockPos());
        }
        for (int i = 0; i < lifted.size(); i++) {
            BlockPos p = lifted.get(i).getKey();
            if (!level.getBlockState(p).isAir()) {
                continue;
            }
            level.setBlock(p, lifted.get(i).getValue(), PLACE_FLAGS);
            BlockEntity blockEntity = liftedData.get(i) == null ? null : level.getBlockEntity(p);
            if (blockEntity != null) {
                blockEntity.load(liftedData.get(i));
                blockEntity.setChanged();
            }
        }

        if (train != null) {
            train.name = Component.literal(pending.site().name() + " Shuttle");
            LOGGER.info("[FrostlineRailways] starting train {} ({}) assembled at {}, facing {} ({} template blocks on the track skipped, {} track blocks restored)",
                    id, train.name.getString(), pending.stop().name(), direction.getOpposite(), skipped, restored);
        } else {
            LOGGER.warn("[FrostlineRailways] starting train {} placed at {} but the station did not assemble it; open that station to see why",
                    id, be.getBlockPos());
        }
        return Result.DONE;
    }

    private static File trainsFile(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(TRAINS_FILE).toFile();
    }

    private static Set<String> trainsPlaced(MinecraftServer server) {
        if (trainsPlaced == null) {
            trainsPlaced = new HashSet<>();
            try {
                File file = trainsFile(server);
                if (file.isFile()) {
                    ListTag list = NbtIo.readCompressed(file).getList("Placed", Tag.TAG_STRING);
                    for (int i = 0; i < list.size(); i++) {
                        trainsPlaced.add(list.getString(i));
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("[FrostlineRailways] could not read {}", TRAINS_FILE, e);
            }
        }
        return trainsPlaced;
    }

    private static void writeTrains(MinecraftServer server, Set<String> placed) {
        try {
            ListTag list = new ListTag();
            placed.forEach(key -> list.add(StringTag.valueOf(key)));
            CompoundTag tag = new CompoundTag();
            tag.put("Placed", list);
            File file = trainsFile(server);
            file.getParentFile().mkdirs();
            NbtIo.writeCompressed(tag, file);
        } catch (Exception e) {
            LOGGER.warn("[FrostlineRailways] could not write {}", TRAINS_FILE, e);
        }
    }

    // --- first join -------------------------------------------------------------------------------

    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!RailwaysConfig.spawnOnPlatform() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        CompoundTag persisted = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        if (persisted.getBoolean(SPAWNED_TAG)) {
            return;
        }
        persisted.putBoolean(SPAWNED_TAG, true);
        player.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
        if (RailLayoutService.dimension() == null) {
            return;
        }
        ServerLevel level = player.server.getLevel(RailLayoutService.dimension());
        if (level == null || player.serverLevel() != level
                || player.blockPosition().distSqr(level.getSharedSpawnPos()) > 64 * 64) {
            return;
        }
        RailContext ctx = context(level);
        StationPlanner.Site site = ctx == null ? null : StationPlanner.spawnSite(ctx);
        BlockPos stand = site == null ? null : standable(level, site);
        if (stand != null) {
            player.teleportTo(level, stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, site.sign() > 0 ? 90.0F : -90.0F, 0.0F);
        }
    }

    /** A free spot on the platform near the station's middle, only if the station was actually generated. */
    private static BlockPos standable(ServerLevel level, StationPlanner.Site site) {
        List<StationPlanner.Stop> stops = site.stops();
        if (stops.isEmpty() || !level.isLoaded(stops.get(0).pos())
                || !(level.getBlockEntity(stops.get(0).pos()) instanceof StationBlockEntity)) {
            return null;
        }
        int gap = site.def().look().gap();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dz = 0; dz <= 12; dz++) {
            for (int s = 1; s >= -1; s -= 2) {
                if (dz == 0 && s < 0) {
                    continue;
                }
                int z = site.zCentre() + dz * s;
                for (int out = gap + 1; out <= gap + 6; out++) {
                    int x = site.trackX() + site.sign() * out;
                    for (int y = site.bedY() + 4; y >= site.bedY(); y--) {
                        if (fits(level, pos, x, y, z)) {
                            return new BlockPos(x, y, z);
                        }
                    }
                }
            }
        }
        return null;
    }

    private static boolean fits(ServerLevel level, BlockPos.MutableBlockPos pos, int x, int y, int z) {
        BlockState below = level.getBlockState(pos.set(x, y - 1, z));
        if (below.getCollisionShape(level, pos).isEmpty() || !below.getFluidState().isEmpty()) {
            return false;
        }
        for (int dy = 0; dy <= 1; dy++) {
            BlockState state = level.getBlockState(pos.set(x, y + dy, z));
            if (!state.getCollisionShape(level, pos).isEmpty() || !state.getFluidState().isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
