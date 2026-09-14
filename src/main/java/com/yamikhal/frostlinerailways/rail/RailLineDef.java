package com.yamikhal.frostlinerailways.rail;

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
            return RecordCodecBuilder.create(i -> i.group(
                    Codec.STRING.optionalFieldOf("climate", defaults.climate()).forGetter(End::climate),
                    Codec.DOUBLE.optionalFieldOf("value", defaults.value()).forGetter(End::value),
                    Codec.intRange(64, 1_000_000).optionalFieldOf("search_limit", defaults.searchLimit()).forGetter(End::searchLimit)
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
        static final Codec<Grade> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.intRange(7, 31).optionalFieldOf("run", DEFAULT.run()).forGetter(Grade::run),
                Codec.intRange(4, 64).optionalFieldOf("sample_spacing", DEFAULT.sampleSpacing()).forGetter(Grade::sampleSpacing),
                Codec.doubleRange(0, 1000).optionalFieldOf("fill_cost", DEFAULT.fillCost()).forGetter(Grade::fillCost),
                Codec.doubleRange(0, 1000).optionalFieldOf("cut_cost", DEFAULT.cutCost()).forGetter(Grade::cutCost),
                Codec.doubleRange(0, 1000).optionalFieldOf("bridge_cost", DEFAULT.bridgeCost()).forGetter(Grade::bridgeCost),
                Codec.doubleRange(0, 1_000_000).optionalFieldOf("ramp_cost", DEFAULT.rampCost()).forGetter(Grade::rampCost)
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
        static final Codec<Steering> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.intRange(2, 256).optionalFieldOf("tolerance", DEFAULT.tolerance()).forGetter(Steering::tolerance),
                Codec.intRange(16, 4096).optionalFieldOf("min_straight", DEFAULT.minStraight()).forGetter(Steering::minStraight),
                Codec.intRange(16, 4096).optionalFieldOf("average_window", DEFAULT.averageWindow()).forGetter(Steering::averageWindow)
        ).apply(i, Steering::new));
    }

    /**
     * Track bed: {@code halfWidth} blocks of ballast each side of the track, {@code clearance} blocks of
     * air above it. Gaps up to {@code maxFill} are filled, deeper ones bridged with piers every
     * {@code pierSpacing} blocks (at most {@code maxPierDepth} deep). Ground more than
     * {@code tunnelMinCover} above the clearance is tunnelled, less is cut open.
     */
    public record Bed(int halfWidth, int clearance, int maxFill, int tunnelMinCover, int pierSpacing, int maxPierDepth) {
        static final Bed DEFAULT = new Bed(2, 5, 6, 6, 12, 64);
        static final Codec<Bed> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.intRange(0, 6).optionalFieldOf("half_width", DEFAULT.halfWidth()).forGetter(Bed::halfWidth),
                Codec.intRange(3, 12).optionalFieldOf("clearance", DEFAULT.clearance()).forGetter(Bed::clearance),
                Codec.intRange(0, 32).optionalFieldOf("max_fill", DEFAULT.maxFill()).forGetter(Bed::maxFill),
                Codec.intRange(1, 64).optionalFieldOf("tunnel_min_cover", DEFAULT.tunnelMinCover()).forGetter(Bed::tunnelMinCover),
                Codec.intRange(2, 64).optionalFieldOf("pier_spacing", DEFAULT.pierSpacing()).forGetter(Bed::pierSpacing),
                Codec.intRange(1, 128).optionalFieldOf("max_pier_depth", DEFAULT.maxPierDepth()).forGetter(Bed::maxPierDepth)
        ).apply(i, Bed::new));
    }

    /** Blocks of the bed. Styles per biome/climate replace these in F4. */
    public record Blocks(ResourceLocation ballast, ResourceLocation fill, ResourceLocation pier) {
        static final Blocks DEFAULT = new Blocks(new ResourceLocation("minecraft", "gravel"),
                new ResourceLocation("minecraft", "cobblestone"), new ResourceLocation("minecraft", "stone_bricks"));
        static final Codec<Blocks> CODEC = RecordCodecBuilder.create(i -> i.group(
                ResourceLocation.CODEC.optionalFieldOf("ballast", DEFAULT.ballast()).forGetter(Blocks::ballast),
                ResourceLocation.CODEC.optionalFieldOf("fill", DEFAULT.fill()).forGetter(Blocks::fill),
                ResourceLocation.CODEC.optionalFieldOf("pier", DEFAULT.pier()).forGetter(Blocks::pier)
        ).apply(i, Blocks::new));
    }

    static final End DEFAULT_SOUTH = new End("continentalness", 0.25, 20_000);
    static final End DEFAULT_NORTH = new End("temperature", 0.591, 40_000);

    public static final Codec<RailLineDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            ResourceLocation.CODEC.optionalFieldOf("dimension", new ResourceLocation("minecraft", "overworld")).forGetter(RailLineDef::dimension),
            End.codec(DEFAULT_SOUTH).optionalFieldOf("south", DEFAULT_SOUTH).forGetter(RailLineDef::south),
            End.codec(DEFAULT_NORTH).optionalFieldOf("north", DEFAULT_NORTH).forGetter(RailLineDef::north),
            Grade.CODEC.optionalFieldOf("grade", Grade.DEFAULT).forGetter(RailLineDef::grade),
            Steering.CODEC.optionalFieldOf("steering", Steering.DEFAULT).forGetter(RailLineDef::steering),
            Bed.CODEC.optionalFieldOf("bed", Bed.DEFAULT).forGetter(RailLineDef::bed),
            Blocks.CODEC.optionalFieldOf("blocks", Blocks.DEFAULT).forGetter(RailLineDef::blocks)
    ).apply(i, RailLineDef::new));
}
