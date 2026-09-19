package com.yamikhal.frostlinerailways.rail.sites;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yamikhal.frostlinerailways.rail.decor.BiomeFilter;

import java.util.List;

/**
 * A line site: data/&lt;ns&gt;/frostline_rail/site/*.json (RAILWAYS.md §A8.15, docs/wiki/STRUCTURES.md). Structures
 * placed beside the track, close enough to be seen from a train. The JSON is flat; the record groups its fields.
 *
 * Where ({@link Where}): a slot every {@code spacing} blocks from {@code first_at} north of the south end, taken
 * with {@code chance}, at most {@code max_count} along the line (-1 = no limit). Per slot, up to {@code tries}
 * spots stepping north through half a spacing; the first that passes every check gets the structure. {@code side}:
 * east, west, alternate, random or both (one each side). {@code distance}: blocks from the track centre to the
 * structure's nearest block. {@code rows}: the kinds of track the structure may stand beside (open, cut, bridge,
 * tunnel). Keeps {@code min_separation} from every other rail site and {@code station_margin} (along the line) from
 * stations. {@code biomes} / {@code exclude_biomes}: at the structure's centre.
 */
public record RailSite(List<SiteEntry> structures, Where where, SiteFit fit) {

    public record Where(BiomeFilter biomes, BiomeFilter excludeBiomes, int spacing, int firstAt, float chance, int maxCount,
                        String side, SiteRange distance, List<String> rows, int minSeparation, int stationMargin, int tries) {
        static final MapCodec<Where> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                BiomeFilter.CODEC.optionalFieldOf("biomes", BiomeFilter.NONE).forGetter(Where::biomes),
                BiomeFilter.CODEC.optionalFieldOf("exclude_biomes", BiomeFilter.NONE).forGetter(Where::excludeBiomes),
                Codec.intRange(64, 1_000_000).optionalFieldOf("spacing", 800).forGetter(Where::spacing),
                Codec.intRange(0, 1_000_000).optionalFieldOf("first_at", 400).forGetter(Where::firstAt),
                Codec.floatRange(0, 1).optionalFieldOf("chance", 1.0F).forGetter(Where::chance),
                Codec.intRange(-1, 1_000_000).optionalFieldOf("max_count", -1).forGetter(Where::maxCount),
                Codec.STRING.optionalFieldOf("side", "random").forGetter(Where::side),
                SiteRange.codec(0, 512).optionalFieldOf("distance", new SiteRange(24, 64)).forGetter(Where::distance),
                Codec.STRING.listOf().optionalFieldOf("rows", List.of("open", "bridge")).forGetter(Where::rows),
                Codec.intRange(0, 4096).optionalFieldOf("min_separation", 96).forGetter(Where::minSeparation),
                Codec.intRange(0, 4096).optionalFieldOf("station_margin", 64).forGetter(Where::stationMargin),
                Codec.intRange(1, 64).optionalFieldOf("tries", 8).forGetter(Where::tries)
        ).apply(i, Where::new));
    }

    public static final Codec<RailSite> CODEC = RecordCodecBuilder.create(i -> i.group(
            SiteEntry.CODEC.listOf().fieldOf("structures").forGetter(RailSite::structures),
            Where.CODEC.forGetter(RailSite::where),
            SiteFit.CODEC.forGetter(RailSite::fit)
    ).apply(i, RailSite::new));
}
