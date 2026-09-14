package com.yamikhal.frostlinerailways;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;
import java.util.function.Supplier;

/**
 * config/frostline_railways.toml — the server-side railway settings. Read by the server (or the
 * integrated server in single player).
 *
 * Values are read through the static accessors, which fall back to the default when the file is
 * not loaded yet, so code that runs early (worldgen threads) never throws. What the line looks
 * like and where it goes is datapack data under data/&lt;namespace&gt;/frostline_rail/.
 */
public final class RailwaysConfig {

    public static final ForgeConfigSpec SPEC;

    private static final ForgeConfigSpec.BooleanValue RAIL_ENABLED;
    private static final ForgeConfigSpec.ConfigValue<String> LINE_ID;
    private static final ForgeConfigSpec.BooleanValue RAIL_PERF_LOGGING;
    private static final ForgeConfigSpec.IntValue GRAPH_EDGES_PER_TICK;
    private static final ForgeConfigSpec.DoubleValue GRAPH_MILLIS_PER_TICK;
    private static final ForgeConfigSpec.BooleanValue RECONCILE_ON_CHUNK_LOAD;
    private static final ForgeConfigSpec.BooleanValue SPAWN_NEAR_LINE;
    private static final ForgeConfigSpec.IntValue SPAWN_Z;
    private static final ForgeConfigSpec.IntValue SPAWN_OFFSET_X;
    private static final ForgeConfigSpec.BooleanValue SPAWN_ON_PLATFORM;
    private static final ForgeConfigSpec.BooleanValue STARTING_TRAIN;
    private static final ForgeConfigSpec.BooleanValue GIRDER_TRACKS;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> BIOME_RAISE;
    private static final ForgeConfigSpec.BooleanValue MINUS_ONE_IS_HIGHEST;
    private static final ForgeConfigSpec.IntValue HIGHEST_LOOKAHEAD;
    private static final ForgeConfigSpec.DoubleValue HIGHEST_STRICTNESS;
    private static final ForgeConfigSpec.BooleanValue CLEAR_ABOVE_TRACK;
    private static final ForgeConfigSpec.IntValue CLEAR_EXTRA_WIDTH;
    private static final ForgeConfigSpec.BooleanValue CUT_COVERS;
    private static final ForgeConfigSpec.BooleanValue BRIDGE_STRUCTURES;
    private static final ForgeConfigSpec.BooleanValue TUNNEL_STRUCTURES;
    private static final ForgeConfigSpec.BooleanValue COVER_LAYER;
    private static final ForgeConfigSpec.IntValue TRAIN_HALF_WIDTH;
    private static final ForgeConfigSpec.IntValue TRAIN_HEIGHT;
    private static final ForgeConfigSpec.IntValue CLEAR_HEIGHT;
    private static final ForgeConfigSpec.IntValue BRIDGE_FULL_MIN_LENGTH;
    private static final ForgeConfigSpec.IntValue BRIDGE_FULL_MIN_MIDDLES;
    private static final ForgeConfigSpec.IntValue CURVE_MARGIN;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment(
                "Frostline Rail: the railway generated with the world from the seed (RAILWAYS.md).",
                "Needs Create. The line's shape and look come from the datapack (frostline_rail/).",
                "Only chunks generated while enabled get track. Commands: /frostline rail info|where|tp|graph|layout"
        ).push("frostline_rail");
        RAIL_ENABLED = b.comment("Generate the Frostline railway and build its Create track graph.")
                .define("enabled", true);
        LINE_ID = b.comment("Which datapack line to generate: frostline_rail/line/<id>.json, as namespace:id.")
                .define("line", "frostline:main");
        RAIL_PERF_LOGGING = b.comment("Log [railperf] timings for worldgen and graph work.")
                .define("perfLogging", false);
        GRAPH_EDGES_PER_TICK = b.comment("Most Create graph edges added per server tick.")
                .defineInRange("graphEdgesPerTick", 32, 1, 4096);
        GRAPH_MILLIS_PER_TICK = b.comment("Most milliseconds of graph work per server tick, whatever graphEdgesPerTick allows.")
                .defineInRange("graphMillisPerTick", 2.0, 0.1, 50.0);
        RECONCILE_ON_CHUNK_LOAD = b.comment("When a chunk carrying the line loads, re-add any graph edges it should have and does not.")
                .define("reconcileOnChunkLoad", true);
        SPAWN_NEAR_LINE = b.comment("New worlds spawn beside the rail line instead of where vanilla would pick.")
                .define("spawnNearLine", true);
        SPAWN_Z = b.comment("z of the new world spawn along the line (zone one runs from about z 0 to z -3000).")
                .defineInRange("spawnZ", -128, -30000000, 30000000);
        SPAWN_OFFSET_X = b.comment("Blocks east of the track the spawn is placed (negative = west). Keep it outside the track bed.")
                .defineInRange("spawnOffsetX", 12, -256, 256);
        SPAWN_ON_PLATFORM = b.comment("With a spawn station (a frostline_rail/station with \"spawn\": true): new worlds spawn at it,",
                        "and a player joining for the first time is put on its platform.")
                .define("spawnOnPlatform", true);
        STARTING_TRAIN = b.comment("Place and assemble the spawn station's \"train\" template once per world, when the graph is built.")
                .define("startingTrain", true);
        GIRDER_TRACKS = b.comment("Generate track in Create's metal girder variant, as if placed with metal girders in the offhand:",
                        "curves carry girder supports, straight and diagonal track get metal girders under both rails.",
                        "Only affects newly generated chunks.")
                .define("girderTracks", true);

