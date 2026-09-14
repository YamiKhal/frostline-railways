package com.yamikhal.frostlinerailways.rail;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;

/** Loads data/&lt;namespace&gt;/frostline_rail/line/*.json on every datapack (re)load. */
public final class RailLineLoader extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().create();
    private static volatile Map<ResourceLocation, RailLineDef> lines = Map.of();

    public RailLineLoader() {
        super(GSON, "frostline_rail/line");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> jsons, ResourceManager manager, ProfilerFiller profiler) {
        Map<ResourceLocation, RailLineDef> loaded = new HashMap<>();
        jsons.forEach((id, json) -> RailLineDef.CODEC.parse(JsonOps.INSTANCE, json)
                .resultOrPartial(error -> LOGGER.error("[FrostlineRailways] rail line {} is invalid: {}", id, error))
                .ifPresent(def -> loaded.put(id, def)));
        lines = Map.copyOf(loaded);
        LOGGER.info("[FrostlineRailways] rail lines loaded: {}", loaded.keySet());
    }

    public static RailLineDef get(ResourceLocation id) {
        return id == null ? null : lines.get(id);
    }
}
