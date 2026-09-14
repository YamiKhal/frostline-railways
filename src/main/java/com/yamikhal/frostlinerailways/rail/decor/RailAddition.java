package com.yamikhal.frostlinerailways.rail.decor;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Optional;

/**
 * Something placed along the track: lamps, tunnel lights, buffers, debris, snow (RAILWAYS.md §A8.5).
 * Standalone in data/&lt;ns&gt;/frostline_rail/addition/*.json (every line), or inside a style's
 * "additions" (only where that style is used), or inside a station.
 *
 * Where: an anchor every {@code spacing} blocks along the line (z - phase divisible by spacing),
 * on rows whose kind is in {@code where} (open, cut, tunnel, bridge, portal; empty = any), on pieces
 * in {@code pieces} (straight, bend, ramp, shift), in allowed biomes, with {@code chance}.
 * "south_end" / "north_end" in {@code where} place it once at that end of the line instead.
 *
 * What: {@code blocks}, relative to the anchor. "at" is [outward, up, forward]: outward is away from
 * the track on the addition's side (mirrored for the west side), up is from the track's height plus
 * {@code height}, forward is towards the north. {@code side}: center, east, west or both, at
 * {@code offset} blocks from the track centre. {@code replace}: "air" (only into air and
 * replaceable blocks) or "any".
 *
 * Or: {@code scatter}, which instead rolls {@code chance} for every bed column within {@code reach}
 * of the track (never the track column) and puts one of its weighted states on top of the bed —
 * e.g. snow layers on the ballast.
 */
public record RailAddition(BiomeFilter biomes, BiomeFilter excludeBiomes, List<String> where, List<String> pieces,
                           int spacing, int phase, float chance, String side, int offset, int height,
                           List<Placement> blocks, String replace, Optional<Scatter> scatter) {

    public record Placement(List<Integer> at, BlockState state) {
        public static final Codec<Placement> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.listOf().optionalFieldOf("at", List.of(0, 0, 0)).forGetter(Placement::at),
                BlockState.CODEC.fieldOf("state").forGetter(Placement::state)
        ).apply(i, Placement::new));

        public int outward() {
            return at.size() > 0 ? at.get(0) : 0;
        }

        public int up() {
            return at.size() > 1 ? at.get(1) : 0;
        }

        public int forward() {
            return at.size() > 2 ? at.get(2) : 0;
        }
    }

    public record WeightedState(BlockState state, int weight) {
        public static final Codec<WeightedState> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockState.CODEC.fieldOf("state").forGetter(WeightedState::state),
                Codec.intRange(1, 10_000).optionalFieldOf("weight", 1).forGetter(WeightedState::weight)
        ).apply(i, WeightedState::new));
    }

    public record Scatter(float chance, int reach, List<WeightedState> states) {
        public static final Codec<Scatter> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.floatRange(0, 1).optionalFieldOf("chance", 0.25F).forGetter(Scatter::chance),
                Codec.intRange(1, 8).optionalFieldOf("reach", 2).forGetter(Scatter::reach),
                WeightedState.CODEC.listOf().fieldOf("states").forGetter(Scatter::states)
        ).apply(i, Scatter::new));
    }

    public static final Codec<RailAddition> CODEC = RecordCodecBuilder.create(i -> i.group(
            BiomeFilter.CODEC.optionalFieldOf("biomes", BiomeFilter.NONE).forGetter(RailAddition::biomes),
            BiomeFilter.CODEC.optionalFieldOf("exclude_biomes", BiomeFilter.NONE).forGetter(RailAddition::excludeBiomes),
            Codec.STRING.listOf().optionalFieldOf("where", List.of()).forGetter(RailAddition::where),
            Codec.STRING.listOf().optionalFieldOf("pieces", List.of("straight")).forGetter(RailAddition::pieces),
            Codec.intRange(1, 100_000).optionalFieldOf("spacing", 16).forGetter(RailAddition::spacing),
            Codec.INT.optionalFieldOf("phase", 0).forGetter(RailAddition::phase),
            Codec.floatRange(0, 1).optionalFieldOf("chance", 1.0F).forGetter(RailAddition::chance),
            Codec.STRING.optionalFieldOf("side", "center").forGetter(RailAddition::side),
            Codec.intRange(0, 32).optionalFieldOf("offset", 0).forGetter(RailAddition::offset),
            Codec.intRange(-32, 32).optionalFieldOf("height", 0).forGetter(RailAddition::height),
            Placement.CODEC.listOf().optionalFieldOf("blocks", List.of()).forGetter(RailAddition::blocks),
            Codec.STRING.optionalFieldOf("replace", "air").forGetter(RailAddition::replace),
            Scatter.CODEC.optionalFieldOf("scatter").forGetter(RailAddition::scatter)
    ).apply(i, RailAddition::new));

    /** Sides as x signs: east +1, west -1. */
    public int[] signs() {
        return switch (side) {
            case "both" -> new int[] {-1, 1};
            case "west", "left" -> new int[] {-1};
            case "east", "right" -> new int[] {1};
            default -> new int[] {1};
        };
    }

    public boolean isEnd() {
        return where.contains("south_end") || where.contains("north_end");
    }
}
