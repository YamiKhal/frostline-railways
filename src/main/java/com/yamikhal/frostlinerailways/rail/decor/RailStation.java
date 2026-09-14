package com.yamikhal.frostlinerailways.rail.decor;

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
                BiomeFilter.CODEC.optionalFieldOf("biomes", BiomeFilter.NONE).forGetter(Where::biomes),
                BiomeFilter.CODEC.optionalFieldOf("exclude_biomes", BiomeFilter.NONE).forGetter(Where::excludeBiomes),
                Codec.intRange(256, 1_000_000).optionalFieldOf("spacing", 3000).forGetter(Where::spacing),
                Codec.intRange(0, 1_000_000).optionalFieldOf("first_at", 400).forGetter(Where::firstAt),
                Codec.floatRange(0, 1).optionalFieldOf("chance", 1.0F).forGetter(Where::chance),
                Codec.BOOL.optionalFieldOf("spawn", false).forGetter(Where::spawn),
                Codec.intRange(8, 128).optionalFieldOf("length", 32).forGetter(Where::length),
                Codec.STRING.optionalFieldOf("side", "alternate").forGetter(Where::side)
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
                           boolean clear) {
        static final Codec<Template> CODEC = RecordCodecBuilder.create(i -> i.group(
                ResourceLocation.CODEC.fieldOf("id").forGetter(Template::id),
                Codec.intRange(0, 256).fieldOf("track_z").forGetter(Template::trackZ),
                Codec.intRange(0, 64).optionalFieldOf("track_y", 1).forGetter(Template::trackY),
                BlockState.CODEC.optionalFieldOf("foundation").forGetter(Template::foundation),
                Codec.intRange(0, 64).optionalFieldOf("foundation_depth", 12).forGetter(Template::foundationDepth),
                Codec.BOOL.optionalFieldOf("clear", true).forGetter(Template::clear)
        ).apply(i, Template::new));
    }

    public record Look(int width, int gap, int clearance, BlockState platform, Optional<BlockState> edge,
                       Optional<Template> template) {
        static final MapCodec<Look> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.intRange(1, 8).optionalFieldOf("width", 3).forGetter(Look::width),
                Codec.intRange(1, 8).optionalFieldOf("gap", 2).forGetter(Look::gap),
                Codec.intRange(2, 12).optionalFieldOf("clearance", 4).forGetter(Look::clearance),
                BlockState.CODEC.optionalFieldOf("platform", Blocks.STONE_BRICKS.defaultBlockState()).forGetter(Look::platform),
                BlockState.CODEC.optionalFieldOf("edge").forGetter(Look::edge),
                Template.CODEC.optionalFieldOf("template").forGetter(Look::template)
        ).apply(i, Look::new));
    }

    public record Service(boolean stationBlock, String direction, String name, Optional<ResourceLocation> train) {
        static final MapCodec<Service> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.BOOL.optionalFieldOf("station_block", true).forGetter(Service::stationBlock),
                Codec.STRING.optionalFieldOf("direction", "both").forGetter(Service::direction),
                Codec.STRING.optionalFieldOf("name", "Station {n}").forGetter(Service::name),
                ResourceLocation.CODEC.optionalFieldOf("train").forGetter(Service::train)
        ).apply(i, Service::new));
    }

    public static final Codec<RailStation> CODEC = RecordCodecBuilder.create(i -> i.group(
            Where.CODEC.forGetter(RailStation::where),
            Look.CODEC.forGetter(RailStation::look),
            Service.CODEC.forGetter(RailStation::service),
            RailAddition.CODEC.listOf().optionalFieldOf("additions", List.of()).forGetter(RailStation::additions)
    ).apply(i, RailStation::new));

    public int length() {
        return where.length();
    }
}