        b.comment("Vertical profile per biome (RAILWAYS.md A8.8). Changing these only affects new worlds",
                "(or /frostline rail layout rebuild).").push("profile");
        BIOME_RAISE = b.comment("How high the track runs above the ground, per biome: \"biome_id=amount\" or \"#biome_tag=amount\",",
                        "first match wins. amount >= 0: aim that many blocks above the ground (0 = hug it; more gives",
                        "embankments and bridges). -1: hug the ground, or with minusOneIsHighest hold the highest ground ahead.")
                .defineListAllowEmpty("biomeRaise", List.of("#frostline:rail/raised=5"), o -> o instanceof String s && s.contains("="));
        MINUS_ONE_IS_HIGHEST = b.comment("Make -1 in biomeRaise mean \"hold the highest ground of the next highestLookahead blocks\"",
                        "(the older, always-level profile) instead of hugging the ground.")
                .define("minusOneIsHighest", false);
        HIGHEST_LOOKAHEAD = b.comment("Blocks ahead the highest-ground profile looks.")
                .defineInRange("highestLookahead", 300, 16, 4096);
        HIGHEST_STRICTNESS = b.comment("How hard the highest-ground profile holds its level (cost weight; 1 = like ground hugging).")
                .defineInRange("highestStrictness", 4.0, 0.1, 100.0);
        b.pop();

        b.comment("Terrain along the track and tiled structures (styles' cut_cover / bridge_structures).").push("terrain");
        CLEAR_ABOVE_TRACK = b.comment("Outside tunnels, clear everything above the track bed up to the surface (trees, overhanging",
                        "leaves, cut walls over the track), and loose blocks (leaves, logs, plants, snow) beside it.")
                .define("clearAboveTrack", true);
        CLEAR_EXTRA_WIDTH = b.comment("Columns beyond the bed, each side, where loose blocks are cleared up to the surface.")
                .defineInRange("clearExtraWidth", 3, 0, 8);
        CUT_COVERS = b.comment("Place styles' cut_cover structures over cuts at least min_depth deep.")
                .define("cutCovers", true);
        BRIDGE_STRUCTURES = b.comment("Place styles' bridge_structures (start/middle/end, or flat) on bridges.")
                .define("bridgeStructures", true);
        TUNNEL_STRUCTURES = b.comment("Place styles' tunnel_structures (portals at tunnel mouths, optional middles inside).")
                .define("tunnelStructures", true);
        COVER_LAYER = b.comment("Place styles' cover_layer (e.g. snow) on the terrain the line leaves exposed.")
                .define("coverLayer", true);
        TRAIN_HALF_WIDTH = b.comment("Half width of the space trains drive through, from the track centre: nothing the line places",
                        "(templates, stations, additions, snow) goes within it. 2 = Create's 3 wide track plus one free block each side;",
                        "bridge decks reach one block further and railings stand outside it.")
                .defineInRange("trainHalfWidth", 2, 1, 6);
        TRAIN_HEIGHT = b.comment("Height of that space, counted from the track block up.")
                .defineInRange("trainHeight", 6, 3, 16);
        CLEAR_HEIGHT = b.comment("clearAboveTrack clears at least this many blocks above the track, or up to the surface where that is higher.")
                .defineInRange("clearHeight", 32, 0, 256);
        BRIDGE_FULL_MIN_LENGTH = b.comment("Shortest bridge that gets the full start/middle/end structure; shorter ones get flat tiles.",
                        "The style's min_length applies too, whichever is longer.")
                .defineInRange("bridgeFullMinLength", 48, 1, 100000);
        BRIDGE_FULL_MIN_MIDDLES = b.comment("A full bridge must fit at least this many middle templates between start and end.")
                .defineInRange("bridgeFullMinMiddles", 2, 1, 64);
        CURVE_MARGIN = b.comment("Rows before and after an S-bend or diagonal shift that are built as curve rows too: tunnel walls and bridge",
                        "decks widen and top_curve parts replace top there, so the widening starts before the curve and ends after it.")
                .defineInRange("curveMargin", 8, 0, 64);
        b.pop();

