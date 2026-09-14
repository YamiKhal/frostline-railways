package com.yamikhal.frostlinerailways.rail.decor;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Structure templates the line places: data/&lt;ns&gt;/frostline_rail/template/&lt;path&gt;.nbt, vanilla structure
 * format (RAILWAYS.md §A8.6). Read with the server's resource manager on first use, from any thread,
 * and cached until the next datapack reload.
 *
 *   station  blocks and block entity NBT of a station building (create:track, create:track_station and
 *            structure voids are dropped: the line writes its own track and stations)
 *   train    the first create:carriage_contraption entity of a saved train: its blocks relative to the
 *            first bogey, and the direction it was assembled in
 */
public final class RailTemplates {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<String> SKIPPED = Set.of("create:track", "create:track_station", "minecraft:structure_void");

    public record Structure(int sizeX, int sizeY, int sizeZ, BlockState[] states, Map<Integer, CompoundTag> nbt, int[] bottom) {
        private int index(int x, int y, int z) {
            return (x * sizeY + y) * sizeZ + z;
        }

        public BlockState state(int x, int y, int z) {
            return states[index(x, y, z)];
        }

        public CompoundTag nbt(int x, int y, int z) {
            return nbt.get(index(x, y, z));
        }

        /** Lowest y with a block in column (x, z), or -1. */
        public int bottom(int x, int z) {
            return bottom[x * sizeZ + z];
        }
    }

    public record TrainBlock(BlockPos pos, BlockState state, CompoundTag data) {
    }

    /** A carriage: its Create contraption NBT (as saved in the carriage entity), blocks relative to the first bogey, assembly direction. */
    public record Train(Direction assembly, List<TrainBlock> blocks, CompoundTag contraption) {
    }

    private static final Map<ResourceLocation, Optional<Structure>> STRUCTURES = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, Optional<Train>> TRAINS = new ConcurrentHashMap<>();

    private RailTemplates() {
    }

    /** Template id → the files it stands for: itself if it exists, and every "&lt;id&gt;_&lt;number&gt;". */
    private static volatile Map<ResourceLocation, List<ResourceLocation>> numbered;
    private static final Pattern NUMBERED = Pattern.compile("^(.*)_(\\d+)$");
    private static final String FOLDER = "frostline_rail/template/";

    static void clear() {
        STRUCTURES.clear();
        TRAINS.clear();
        numbered = null;
    }

    /**
     * The template file to use for id: id itself or one of its numbered files "id_1", "id_2", ..., chosen by a
     * uniform roll in [0, 1); null if there is none.
     */
    public static ResourceLocation pick(MinecraftServer server, ResourceLocation id, double roll) {
        List<ResourceLocation> candidates = candidates(server, id);
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(Math.min(candidates.size() - 1, (int) (roll * candidates.size())));
    }

    public static List<ResourceLocation> candidates(MinecraftServer server, ResourceLocation id) {
        Map<ResourceLocation, List<ResourceLocation>> index = numbered;
        if (index == null) {
            synchronized (RailTemplates.class) {
                if (numbered == null) {
                    numbered = index(server);
                }
                index = numbered;
            }
        }
        List<ResourceLocation> list = index.get(id);
        if (list == null) {
            LOGGER.error("[FrostlineRailways] template {} not found (no frostline_rail/template/{}.nbt or {}_<n>.nbt)", id, id.getPath(), id.getPath());
            return List.of();
        }
        return list;
    }

    private static Map<ResourceLocation, List<ResourceLocation>> index(MinecraftServer server) {
        Map<ResourceLocation, List<ResourceLocation>> map = new HashMap<>();
        server.getResourceManager().listResources("frostline_rail/template", file -> file.getPath().endsWith(".nbt")).keySet().stream()
                .sorted(Comparator.comparing(ResourceLocation::toString))
                .forEach(file -> {
                    String path = file.getPath().substring(FOLDER.length(), file.getPath().length() - ".nbt".length());
                    ResourceLocation id = new ResourceLocation(file.getNamespace(), path);
                    map.computeIfAbsent(id, k -> new ArrayList<>()).add(id);
                    Matcher matcher = NUMBERED.matcher(path);
                    if (matcher.matches()) {
                        map.computeIfAbsent(new ResourceLocation(file.getNamespace(), matcher.group(1)), k -> new ArrayList<>()).add(id);
                    }
                });
        map.replaceAll((k, v) -> List.copyOf(v));
        LOGGER.info("[FrostlineRailways] {} template names ({} files)", map.size(), map.values().stream().filter(l -> l.size() == 1).count());
        return Map.copyOf(map);
    }

    public static Structure structure(MinecraftServer server, ResourceLocation id) {
        return server == null ? null : STRUCTURES.computeIfAbsent(id, key -> Optional.ofNullable(readStructure(server, key))).orElse(null);
    }

    public static Train train(MinecraftServer server, ResourceLocation id) {
        return server == null ? null : TRAINS.computeIfAbsent(id, key -> Optional.ofNullable(readTrain(server, key))).orElse(null);
    }

