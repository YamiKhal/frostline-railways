package com.yamikhal.frostlinerailways.rail.sites;

import com.yamikhal.frostlinerailways.StrictFields;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yamikhal.frostlinerailways.rail.decor.BiomeFilter;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
import java.util.List;

/**
 * One structure a site or district may use (RAILWAYS.md §A8.15): an ordinary worldgen/structure id, its
 * {@code weight} among the list, and optional {@code biomes} / {@code exclude_biomes} tested where it would stand.
 * A bare id string is the same as {@code {"structure": id}}.
 */
public record SiteEntry(ResourceLocation structure, int weight, BiomeFilter biomes, BiomeFilter excludeBiomes) {

    private static final Codec<SiteEntry> FULL = StrictFields.record(i -> i.group(
            ResourceLocation.CODEC.fieldOf("structure").forGetter(SiteEntry::structure),
            StrictFields.optional(Codec.intRange(0, 10_000), "weight", 1).forGetter(SiteEntry::weight),
            StrictFields.optional(BiomeFilter.CODEC, "biomes", BiomeFilter.NONE).forGetter(SiteEntry::biomes),
            StrictFields.optional(BiomeFilter.CODEC, "exclude_biomes", BiomeFilter.NONE).forGetter(SiteEntry::excludeBiomes)
    ).apply(i, SiteEntry::new));

    public static final Codec<SiteEntry> CODEC = Codec.either(ResourceLocation.CODEC, FULL).xmap(
            either -> either.map(id -> new SiteEntry(id, 1, BiomeFilter.NONE, BiomeFilter.NONE), entry -> entry),
            Either::right);

    /** By weight among the entries allowed in this biome, for a uniform roll in [0, 1); null if none is allowed. */
    public static SiteEntry pick(List<SiteEntry> entries, Holder<Biome> biome, double roll) {
        List<SiteEntry> allowed = new ArrayList<>(entries.size());
        int total = 0;
        for (SiteEntry entry : entries) {
            if (entry.weight() > 0 && BiomeFilter.allows(entry.biomes(), entry.excludeBiomes(), biome)) {
                allowed.add(entry);
                total += entry.weight();
            }
        }
        if (allowed.isEmpty()) {
            return null;
        }
        double r = roll * total;
        for (SiteEntry entry : allowed) {
            r -= entry.weight();
            if (r < 0) {
                return entry;
            }
        }
        return allowed.get(allowed.size() - 1);
    }
}
