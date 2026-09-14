package com.yamikhal.frostlinerailways.rail.decor;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Optional;

/**
 * A station or halt along the line: data/&lt;ns&gt;/frostline_rail/station/*.json (RAILWAYS.md §A8.5).
 *
 * One is tried every {@code spacing} blocks, starting {@code firstAt} blocks north of the south end;
 * the first spot within half a spacing further north where the track is one straight of at least
 * {@code length} + 8 blocks, in the open or a cut (never a tunnel or bridge), in an allowed biome,
 * gets it (with {@code chance}).
 *
 * A platform {@code width} blocks wide runs {@code length} blocks along the track, {@code gap} blocks
 * from its centre, on {@code side} (east, west or alternate), level with the track, with air
 * {@code clearance} blocks above. The inner row uses {@code edge} when given. With
 * {@code stationBlock} a Create station is placed in the platform's inner row at its middle, already
 * pointed at the track in {@code direction} (north or south), so trains can stop there straight away.
 * {@code additions} are placed anchored at the station's middle on its side (their own side is ignored).
 */
public record RailStation(BiomeFilter biomes, BiomeFilter excludeBiomes, int spacing, int firstAt, float chance,
                          int length, int width, int gap, String side, int clearance,
                          BlockState platform, Optional<BlockState> edge, boolean stationBlock, String direction,
                          List<RailAddition> additions) {

    public static final Codec<RailStation> CODEC = RecordCodecBuilder.create(i -> i.group(
            BiomeFilter.CODEC.optionalFieldOf("biomes", BiomeFilter.NONE).forGetter(RailStation::biomes),
            BiomeFilter.CODEC.optionalFieldOf("exclude_biomes", BiomeFilter.NONE).forGetter(RailStation::excludeBiomes),
            Codec.intRange(256, 1_000_000).optionalFieldOf("spacing", 3000).forGetter(RailStation::spacing),
            Codec.intRange(0, 1_000_000).optionalFieldOf("first_at", 400).forGetter(RailStation::firstAt),
            Codec.floatRange(0, 1).optionalFieldOf("chance", 1.0F).forGetter(RailStation::chance),
            Codec.intRange(8, 128).optionalFieldOf("length", 32).forGetter(RailStation::length),
            Codec.intRange(1, 8).optionalFieldOf("width", 3).forGetter(RailStation::width),
            Codec.intRange(1, 8).optionalFieldOf("gap", 2).forGetter(RailStation::gap),
            Codec.STRING.optionalFieldOf("side", "alternate").forGetter(RailStation::side),
            Codec.intRange(2, 12).optionalFieldOf("clearance", 4).forGetter(RailStation::clearance),
            BlockState.CODEC.optionalFieldOf("platform", Blocks.STONE_BRICKS.defaultBlockState()).forGetter(RailStation::platform),
            BlockState.CODEC.optionalFieldOf("edge").forGetter(RailStation::edge),
            Codec.BOOL.optionalFieldOf("station_block", true).forGetter(RailStation::stationBlock),
            Codec.STRING.optionalFieldOf("direction", "north").forGetter(RailStation::direction),
            RailAddition.CODEC.listOf().optionalFieldOf("additions", List.of()).forGetter(RailStation::additions)
    ).apply(i, RailStation::new));
}
