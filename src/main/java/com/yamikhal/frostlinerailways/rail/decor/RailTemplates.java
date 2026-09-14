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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structure templates the line places: data/&lt;ns&gt;/frostline_rail/template/&lt;path&gt;.nbt, vanilla structure
 * format (RAILWAYS.md §A8.6, §A8.10, §A8.11). Read with the server's resource manager on first use, from any
 * thread, and cached until the next datapack reload.
 *
 *   station  blocks and block entity NBT of a building (create:track, create:track_station and structure voids
 *            are dropped: the line writes its own track and stations)
 *   train    the first create:carriage_contraption entity of a saved train: its blocks relative to the first
 *            bogey, and the direction it was assembled in
 *
 * Numbered variations, everywhere a template id is used (stations, trains, tunnels, covers, bridges, tops):
 * an id stands for its own file (if there is one) and for every "&lt;id&gt;_&lt;number&gt;", each of which may have
 * numbered variations of its own. {@link #pick} walks down that tree with one seeded roll: "bridge/stone_top"
 * picks among stone_top_1, stone_top_2, and if stone_top_2_1 and stone_top_2_2 exist, stone_top_2 picks again.
 * Names that only differ by a word ("stone_top_lit") are separate templates, with their own numbers.
 */
public final class RailTemplates {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<String> SKIPPED = Set.of("create:track", "create:track_station", "minecraft:structure_void");
    private static final Pattern NUMBERED = Pattern.compile("^(.*)_(\\d+)$");
    private static final String FOLDER = "frostline_rail/template/";

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

    /** A name in the variation tree: whether a file has exactly this name, and its numbered variations. */
    private record Node(boolean file, List<ResourceLocation> children) {
    }

    private static final Map<ResourceLocation, Optional<Structure>> STRUCTURES = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, Optional<Train>> TRAINS = new ConcurrentHashMap<>();
    private static volatile Map<ResourceLocation, Node> names;

    private RailTemplates() {
    }

    static void clear() {
        STRUCTURES.clear();
        TRAINS.clear();
        names = null;
    }

    public static Structure structure(MinecraftServer server, ResourceLocation id) {
        return server == null ? null : STRUCTURES.computeIfAbsent(id, key -> Optional.ofNullable(readStructure(server, key))).orElse(null);
    }

    public static Train train(MinecraftServer server, ResourceLocation id) {
        return server == null ? null : TRAINS.computeIfAbsent(id, key -> Optional.ofNullable(readTrain(server, key))).orElse(null);
    }

    /**
     * The template file to use for id, chosen with a uniform roll in [0, 1) down the variation tree (see class
     * doc); null if neither id nor any numbered variation of it exists.
     */
    public static ResourceLocation pick(MinecraftServer server, ResourceLocation id, double roll) {
        if (server == null) {
            return null;
        }
        Map<ResourceLocation, Node> index = index(server);
        Node node = index.get(id);
        if (node == null) {
            LOGGER.error("[FrostlineRailways] template {} not found (no frostline_rail/template/{}.nbt or {}_<n>.nbt)", id, id.getPath(), id.getPath());
            return null;
        }
        for (int depth = 0; depth < 16; depth++) {
            int options = (node.file() ? 1 : 0) + node.children().size();
            if (options == 0) {
                return null;
            }
            double scaled = Math.max(0, Math.min(0.999_999_999, roll)) * options;
            int choice = (int) scaled;
            roll = scaled - choice;
            if (node.file() && choice == 0) {
                return id;
            }
            id = node.children().get(choice - (node.file() ? 1 : 0));
            node = index.get(id);
        }
        return id;
    }

    private static Map<ResourceLocation, Node> index(MinecraftServer server) {
        Map<ResourceLocation, Node> current = names;
        if (current != null) {
            return current;
        }
        synchronized (RailTemplates.class) {
            if (names == null) {
                Set<ResourceLocation> files = new LinkedHashSet<>();
                Map<ResourceLocation, Set<ResourceLocation>> children = new HashMap<>();
                server.getResourceManager().listResources("frostline_rail/template", file -> file.getPath().endsWith(".nbt")).keySet()
                        .forEach(file -> {
                            String path = file.getPath().substring(FOLDER.length(), file.getPath().length() - ".nbt".length());
                            ResourceLocation id = new ResourceLocation(file.getNamespace(), path);
                            files.add(id);
                            // register every ancestor: a_1_2 is a child of a_1, a_1 of a
                            for (Matcher m = NUMBERED.matcher(path); m.matches(); m = NUMBERED.matcher(path)) {
                                ResourceLocation parent = new ResourceLocation(file.getNamespace(), m.group(1));
                                children.computeIfAbsent(parent, k -> new LinkedHashSet<>()).add(new ResourceLocation(file.getNamespace(), path));
                                path = m.group(1);
                            }
                        });
                Map<ResourceLocation, Node> map = new HashMap<>();
                Set<ResourceLocation> all = new LinkedHashSet<>(files);
                all.addAll(children.keySet());
                for (ResourceLocation id : all) {
                    List<ResourceLocation> kids = new ArrayList<>(children.getOrDefault(id, Set.of()));
                    kids.sort(Comparator.comparingLong(RailTemplates::suffix));
                    map.put(id, new Node(files.contains(id), List.copyOf(kids)));
                }
                names = Map.copyOf(map);
                LOGGER.info("[FrostlineRailways] templates: {} files, {} names", files.size(), map.size());
            }
            return names;
        }
    }

    private static long suffix(ResourceLocation id) {
        Matcher m = NUMBERED.matcher(id.getPath());
        try {
            return m.matches() ? Long.parseLong(m.group(2)) : 0L;
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    private static CompoundTag read(MinecraftServer server, ResourceLocation id) {
        ResourceLocation file = new ResourceLocation(id.getNamespace(), FOLDER + id.getPath() + ".nbt");
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
