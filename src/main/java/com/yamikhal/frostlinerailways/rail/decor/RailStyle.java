package com.yamikhal.frostlinerailways.rail.decor;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
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
 * (RAILWAYS.md §A8.5, §A8.8, §A8.10, §A8.11). The line is cut into 256-block sections; each section takes the
 * matching styles for the biome at its middle (whitelist minus blacklist), keeps the highest priority, and
 * picks one of those by weight, seeded per section. Normal and lit variants are two styles with the same
 * biomes and weights; sub-variants are more of them.
 *
 *   bed                ballast under the track, fill under that, optional shoulder at the bed's edge
 *   bridge             deck under the track, piers, optional railing at the deck's edge
 *   tunnel             half width and height of the tunnel, optional lining (walls and ceiling) and
 *                      portal block (the ring at each tunnel mouth)
 *   cut_cover          optional structure over cuts at least min_depth deep ({@link Tiles})
 *   bridge_structures  optional structure on bridges ({@link Tiles})
 *   tunnel_structures  optional structure in tunnels: portals at both mouths, middles inside ({@link Tiles})
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
     * One set of templates for a structure (a variant). Every id also stands for its numbered variations
     * (RailTemplates#pick).
     *
     *   start, end      whole templates at the stretch's south and north ends (x = 0 outward)
     *   middle          whole templates repeated on the straight parts of the stretch
     *   flat            bridges: whole templates tiling straight parts that do not get start/middle/end
     *   top, top_curve  per-row part that follows the track row by row, so it curves cleanly: bridges' deck and
     *                   railing on every row; covers' roof on every row start/end/middle do not take. top_curve
     *                   (usually wider) on S-bends and diagonal shifts, else top. Row n of a stretch (from its
     *                   south end) uses the template's x = n mod length; the track is at the template's middle z,
     *                   y = top_track_y.
     */
    public record Parts(int weight, Optional<ResourceLocation> start, Optional<ResourceLocation> middle, Optional<ResourceLocation> end,
                        Optional<ResourceLocation> flat, Optional<ResourceLocation> top, Optional<ResourceLocation> topCurve) {
        static final MapCodec<Parts> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.intRange(0, 1_000_000).optionalFieldOf("weight", 1).forGetter(Parts::weight),
                ResourceLocation.CODEC.optionalFieldOf("start").forGetter(Parts::start),
                ResourceLocation.CODEC.optionalFieldOf("middle").forGetter(Parts::middle),
                ResourceLocation.CODEC.optionalFieldOf("end").forGetter(Parts::end),
                ResourceLocation.CODEC.optionalFieldOf("flat").forGetter(Parts::flat),
                ResourceLocation.CODEC.optionalFieldOf("top").forGetter(Parts::top),
                ResourceLocation.CODEC.optionalFieldOf("top_curve").forGetter(Parts::topCurve)
        ).apply(i, Parts::new));
        static final Codec<Parts> CODEC = MAP_CODEC.codec();

        Parts orElse(Parts base) {
            return new Parts(weight, start.or(base::start), middle.or(base::middle), end.or(base::end), flat.or(base::flat),
                    top.or(base::top), topCurve.or(base::topCurve));
        }
    }

    /**
     *   track_z, track_y  where the track runs through start/middle/end/flat templates
     *   top_track_y       where the track runs through top/top_curve templates (z: their middle)
     *   min_depth         cut_cover: ground at least this far above the track
     *   min_height        bridge_structures: rows whose track is at least this far above the ground may get
     *                     start/middle/end; lower bridge rows only get flat and top
     *   min_length        shortest stretch that gets start/end (bridges: the full set)
     *   max_length        bridge_structures: longest stretch that gets the full set
     *   min_gap           bridge_structures: blocks between the previous full bridge and the next
     *   merge_gap         rows of another kind (or none) a stretch may bridge over and still count as one
     *   foundation, foundation_depth   fill under whole-template columns standing on the template floor (y = 0)
     *                     down to the ground: piers
     */
    public record Params(int trackZ, int trackY, int topTrackY, int minDepth, int minHeight, int minLength, int maxLength,
                         int minGap, int mergeGap, Optional<BlockState> foundation, int foundationDepth) {
        static final MapCodec<Params> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.intRange(0, 256).optionalFieldOf("track_z", 4).forGetter(Params::trackZ),
                Codec.intRange(0, 64).optionalFieldOf("track_y", 1).forGetter(Params::trackY),
                Codec.intRange(0, 64).optionalFieldOf("top_track_y", 1).forGetter(Params::topTrackY),
                Codec.intRange(1, 256).optionalFieldOf("min_depth", 5).forGetter(Params::minDepth),
                Codec.intRange(1, 256).optionalFieldOf("min_height", 6).forGetter(Params::minHeight),
                Codec.intRange(1, 100_000).optionalFieldOf("min_length", 12).forGetter(Params::minLength),
                Codec.intRange(1, 100_000).optionalFieldOf("max_length", 160).forGetter(Params::maxLength),
                Codec.intRange(0, 100_000).optionalFieldOf("min_gap", 96).forGetter(Params::minGap),
                Codec.intRange(0, 256).optionalFieldOf("merge_gap", 4).forGetter(Params::mergeGap),
                BlockState.CODEC.optionalFieldOf("foundation").forGetter(Params::foundation),
                Codec.intRange(0, 256).optionalFieldOf("foundation_depth", 64).forGetter(Params::foundationDepth)
        ).apply(i, Params::new));
    }

    /**
     * A structure along stretches of the line (StructurePlanner). The JSON is flat: the default {@link Parts}
     * (with its "weight"), the {@link Params}, and "variants": more Parts, each with its own weight. A stretch
     * uses the default with chance weight / (weight + all variant weights), and so on; a variant's missing parts
     * come from the default.
     */
    public record Tiles(Parts parts, Params params, List<Parts> variants) {
        static final Codec<Tiles> CODEC = RecordCodecBuilder.create(i -> i.group(
                Parts.MAP_CODEC.forGetter(Tiles::parts),
                Params.MAP_CODEC.forGetter(Tiles::params),
                Parts.CODEC.listOf().optionalFieldOf("variants", List.of()).forGetter(Tiles::variants)
        ).apply(i, Tiles::new));

        /** The variant for a uniform roll in [0, 1), missing parts filled from the default. */
        public Parts pick(double roll) {
            int total = Math.max(0, parts.weight());
            for (Parts v : variants) {
                total += Math.max(0, v.weight());
            }
            double r = roll * total - Math.max(0, parts.weight());
            if (total <= 0 || r < 0) {
                return parts;
            }
            for (Parts v : variants) {
                r -= Math.max(0, v.weight());
                if (r < 0) {
                    return v.orElse(parts);
                }
            }
            return parts;
        }

        public int trackZ() {
            return params.trackZ();
        }

        public int trackY() {
            return params.trackY();
        }

        public int topTrackY() {
            return params.topTrackY();
        }

        public int minDepth() {
            return params.minDepth();
        }

        public int minHeight() {
            return params.minHeight();
        }

        public int minLength() {
            return params.minLength();
        }

        public int maxLength() {
            return params.maxLength();
        }

        public int minGap() {
            return params.minGap();
        }

        public int mergeGap() {
            return params.mergeGap();
        }

        public Optional<BlockState> foundation() {
            return params.foundation();
        }

        public int foundationDepth() {
            return params.foundationDepth();
        }
    }

    /**
     * A block put on top of the terrain the line leaves exposed (RAILWAYS.md §A8.10, §A8.11): every column within
     * {@code reach} of the track whose top block is solid on top and has air above, with {@code chance}, in
     * allowed biomes — ballast, berms, cut slopes, banks — but not in tunnels, on bridge decks, under structures,
     * at stations, and (unless {@code inside_train_space}) not in the space trains drive through.
     */
    public record CoverLayer(BlockState state, float chance, int reach, BiomeFilter biomes, BiomeFilter excludeBiomes,
                             boolean insideTrainSpace) {
        static final Codec<CoverLayer> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockState.CODEC.fieldOf("state").forGetter(CoverLayer::state),
                Codec.floatRange(0, 1).optionalFieldOf("chance", 1.0F).forGetter(CoverLayer::chance),
                Codec.intRange(0, 32).optionalFieldOf("reach", 24).forGetter(CoverLayer::reach),
                BiomeFilter.CODEC.optionalFieldOf("biomes", BiomeFilter.NONE).forGetter(CoverLayer::biomes),
                BiomeFilter.CODEC.optionalFieldOf("exclude_biomes", BiomeFilter.NONE).forGetter(CoverLayer::excludeBiomes),
                Codec.BOOL.optionalFieldOf("inside_train_space", false).forGetter(CoverLayer::insideTrainSpace)
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
