package com.yamikhal.frostlinerailways.rail.decor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.yamikhal.frostlinerailways.rail.sites.RailDistrict;
import com.yamikhal.frostlinerailways.rail.sites.RailSite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Datapack loaders for the line's look: frostline_rail/style, frostline_rail/addition and
 * frostline_rail/station, and the structures along it: frostline_rail/site and frostline_rail/district
 * (RAILWAYS.md §A8.15). Entries are kept sorted by id, so every choice made from them is the same
 * on every run.
 */
public final class RailDecorData {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().create();

    public static final Loader<RailStyle> STYLES = new Loader<>("frostline_rail/style", RailStyle.CODEC);
    public static final Loader<RailAddition> ADDITIONS = new Loader<>("frostline_rail/addition", RailAddition.CODEC);
    public static final Loader<RailStation> STATIONS = new Loader<>("frostline_rail/station", RailStation.CODEC);
    public static final Loader<RailSite> SITES = new Loader<>("frostline_rail/site", RailSite.CODEC);
    public static final Loader<RailDistrict> DISTRICTS = new Loader<>("frostline_rail/district", RailDistrict.CODEC);

    private RailDecorData() {
    }

    public static void register(AddReloadListenerEvent event) {
        event.addListener(STYLES);
        event.addListener(ADDITIONS);
        event.addListener(STATIONS);
        event.addListener(SITES);
        event.addListener(DISTRICTS);
    }

    public record Entry<T>(ResourceLocation id, T value) {
    }

    public static final class Loader<T> extends SimpleJsonResourceReloadListener {
        private final String folder;
        private final Codec<T> codec;
        private volatile List<Entry<T>> entries = List.of();

        Loader(String folder, Codec<T> codec) {
            super(GSON, folder);
            this.folder = folder;
            this.codec = codec;
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> jsons, ResourceManager manager, ProfilerFiller profiler) {
            TreeMap<ResourceLocation, T> loaded = new TreeMap<>();
            jsons.forEach((id, json) -> codec.parse(JsonOps.INSTANCE, json)
                    .resultOrPartial(error -> LOGGER.error("[FrostlineRailways] {} {} is invalid: {}", folder, id, error))
                    .ifPresent(value -> loaded.put(id, value)));
            List<Entry<T>> list = new ArrayList<>();
            loaded.forEach((id, value) -> list.add(new Entry<>(id, value)));
            entries = List.copyOf(list);
            RailTemplates.clear();
            LOGGER.info("[FrostlineRailways] {}: {}", folder, loaded.keySet());
        }

        public List<Entry<T>> entries() {
            return entries;
        }
    }
}
