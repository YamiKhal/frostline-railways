package com.yamikhal.frostlinerailways.rail.decor;

import com.mojang.serialization.Codec;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A list of biome ids and #tags, as written in datapack JSON. Empty matches nothing on its own; callers treat an empty whitelist as "all". */
public final class BiomeFilter {

    public static final BiomeFilter NONE = new BiomeFilter(List.of());
    public static final Codec<BiomeFilter> CODEC = Codec.STRING.listOf().xmap(BiomeFilter::new, f -> f.entries);

    private final List<String> entries;
    private final List<TagKey<Biome>> tags = new ArrayList<>();
    private final Set<ResourceLocation> ids = new HashSet<>();

    BiomeFilter(List<String> entries) {
        this.entries = List.copyOf(entries);
        for (String entry : entries) {
            if (entry.startsWith("#")) {
                tags.add(TagKey.create(Registries.BIOME, new ResourceLocation(entry.substring(1))));
            } else {
                ids.add(new ResourceLocation(entry));
            }
        }
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public boolean matches(Holder<Biome> biome) {
        for (TagKey<Biome> tag : tags) {
            if (biome.is(tag)) {
                return true;
            }
        }
        return biome.unwrapKey().map(key -> ids.contains(key.location())).orElse(false);
    }

    /** Entries that name no registered biome or no existing biome tag (typos match nothing, silently). */
    public List<String> unknownIn(net.minecraft.core.Registry<Biome> registry) {
        List<String> unknown = new ArrayList<>();
        for (TagKey<Biome> tag : tags) {
            if (registry.getTag(tag).isEmpty()) {
                unknown.add("#" + tag.location());
            }
        }
        for (ResourceLocation id : ids) {
            if (!registry.containsKey(id)) {
                unknown.add(id.toString());
            }
        }
        return unknown;
    }

    /** Whitelist (empty = every biome) minus blacklist. */
    public static boolean allows(BiomeFilter whitelist, BiomeFilter blacklist, Holder<Biome> biome) {
        return (whitelist.isEmpty() || whitelist.matches(biome)) && !blacklist.matches(biome);
    }
}
