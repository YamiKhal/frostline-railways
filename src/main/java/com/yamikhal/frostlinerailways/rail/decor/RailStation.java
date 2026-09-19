package com.yamikhal.frostlinerailways.rail.decor;

import com.yamikhal.frostlinerailways.StrictFields;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Optional;

/**
 * A station or halt along the line: data/&lt;ns&gt;/frostline_rail/station/*.json (RAILWAYS.md §A8.5, §A8.6).
 * The JSON is flat; the record groups its fields.
 *
 * Where ({@link Where}): one is tried every {@code spacing} blocks, starting {@code first_at} blocks north
 * of the south end; the first spot within half a spacing further north where the track is one straight
 * of at least {@code length} + 8 blocks, in the open or a cut (never a tunnel or bridge), in an allowed
 * biome, gets it (with {@code chance}). With {@code spawn} there is exactly one, the flattest good spot
 * near the config's spawnZ, and new worlds spawn at it. Sites keep 64 blocks apart; spawn stations are
 * placed first.
 *
 * Look ({@link Look}): either a {@code template} structure, or a platform {@code width} blocks wide,
 * {@code gap} blocks from the track centre, level with the track, {@code clearance} air above,
 * {@code edge} on its inner row. {@code side}: east, west or alternate.
 *
 * Service ({@link Service}): with {@code station_block}, Create stations {@code gap} blocks from the
 * track: for {@code direction} "north" one at the north end that northbound trains stop at, "south" one
 * at the south end for southbound trains, "both" (default) both. Create names them from {@code name}
 * ({n} = the station's number) once they are bound. {@code train}: a train template placed and
 * assembled at the northbound stop once per world.
 *
 * {@code additions} are placed anchored at the station's middle on its side (their own side is ignored).
 */
public record RailStation(Where where, Look look, Service service, List<RailAddition> additions) {