        b.pop();

        SPEC = b.build();
    }

    private RailwaysConfig() {
    }

    public static boolean railEnabled() {
        return get(RAIL_ENABLED::get, true);
    }

    public static String lineId() {
        return get(LINE_ID::get, "frostline:main");
    }

    public static boolean railPerfLogging() {
        return get(RAIL_PERF_LOGGING::get, false);
    }

    public static int graphEdgesPerTick() {
        return get(GRAPH_EDGES_PER_TICK::get, 32);
    }

    public static double graphMillisPerTick() {
        return get(GRAPH_MILLIS_PER_TICK::get, 2.0);
    }

    public static boolean reconcileOnChunkLoad() {
        return get(RECONCILE_ON_CHUNK_LOAD::get, true);
    }

    public static boolean spawnNearLine() {
        return get(SPAWN_NEAR_LINE::get, true);
    }

    public static int spawnZ() {
        return get(SPAWN_Z::get, -128);
    }

    public static int spawnOffsetX() {
        return get(SPAWN_OFFSET_X::get, 12);
    }

    public static boolean spawnOnPlatform() {
        return get(SPAWN_ON_PLATFORM::get, true);
    }

    public static boolean startingTrain() {
        return get(STARTING_TRAIN::get, true);
    }

    public static boolean girderTracks() {
        return get(GIRDER_TRACKS::get, true);
    }

    public static List<? extends String> biomeRaise() {
        return get(BIOME_RAISE::get, List.of("#frostline:rail/raised=5"));
    }

    public static boolean minusOneIsHighest() {
        return get(MINUS_ONE_IS_HIGHEST::get, false);
    }

    public static int highestLookahead() {
        return get(HIGHEST_LOOKAHEAD::get, 300);
    }

    public static double highestStrictness() {
        return get(HIGHEST_STRICTNESS::get, 4.0);
    }

    public static boolean clearAboveTrack() {
        return get(CLEAR_ABOVE_TRACK::get, true);
    }

    public static int clearExtraWidth() {
        return get(CLEAR_EXTRA_WIDTH::get, 3);
    }

    public static boolean cutCovers() {
        return get(CUT_COVERS::get, true);
    }

    public static boolean bridgeStructures() {
        return get(BRIDGE_STRUCTURES::get, true);
    }

    public static boolean tunnelStructures() {
        return get(TUNNEL_STRUCTURES::get, true);
    }

    public static boolean coverLayer() {
        return get(COVER_LAYER::get, true);
    }

    public static int trainHalfWidth() {
        return get(TRAIN_HALF_WIDTH::get, 2);
    }

    public static int trainHeight() {
        return get(TRAIN_HEIGHT::get, 6);
    }

    public static int clearHeight() {
        return get(CLEAR_HEIGHT::get, 32);
    }

    public static int bridgeFullMinLength() {
        return get(BRIDGE_FULL_MIN_LENGTH::get, 48);
    }

    public static int bridgeFullMinMiddles() {
        return get(BRIDGE_FULL_MIN_MIDDLES::get, 2);
    }

    public static int curveMargin() {
        return get(CURVE_MARGIN::get, 8);
    }

    private static <T> T get(Supplier<T> value, T fallback) {
        if (!SPEC.isLoaded()) {
            return fallback;
        }
        try {
            return value.get();
        } catch (IllegalStateException e) {
            return fallback;
        }
    }
}
