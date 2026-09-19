package com.yamikhal.frostlinerailways.rail;

import com.yamikhal.frostlinerailways.StrictFields;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

/**
 * One railway line, from datapack JSON: data/&lt;namespace&gt;/frostline_rail/line/&lt;id&gt;.json
 * (RAILWAYS.md §A4.1). Every field is optional; missing fields take the defaults below.
 *
 * Changing a line that a world already generated does not move it: the stored layout is kept
 * (/frostline rail layout rebuild replaces it, for new chunks only).
 */
public record RailLineDef(ResourceLocation dimension, End south, End north, Grade grade, Steering steering,
                          Bed bed, Blocks blocks) {

    /** Where a line ends: the first point walking away from spawn where the climate value reaches {@code value}. */
    public record End(String climate, double value, int searchLimit) {
        static Codec<End> codec(End defaults) {
            return StrictFields.record(i -> i.group(
                    StrictFields.optional(StrictFields.oneOf("temperature", "humidity", "vegetation", "continentalness", "continents", "erosion",
                            "depth", "weirdness", "ridges"), "climate", defaults.climate()).forGetter(End::climate),
                    StrictFields.optional(Codec.DOUBLE, "value", defaults.value()).forGetter(End::value),
                    StrictFields.optional(Codec.intRange(64, 1_000_000), "search_limit", defaults.searchLimit()).forGetter(End::searchLimit)
            ).apply(i, End::new));
        }
    }

    /**
     * Vertical profile, chosen for the whole line at once: the cheapest sequence of track heights, one
     * per ramp step. Every block of track costs {@code fillCost} per block it sits above the ground
     * ({@code fillCost + bridgeCost} per block beyond the bed's maxFill) and {@code cutCost} per block
     * below it (counted up to clearance + tunnelMinCover: deeper is a tunnel either way). Every ramp —
     * one continuous climb or descent — costs {@code rampCost}. So the track lies on flat ground, cuts
     * through or fills over bumps and dips, and only climbs or descends, in one go, where holding level
     * would cost more than the ramp. Ramps rise one block every {@code run} blocks (Create accepts
     * 7..31). Terrain is sampled every {@code sampleSpacing} blocks.
     */
    public record Grade(int run, int sampleSpacing, double fillCost, double cutCost, double bridgeCost, double rampCost) {
        static final Grade DEFAULT = new Grade(16, 8, 1.0, 1.0, 2.0, 200.0);
        static final Codec<Grade> CODEC = StrictFields.record(i -> i.group(
                StrictFields.optional(Codec.intRange(7, 31), "run", DEFAULT.run()).forGetter(Grade::run),
                StrictFields.optional(Codec.intRange(4, 64), "sample_spacing", DEFAULT.sampleSpacing()).forGetter(Grade::sampleSpacing),
                StrictFields.optional(Codec.doubleRange(0, 1000), "fill_cost", DEFAULT.fillCost()).forGetter(Grade::fillCost),
                StrictFields.optional(Codec.doubleRange(0, 1000), "cut_cost", DEFAULT.cutCost()).forGetter(Grade::cutCost),
                StrictFields.optional(Codec.doubleRange(0, 1000), "bridge_cost", DEFAULT.bridgeCost()).forGetter(Grade::bridgeCost),
                StrictFields.optional(Codec.doubleRange(0, 1_000_000), "ramp_cost", DEFAULT.rampCost()).forGetter(Grade::rampCost)
        ).apply(i, Grade::new));
    }

    /**
     * Horizontal profile. The track runs straight while the corridor stays within {@code tolerance}
     * blocks of it, and straights are at least {@code minStraight} blocks long. A correction is one
     * maneuver towards the corridor's average over the next {@code averageWindow} blocks: a single
     * S-bend up to 13 blocks sideways, or a 45 degree curve, a diagonal run and a curve back for more.
     */
    public record Steering(int tolerance, int minStraight, int averageWindow) {
        static final Steering DEFAULT = new Steering(24, 160, 192);
        static final Codec<Steering> CODEC = StrictFields.record(i -> i.group(
                StrictFields.optional(Codec.intRange(2, 256), "tolerance", DEFAULT.tolerance()).forGetter(Steering::tolerance),
                StrictFields.optional(Codec.intRange(16, 4096), "min_straight", DEFAULT.minStraight()).forGetter(Steering::minStraight),
                StrictFields.optional(Codec.intRange(16, 4096), "average_window", DEFAULT.averageWindow()).forGetter(Steering::averageWindow)
        ).apply(i, Steering::new));
    }

    /**
     * Track bed: {@code halfWidth} blocks of ballast each side of the track, {@code clearance} blocks of air
     * above it. Rows where the track stands at least {@code bridgeMinHeight} blocks above the ground are
     * bridges (piers every {@code pierSpacing}, at most {@code maxPierDepth} deep); lower gaps are filled. Ground
     * more than {@code tunnelMinCover} above the tunnel is tunnelled, less is cut open. {@code maxFill} is where the
     * profile's bridge cost starts. Blending (RAILWAYS.md §A8.10): embankments fall away from the ballast over
     * {@code bermReach} blocks and cut sides rise over {@code cutSlopeReach} blocks, {@code slopeStep} blocks per
     * block outward (0 reach = off).
     */
    public record Bed(int halfWidth, int clearance, int maxFill, int tunnelMinCover, int pierSpacing, int maxPierDepth,
                      int bridgeMinHeight, int bermReach, int cutSlopeReach, int slopeStep) {
        static final Bed DEFAULT = new Bed(2, 5, 6, 6, 12, 64, 4, 6, 8, 1);
        static final Codec<Bed> CODEC = StrictFields.record(i -> i.group(
                StrictFields.optional(Codec.intRange(0, 6), "half_width", DEFAULT.halfWidth()).forGetter(Bed::halfWidth),
                StrictFields.optional(Codec.intRange(3, 12), "clearance", DEFAULT.clearance()).forGetter(Bed::clearance),
                StrictFields.optional(Codec.intRange(0, 32), "max_fill", DEFAULT.maxFill()).forGetter(Bed::maxFill),
                StrictFields.optional(Codec.intRange(1, 64), "tunnel_min_cover", DEFAULT.tunnelMinCover()).forGetter(Bed::tunnelMinCover),
                StrictFields.optional(Codec.intRange(2, 64), "pier_spacing", DEFAULT.pierSpacing()).forGetter(Bed::pierSpacing),
                StrictFields.optional(Codec.intRange(1, 128), "max_pier_depth", DEFAULT.maxPierDepth()).forGetter(Bed::maxPierDepth),
                StrictFields.optional(Codec.intRange(1, 64), "bridge_min_height", DEFAULT.bridgeMinHeight()).forGetter(Bed::bridgeMinHeight),
                StrictFields.optional(Codec.intRange(0, 16), "berm_reach", DEFAULT.bermReach()).forGetter(Bed::bermReach),
                StrictFields.optional(Codec.intRange(0, 16), "cut_slope_reach", DEFAULT.cutSlopeReach()).forGetter(Bed::cutSlopeReach),
                StrictFields.optional(Codec.intRange(1, 8), "slope_step", DEFAULT.slopeStep()).forGetter(Bed::slopeStep)
        ).apply(i, Bed::new));
    }

    /** Blocks of the bed. Styles per biome/climate replace these in F4. */
    public record Blocks(ResourceLocation ballast, ResourceLocation fill, ResourceLocation pier) {
        static final Blocks DEFAULT = new Blocks(new ResourceLocation("minecraft", "gravel"),
                new ResourceLocation("minecraft", "cobblestone"), new ResourceLocation("minecraft", "stone_bricks"));
        static final Codec<Blocks> CODEC = StrictFields.record(i -> i.group(
                StrictFields.optional(ResourceLocation.CODEC, "ballast", DEFAULT.ballast()).forGetter(Blocks::ballast),
                StrictFields.optional(ResourceLocation.CODEC, "fill", DEFAULT.fill()).forGetter(Blocks::fill),
                StrictFields.optional(ResourceLocation.CODEC, "pier", DEFAULT.pier()).forGetter(Blocks::pier)
        ).apply(i, Blocks::new));
    }

    static final End DEFAULT_SOUTH = new End("continentalness", 0.25, 20_000);
    static final End DEFAULT_NORTH = new End("temperature", 0.591, 40_000);

    public static final Codec<RailLineDef> CODEC = StrictFields.record(i -> i.group(
            StrictFields.optional(ResourceLocation.CODEC, "dimension", new ResourceLocation("minecraft", "overworld")).forGetter(RailLineDef::dimension),
            StrictFields.optional(End.codec(DEFAULT_SOUTH), "south", DEFAULT_SOUTH).forGetter(RailLineDef::south),
            StrictFields.optional(End.codec(DEFAULT_NORTH), "north", DEFAULT_NORTH).forGetter(RailLineDef::north),
            StrictFields.optional(Grade.CODEC, "grade", Grade.DEFAULT).forGetter(RailLineDef::grade),
            StrictFields.optional(Steering.CODEC, "steering", Steering.DEFAULT).forGetter(RailLineDef::steering),
            StrictFields.optional(Bed.CODEC, "bed", Bed.DEFAULT).forGetter(RailLineDef::bed),
            StrictFields.optional(Blocks.CODEC, "blocks", Blocks.DEFAULT).forGetter(RailLineDef::blocks)
    ).apply(i, RailLineDef::new));
}