    public record Where(BiomeFilter biomes, BiomeFilter excludeBiomes, int spacing, int firstAt, float chance,
                        boolean spawn, int length, String side) {
        static final MapCodec<Where> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                StrictFields.optional(BiomeFilter.CODEC, "biomes", BiomeFilter.NONE).forGetter(Where::biomes),
                StrictFields.optional(BiomeFilter.CODEC, "exclude_biomes", BiomeFilter.NONE).forGetter(Where::excludeBiomes),
                StrictFields.optional(Codec.intRange(256, 1_000_000), "spacing", 3000).forGetter(Where::spacing),
                StrictFields.optional(Codec.intRange(0, 1_000_000), "first_at", 400).forGetter(Where::firstAt),
                StrictFields.optional(Codec.floatRange(0, 1), "chance", 1.0F).forGetter(Where::chance),
                StrictFields.optional(Codec.BOOL, "spawn", false).forGetter(Where::spawn),
                StrictFields.optional(Codec.intRange(8, 128), "length", 32).forGetter(Where::length),
                StrictFields.optional(StrictFields.oneOf("east", "west", "alternate", "left", "right"), "side", "alternate").forGetter(Where::side)
        ).apply(i, Where::new));
    }

    /**
     * A structure in data/&lt;ns&gt;/frostline_rail/template/&lt;path&gt;.nbt (vanilla structure format). Its x axis
     * runs along the track from the station's north end; its track is the row z = {@code track_z},
     * y = {@code track_y}; lower z is outward on the station's side. Its own track blocks are never placed.
     * Columns get {@code foundation} below their lowest block, down to the ground (at most
     * {@code foundation_depth}). Set the station's {@code length} to the template's x size. With {@code clear}
     * (default true) template positions at or above track height that hold nothing become air; air saved
     * in the template always does.
     */
    public record Template(ResourceLocation id, int trackZ, int trackY, Optional<BlockState> foundation, int foundationDepth,
                           boolean clear, int weight) {
        public static final Codec<Template> CODEC = StrictFields.record(i -> i.group(
                ResourceLocation.CODEC.fieldOf("id").forGetter(Template::id),
                Codec.intRange(0, 256).fieldOf("track_z").forGetter(Template::trackZ),
                StrictFields.optional(Codec.intRange(0, 64), "track_y", 1).forGetter(Template::trackY),
                StrictFields.optional(BlockState.CODEC, "foundation").forGetter(Template::foundation),
                StrictFields.optional(Codec.intRange(0, 64), "foundation_depth", 12).forGetter(Template::foundationDepth),
                StrictFields.optional(Codec.BOOL, "clear", true).forGetter(Template::clear),
                StrictFields.optional(Codec.intRange(0, 1_000_000), "weight", 1).forGetter(Template::weight)
        ).apply(i, Template::new));
    }

    /**
     * Platform or building. {@code template} and each of {@code variants} (more templates, same shape as
     * template, all as long as the station) are picked per station by weight; a template id also stands for its
     * numbered files "&lt;id&gt;_1", "&lt;id&gt;_2", ... (RAILWAYS.md §A8.10).
     */
    public record Look(int width, int gap, int clearance, BlockState platform, Optional<BlockState> edge,
                       Optional<Template> template, List<Template> variants) {
        static final MapCodec<Look> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                StrictFields.optional(Codec.intRange(1, 8), "width", 3).forGetter(Look::width),
                StrictFields.optional(Codec.intRange(1, 8), "gap", 2).forGetter(Look::gap),
                StrictFields.optional(Codec.intRange(2, 12), "clearance", 4).forGetter(Look::clearance),
                StrictFields.optional(BlockState.CODEC, "platform", Blocks.STONE_BRICKS.defaultBlockState()).forGetter(Look::platform),
                StrictFields.optional(BlockState.CODEC, "edge").forGetter(Look::edge),
                StrictFields.optional(Template.CODEC, "template").forGetter(Look::template),
                StrictFields.optional(Template.CODEC.listOf(), "variants", List.of()).forGetter(Look::variants)
        ).apply(i, Look::new));

        /** The template for a uniform roll in [0, 1), by weight among template and variants; empty for a plain platform. */
        public Optional<Template> pick(double roll) {
            return RailStation.pick(template, variants, roll);
        }
    }

    /**
     * Just the building part of a look: {@code template} and {@code variants}. A station district's {@code station}
     * field (RAILWAYS.md §A8.15) replaces the station's own building with one of these.
     */
    public record Templates(Optional<Template> template, List<Template> variants) {
        public static final Codec<Templates> CODEC = StrictFields.record(i -> i.group(
                StrictFields.optional(Template.CODEC, "template").forGetter(Templates::template),
                StrictFields.optional(Template.CODEC.listOf(), "variants", List.of()).forGetter(Templates::variants)
        ).apply(i, Templates::new));

        public Optional<Template> pick(double roll) {
            return RailStation.pick(template, variants, roll);
        }

        public boolean isEmpty() {
            return template.isEmpty() && variants.isEmpty();
        }
    }

    /** By weight among template and variants for a uniform roll in [0, 1); empty when there is no template at all. */
    public static Optional<Template> pick(Optional<Template> template, List<Template> variants, double roll) {
        List<Template> all = new java.util.ArrayList<>();
        template.ifPresent(all::add);
        all.addAll(variants);
        int total = all.stream().mapToInt(t -> Math.max(0, t.weight())).sum();
        if (all.isEmpty() || total <= 0) {
            return template;
        }
        double r = roll * total;
        for (Template t : all) {
            r -= Math.max(0, t.weight());
            if (r < 0) {
                return Optional.of(t);
            }
        }
        return Optional.of(all.get(all.size() - 1));
    }

    public record Service(boolean stationBlock, String direction, String name, Optional<ResourceLocation> train) {
        static final MapCodec<Service> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                StrictFields.optional(Codec.BOOL, "station_block", true).forGetter(Service::stationBlock),
                StrictFields.optional(StrictFields.oneOf("north", "south", "both"), "direction", "both").forGetter(Service::direction),
                StrictFields.optional(Codec.STRING, "name", "Station {n}").forGetter(Service::name),
                StrictFields.optional(ResourceLocation.CODEC, "train").forGetter(Service::train)
        ).apply(i, Service::new));
    }

    public static final Codec<RailStation> CODEC = StrictFields.record(i -> i.group(
            Where.CODEC.forGetter(RailStation::where),
            Look.CODEC.forGetter(RailStation::look),
            Service.CODEC.forGetter(RailStation::service),
            StrictFields.optional(RailAddition.CODEC.listOf(), "additions", List.of()).forGetter(RailStation::additions)
    ).apply(i, RailStation::new));

    public int length() {
        return where.length();
    }
}
