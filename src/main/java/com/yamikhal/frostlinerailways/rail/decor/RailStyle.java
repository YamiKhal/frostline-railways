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
 * (RAILWAYS.md §A8.5, §A8.8, §A8.10). The line is cut into 256-block sections; each section takes the
 * matching styles for the biome at its middle (whitelist minus blacklist), keeps the highest priority, and
 * picks one of those by weight, seeded per section. Normal and lit variants are two styles with the same
 * biomes and weights; sub-variants are more of them.
 *
 *   bed                ballast under the track, fill under that, optional shoulder at the bed's edge
 *   bridge             deck under the track, piers, optional railing at the deck's edge
 *   tunnel             half width and height of the tunnel, optional lining (walls and ceiling) and
 *                      portal block (the ring at each tunnel mouth)
 *   cut_cover          optional tiled structure over cuts at least min_depth deep ({@link Tiles})
 *   bridge_structures  optional tiled structure on bridges, with a flat variant ({@link Tiles})
 *   tunnel_structures  optional tiled structure in tunnels: portals at both mouths, middles inside ({@link Tiles})
 *   cover_layer        optional block (e.g. a snow layer) put on top of the line's exposed terrain ({@link CoverLayer})
 *   additions          placed wherever this style is used (tunnel lights, lamp posts, ...)
 */
