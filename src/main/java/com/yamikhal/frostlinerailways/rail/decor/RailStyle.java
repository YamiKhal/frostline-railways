package com.yamikhal.frostlinerailways.rail.decor;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yamikhal.frostlinerailways.rail.RailLineDef;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Optional;

/**
 * How the line looks in a stretch of the world: data/&lt;ns&gt;/frostline_rail/style/*.json
 * (RAILWAYS.md §A8.5, §A8.8). The line is cut into 256-block sections; each section takes the matching
 * styles for the biome at its middle (whitelist minus blacklist), keeps the highest priority, and picks
 * one of those by weight, seeded per section. Normal and lit variants are two styles with the same
 * biomes and weights; sub-variants are more of them.
 *
 *   bed                ballast under the track, fill under that, optional shoulder at the bed's edge
 *   bridge             deck under the track, piers, optional railing at the deck's edge
 *   tunnel             half width and height of the tunnel, optional lining (walls and ceiling) and
 *                      portal block (the ring at each tunnel mouth)
 *   cut_cover          optional tiled structure over cuts at least min_depth deep ({@link Tiles})
 *   bridge_structures  optional tiled structure on bridges, with a flat variant ({@link Tiles})
 *   additions          placed wherever this style is used (tunnel lights, lamp posts, ...)
 */
