package com.yamikhal.frostlinerailways.rail.sites;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yamikhal.frostlinerailways.rail.decor.BiomeFilter;
import com.yamikhal.frostlinerailways.rail.decor.RailStation;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;

/**
 * A station district: data/&lt;ns&gt;/frostline_rail/district/*.json (RAILWAYS.md §A8.15, docs/wiki/STRUCTURES.md).
 * What surrounds a station, on the station's side, behind its platform. The JSON is flat apart from core and outer.
 *
 * Every station rolls one district by {@code weight} among the files whose {@code stations} list is empty or names
 * the station's file id, and whose {@code biomes} / {@code exclude_biomes} allow the biome at the station. A district
 * without {@code core} is "nothing here" — the way to give stations a chance of no district. If the rolled
 * district's core cannot be placed, the station rolls again among the rest.
 *
 *   station  optional replacement for the station's building ({template, variants}, the station template format);
 *            the platform, Create stops and train stay the station's own
 *   core     the inner circle: one structure, {@code distance} blocks behind the station's back edge, its centre
 *            {@code along} blocks along the line from the station's middle (+ = north)
 *   outer    the outer circle: {@code count} more structures, each {@code distance} blocks from the core, at least
 *            {@code spacing} apart, on the station's side only
 */
public record RailDistrict(Match match, Optional<RailStation.Templates> station, Optional<Core> core, Optional<Outer> outer) {

    public record Match(List<ResourceLocation> stations, int weight, BiomeFilter biomes, BiomeFilter excludeBiomes) {
        static final MapCodec<Match> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                ResourceLocation.CODEC.listOf().optionalFieldOf("stations", List.of()).forGetter(Match::stations),
                Codec.intRange(0, 10_000).optionalFieldOf("weight", 1).forGetter(Match::weight),
                BiomeFilter.CODEC.optionalFieldOf("biomes", BiomeFilter.NONE).forGetter(Match::biomes),
                BiomeFilter.CODEC.optionalFieldOf("exclude_biomes", BiomeFilter.NONE).forGetter(Match::excludeBiomes)
        ).apply(i, Match::new));
    }

    public record Core(List<SiteEntry> structures, SiteRange distance, SiteRange along, int tries, SiteFit fit) {
        static final Codec<Core> CODEC = RecordCodecBuilder.create(i -> i.group(
                SiteEntry.CODEC.listOf().fieldOf("structures").forGetter(Core::structures),
                SiteRange.codec(0, 256).optionalFieldOf("distance", new SiteRange(4, 24)).forGetter(Core::distance),
                SiteRange.codec(-256, 256).optionalFieldOf("along", new SiteRange(-8, 8)).forGetter(Core::along),
                Codec.intRange(1, 64).optionalFieldOf("tries", 12).forGetter(Core::tries),
                SiteFit.CODEC.forGetter(Core::fit)
        ).apply(i, Core::new));
    }

    public record Outer(List<SiteEntry> structures, SiteRange count, SiteRange distance, int spacing, int tries, SiteFit fit) {
        static final Codec<Outer> CODEC = RecordCodecBuilder.create(i -> i.group(
                SiteEntry.CODEC.listOf().fieldOf("structures").forGetter(Outer::structures),
                SiteRange.codec(0, 64).optionalFieldOf("count", new SiteRange(2, 4)).forGetter(Outer::count),
                SiteRange.codec(0, 256).optionalFieldOf("distance", new SiteRange(16, 80)).forGetter(Outer::distance),
                Codec.intRange(0, 256).optionalFieldOf("spacing", 8).forGetter(Outer::spacing),
                Codec.intRange(1, 64).optionalFieldOf("tries", 8).forGetter(Outer::tries),
                SiteFit.CODEC.forGetter(Outer::fit)
        ).apply(i, Outer::new));
    }

    public static final Codec<RailDistrict> CODEC = RecordCodecBuilder.create(i -> i.group(
            Match.CODEC.forGetter(RailDistrict::match),
            RailStation.Templates.CODEC.optionalFieldOf("station").forGetter(RailDistrict::station),
            Core.CODEC.optionalFieldOf("core").forGetter(RailDistrict::core),
            Outer.CODEC.optionalFieldOf("outer").forGetter(RailDistrict::outer)
    ).apply(i, RailDistrict::new));
}