public record RailStyle(int priority, int weight, BiomeFilter biomes, BiomeFilter excludeBiomes,
                        Bed bed, Bridge bridge, Tunnel tunnel, Optional<Tiles> cutCover, Optional<Tiles> bridgeTiles,
                        Optional<Tiles> tunnelTiles, Optional<CoverLayer> coverLayer, List<RailAddition> additions) {

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
     * One set of templates for a tiled structure. Template ids name files in frostline_rail/template/; an id
     * also stands for every numbered file "&lt;id&gt;_1", "&lt;id&gt;_2", ...: each tile picks one of them at random
     * (seeded), so "tunnel/black_end" chooses among black_end_1, black_end_2, black_end_3.
     */
    public record Variant(int weight, Optional<ResourceLocation> start, Optional<ResourceLocation> middle,
                          Optional<ResourceLocation> end, Optional<ResourceLocation> flat) {
        static final Codec<Variant> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.intRange(0, 1_000_000).optionalFieldOf("weight", 1).forGetter(Variant::weight),
                ResourceLocation.CODEC.optionalFieldOf("start").forGetter(Variant::start),
                ResourceLocation.CODEC.optionalFieldOf("middle").forGetter(Variant::middle),
                ResourceLocation.CODEC.optionalFieldOf("end").forGetter(Variant::end),
                ResourceLocation.CODEC.optionalFieldOf("flat").forGetter(Variant::flat)
        ).apply(i, Variant::new));
    }

    /**
     * A structure tiled along a stretch from templates (StructurePlanner): {@code start} at the south end,
     * {@code middle} repeated, {@code end} at the north end, each with its x = 0 edge outward; for bridges,
     * {@code flat} tiles stretches that do not get the full set. The track runs through every template at
     * z = {@code track_z}, y = {@code track_y} (all templates of one structure share them).
     *
     * The templates given directly are the default variant with {@code weight}; {@code variants} are more
     * sets, each with its own weight: a stretch uses the default with chance weight / (weight + sum of variant
     * weights), and so on. A variant's missing parts come from the default.
     *
     *   min_depth         cut_cover: ground at least this far above the track
     *   min_height        bridge_structures: rows whose track is at least this far above the ground may get the
     *                     full set; lower bridge rows always get flat tiles
     *   min_length        shortest stretch that gets any tiles (bridges: the full set)
     *   max_length        bridge_structures: longest stretch that gets the full set (longer: flat)
     *   min_gap           bridge_structures: blocks between the previous full bridge and the next
     *   merge_gap         rows of another kind (or none) a stretch may bridge over and still count as one
     *   foundation, foundation_depth   fill under template columns standing on the template floor (y = 0)
     *                     down to the ground: piers
     */
    public record Tiles(int weight, Optional<ResourceLocation> start, Optional<ResourceLocation> middle, Optional<ResourceLocation> end,
                        Optional<ResourceLocation> flat, int trackZ, int trackY, int minDepth, int minHeight, int minLength,
                        int maxLength, int minGap, int mergeGap, Optional<BlockState> foundation, int foundationDepth,
                        List<Variant> variants) {
        static final Codec<Tiles> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.intRange(0, 1_000_000).optionalFieldOf("weight", 1).forGetter(Tiles::weight),
                ResourceLocation.CODEC.optionalFieldOf("start").forGetter(Tiles::start),
                ResourceLocation.CODEC.optionalFieldOf("middle").forGetter(Tiles::middle),
                ResourceLocation.CODEC.optionalFieldOf("end").forGetter(Tiles::end),
                ResourceLocation.CODEC.optionalFieldOf("flat").forGetter(Tiles::flat),
                Codec.intRange(0, 256).optionalFieldOf("track_z", 4).forGetter(Tiles::trackZ),
                Codec.intRange(0, 64).optionalFieldOf("track_y", 1).forGetter(Tiles::trackY),
                Codec.intRange(1, 256).optionalFieldOf("min_depth", 5).forGetter(Tiles::minDepth),
                Codec.intRange(1, 256).optionalFieldOf("min_height", 6).forGetter(Tiles::minHeight),
                Codec.intRange(1, 100_000).optionalFieldOf("min_length", 12).forGetter(Tiles::minLength),
                Codec.intRange(1, 100_000).optionalFieldOf("max_length", 160).forGetter(Tiles::maxLength),
                Codec.intRange(0, 100_000).optionalFieldOf("min_gap", 96).forGetter(Tiles::minGap),
                Codec.intRange(0, 256).optionalFieldOf("merge_gap", 4).forGetter(Tiles::mergeGap),
                BlockState.CODEC.optionalFieldOf("foundation").forGetter(Tiles::foundation),
                Codec.intRange(0, 256).optionalFieldOf("foundation_depth", 64).forGetter(Tiles::foundationDepth),
                Variant.CODEC.listOf().optionalFieldOf("variants", List.of()).forGetter(Tiles::variants)
        ).apply(i, Tiles::new));

        /** The variant for a uniform roll in [0, 1), missing parts filled from the default. */
        public Variant pick(double roll) {
            Variant base = new Variant(weight, start, middle, end, flat);
            int total = Math.max(0, weight);
            for (Variant v : variants) {
                total += Math.max(0, v.weight());
            }
            if (total <= 0) {
                return base;
            }
            double r = roll * total - Math.max(0, weight);
            if (r < 0) {
                return base;
            }
            for (Variant v : variants) {
                r -= Math.max(0, v.weight());
                if (r < 0) {
                    return new Variant(v.weight(), v.start().or(() -> start), v.middle().or(() -> middle),
                            v.end().or(() -> end), v.flat().or(() -> flat));
                }
            }
            return base;
        }
    }

    /**
     * A block put on top of the terrain the line leaves exposed (RAILWAYS.md §A8.10): every column within
     * {@code reach} of the track whose top block is solid on top and has air above, with {@code chance}, in
     * allowed biomes — ballast, berms, cut slopes, even inside the train's space — but not in tunnels, on bridge
     * decks, under tiled structures or at stations.
     */
    public record CoverLayer(BlockState state, float chance, int reach, BiomeFilter biomes, BiomeFilter excludeBiomes) {
        static final Codec<CoverLayer> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockState.CODEC.fieldOf("state").forGetter(CoverLayer::state),
                Codec.floatRange(0, 1).optionalFieldOf("chance", 1.0F).forGetter(CoverLayer::chance),
                Codec.intRange(0, 24).optionalFieldOf("reach", 12).forGetter(CoverLayer::reach),
                BiomeFilter.CODEC.optionalFieldOf("biomes", BiomeFilter.NONE).forGetter(CoverLayer::biomes),
                BiomeFilter.CODEC.optionalFieldOf("exclude_biomes", BiomeFilter.NONE).forGetter(CoverLayer::excludeBiomes)
        ).apply(i, CoverLayer::new));
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
            Tiles.CODEC.optionalFieldOf("tunnel_structures").forGetter(RailStyle::tunnelTiles),
            CoverLayer.CODEC.optionalFieldOf("cover_layer").forGetter(RailStyle::coverLayer),
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
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), List.of());
    }
}