public record RailStyle(int priority, int weight, BiomeFilter biomes, BiomeFilter excludeBiomes,
                        Bed bed, Bridge bridge, Tunnel tunnel, Optional<Tiles> cutCover, Optional<Tiles> bridgeTiles,
                        List<RailAddition> additions) {

    public record Bed(BlockState ballast, BlockState fill, Optional<BlockState> shoulder) {
        static final Codec<Bed> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockState.CODEC.optionalFieldOf("ballast", Blocks.GRAVEL.defaultBlockState()).forGetter(Bed::ballast),
                BlockState.CODEC.optionalFieldOf("fill", Blocks.COBBLESTONE.defaultBlockState()).forGetter(Bed::fill),
                BlockState.CODEC.optionalFieldOf("shoulder").forGetter(Bed::shoulder)
        ).apply(i, Bed::new));
    }

    public record Bridge(BlockState deck, BlockState pier, Optional<BlockState> railing) {
        static final Codec<Bridge> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockState.CODEC.optionalFieldOf("deck", Blocks.STONE_BRICKS.defaultBlockState()).forGetter(Bridge::deck),
                BlockState.CODEC.optionalFieldOf("pier", Blocks.STONE_BRICKS.defaultBlockState()).forGetter(Bridge::pier),
                BlockState.CODEC.optionalFieldOf("railing").forGetter(Bridge::railing)
        ).apply(i, Bridge::new));
    }

    public record Tunnel(int halfWidth, int height, Optional<BlockState> lining, Optional<BlockState> portal) {
        static final Codec<Tunnel> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.intRange(1, 8).optionalFieldOf("half_width", 2).forGetter(Tunnel::halfWidth),
                Codec.intRange(3, 16).optionalFieldOf("height", 5).forGetter(Tunnel::height),
                BlockState.CODEC.optionalFieldOf("lining").forGetter(Tunnel::lining),
                BlockState.CODEC.optionalFieldOf("portal").forGetter(Tunnel::portal)
        ).apply(i, Tunnel::new));
    }

    /**
     * A structure tiled along a stretch from templates in frostline_rail/template/ (StructurePlanner):
     * {@code start} at the south end, {@code middle} repeated, {@code end} at the north end, each with its
     * x = 0 edge outward; for bridges, {@code flat} tiles stretches that do not get the full set. The
     * track runs through every template at z = {@code track_z}, y = {@code track_y}.
     *
     *   min_depth         cut_cover: ground at least this far above the track
     *   min_length        shortest stretch that gets the full set
     *   max_length        bridge_structures: longest stretch that gets the full set (longer: flat)
     *   min_gap           bridge_structures: blocks between the previous full bridge and the next
     *   foundation, foundation_depth   fill under template columns standing on the template floor (y = 0)
     *                     down to the ground: piers
     */
    public record Tiles(Optional<ResourceLocation> start, Optional<ResourceLocation> middle, Optional<ResourceLocation> end,
                        Optional<ResourceLocation> flat, int trackZ, int trackY, int minDepth, int minLength, int maxLength,
                        int minGap, Optional<BlockState> foundation, int foundationDepth) {
        static final Codec<Tiles> CODEC = RecordCodecBuilder.create(i -> i.group(
                ResourceLocation.CODEC.optionalFieldOf("start").forGetter(Tiles::start),
                ResourceLocation.CODEC.optionalFieldOf("middle").forGetter(Tiles::middle),
                ResourceLocation.CODEC.optionalFieldOf("end").forGetter(Tiles::end),
                ResourceLocation.CODEC.optionalFieldOf("flat").forGetter(Tiles::flat),
                Codec.intRange(0, 256).optionalFieldOf("track_z", 4).forGetter(Tiles::trackZ),
                Codec.intRange(0, 64).optionalFieldOf("track_y", 1).forGetter(Tiles::trackY),
                Codec.intRange(1, 256).optionalFieldOf("min_depth", 5).forGetter(Tiles::minDepth),
                Codec.intRange(1, 100_000).optionalFieldOf("min_length", 12).forGetter(Tiles::minLength),
                Codec.intRange(1, 100_000).optionalFieldOf("max_length", 160).forGetter(Tiles::maxLength),
                Codec.intRange(0, 100_000).optionalFieldOf("min_gap", 96).forGetter(Tiles::minGap),
                BlockState.CODEC.optionalFieldOf("foundation").forGetter(Tiles::foundation),
                Codec.intRange(0, 256).optionalFieldOf("foundation_depth", 64).forGetter(Tiles::foundationDepth)
        ).apply(i, Tiles::new));
    }

    private static final Bed DEFAULT_BED = new Bed(Blocks.GRAVEL.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(), Optional.empty());
    private static final Bridge DEFAULT_BRIDGE = new Bridge(Blocks.STONE_BRICKS.defaultBlockState(), Blocks.STONE_BRICKS.defaultBlockState(), Optional.empty());
    private static final Tunnel DEFAULT_TUNNEL = new Tunnel(2, 5, Optional.empty(), Optional.empty());

    public static final Codec<RailStyle> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("priority", 0).forGetter(RailStyle::priority),
            Codec.intRange(1, 10_000).optionalFieldOf("weight", 1).forGetter(RailStyle::weight),
            BiomeFilter.CODEC.optionalFieldOf("biomes", BiomeFilter.NONE).forGetter(RailStyle::biomes),
            BiomeFilter.CODEC.optionalFieldOf("exclude_biomes", BiomeFilter.NONE).forGetter(RailStyle::excludeBiomes),
            Bed.CODEC.optionalFieldOf("bed", DEFAULT_BED).forGetter(RailStyle::bed),
            Bridge.CODEC.optionalFieldOf("bridge", DEFAULT_BRIDGE).forGetter(RailStyle::bridge),
            Tunnel.CODEC.optionalFieldOf("tunnel", DEFAULT_TUNNEL).forGetter(RailStyle::tunnel),
            Tiles.CODEC.optionalFieldOf("cut_cover").forGetter(RailStyle::cutCover),
            Tiles.CODEC.optionalFieldOf("bridge_structures").forGetter(RailStyle::bridgeTiles),
            RailAddition.CODEC.listOf().optionalFieldOf("additions", List.of()).forGetter(RailStyle::additions)
    ).apply(i, RailStyle::new));

    /** The style used where no datapack style matches: the line definition's own blocks. */
    public static RailStyle fromLine(RailLineDef def) {
        BlockState ballast = BuiltInRegistries.BLOCK.get(def.blocks().ballast()).defaultBlockState();
        BlockState fill = BuiltInRegistries.BLOCK.get(def.blocks().fill()).defaultBlockState();
        BlockState pier = BuiltInRegistries.BLOCK.get(def.blocks().pier()).defaultBlockState();
        return new RailStyle(Integer.MIN_VALUE, 1, BiomeFilter.NONE, BiomeFilter.NONE,
                new Bed(ballast, fill, Optional.empty()), new Bridge(pier, pier, Optional.empty()),
                new Tunnel(def.bed().halfWidth(), def.bed().clearance(), Optional.empty(), Optional.empty()),
                Optional.empty(), Optional.empty(), List.of());
    }
}
