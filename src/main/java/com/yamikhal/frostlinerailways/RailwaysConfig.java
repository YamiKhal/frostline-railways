package com.yamikhal.frostlinerailways;

import net.minecraftforge.common.ForgeConfigSpec;

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