    private static CompoundTag read(MinecraftServer server, ResourceLocation id) {
        ResourceLocation file = new ResourceLocation(id.getNamespace(), "frostline_rail/template/" + id.getPath() + ".nbt");
        Optional<Resource> resource = server.getResourceManager().getResource(file);
        if (resource.isEmpty()) {
            LOGGER.error("[FrostlineRailways] template {} not found ({})", id, file);
            return null;
        }
        try (InputStream in = resource.get().open()) {
            CompoundTag tag = NbtIo.readCompressed(in);
            normalizeItems(tag);
            return tag;
        } catch (Exception e) {
            LOGGER.error("[FrostlineRailways] template {} could not be read", id, e);
            return null;
        }
    }

    /**
     * Item stacks saved by newer Minecraft versions ({"id", "count": int}) read as empty in 1.20.1, which
     * wants "Count" as a byte; an empty "Item" makes a copycat drop its material and show its base texture.
     * Adds "Count" wherever a stack only has "count".
     */
    static void normalizeItems(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            if (compound.contains("id", Tag.TAG_STRING) && compound.contains("count", Tag.TAG_ANY_NUMERIC) && !compound.contains("Count")) {
                compound.putByte("Count", (byte) Math.max(1, Math.min(127, compound.getInt("count"))));
            }
            for (String key : new ArrayList<>(compound.getAllKeys())) {
                normalizeItems(compound.get(key));
            }
        } else if (tag instanceof ListTag list) {
            for (Tag element : list) {
                normalizeItems(element);
            }
        }
    }

    private static Structure readStructure(MinecraftServer server, ResourceLocation id) {
        CompoundTag tag = read(server, id);
        if (tag == null) {
            return null;
        }
        ListTag size = tag.getList("size", Tag.TAG_INT);
        int sx = size.getInt(0);
        int sy = size.getInt(1);
        int sz = size.getInt(2);
        ListTag palette = tag.contains("palettes", Tag.TAG_LIST)
                ? tag.getList("palettes", Tag.TAG_LIST).getList(0) : tag.getList("palette", Tag.TAG_COMPOUND);
        BlockState[] paletteStates = new BlockState[palette.size()];
        boolean[] skipped = new boolean[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            CompoundTag entry = palette.getCompound(i);
            skipped[i] = SKIPPED.contains(entry.getString("Name"));
            paletteStates[i] = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), entry);
        }
        BlockState[] states = new BlockState[sx * sy * sz];
        Map<Integer, CompoundTag> nbt = new HashMap<>();
        int[] bottom = new int[sx * sz];
        Arrays.fill(bottom, -1);
        ListTag blocks = tag.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompound(i);
            int state = block.getInt("state");
            if (state < 0 || state >= paletteStates.length || skipped[state]) {
                continue;
            }
            ListTag pos = block.getList("pos", Tag.TAG_INT);
            int x = pos.getInt(0);
            int y = pos.getInt(1);
            int z = pos.getInt(2);
            if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) {
                continue;
            }
            int index = (x * sy + y) * sz + z;
            states[index] = paletteStates[state];
            if (paletteStates[state].isAir()) {
                continue; // explicit air: placed as air, never a column's lowest block
            }
            if (block.contains("nbt", Tag.TAG_COMPOUND) && block.getCompound("nbt").contains("id")) {
                nbt.put(index, block.getCompound("nbt"));
            }
            int column = x * sz + z;
            if (bottom[column] < 0 || y < bottom[column]) {
                bottom[column] = y;
            }
        }
        LOGGER.info("[FrostlineRailways] template {}: {}x{}x{}, {} blocks", id, sx, sy, sz, blocks.size());
        return new Structure(sx, sy, sz, states, Map.copyOf(nbt), bottom);
    }

    private static Train readTrain(MinecraftServer server, ResourceLocation id) {
        CompoundTag tag = read(server, id);
        if (tag == null) {
            return null;
        }
        ListTag entities = tag.getList("entities", Tag.TAG_COMPOUND);
        for (int e = 0; e < entities.size(); e++) {
            CompoundTag entity = entities.getCompound(e).getCompound("nbt");
            if (!"create:carriage_contraption".equals(entity.getString("id"))) {
                continue;
            }
            CompoundTag contraption = entity.getCompound("Contraption");
            Direction assembly = Direction.byName(contraption.getString("AssemblyDirection").toLowerCase(Locale.ROOT));
            CompoundTag blocks = contraption.getCompound("Blocks");
            ListTag palette = blocks.getList("Palette", Tag.TAG_COMPOUND);
            ListTag list = blocks.getList("BlockList", Tag.TAG_COMPOUND);
            List<TrainBlock> out = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                CompoundTag block = list.getCompound(i);
                BlockState state = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), palette.getCompound(block.getInt("State")));
                CompoundTag data = block.contains("Data", Tag.TAG_COMPOUND) ? block.getCompound("Data") : null;
                out.add(new TrainBlock(BlockPos.of(block.getLong("Pos")), state, data));
            }
            LOGGER.info("[FrostlineRailways] train template {}: {} blocks, assembled towards {}", id, out.size(), assembly);
            return new Train(assembly == null ? Direction.SOUTH : assembly, List.copyOf(out), contraption);
        }
        LOGGER.error("[FrostlineRailways] train template {} has no create:carriage_contraption entity", id);
        return null;
    }
}
