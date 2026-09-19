package com.yamikhal.frostlinerailways.rail.sites;

import com.yamikhal.frostlinerailways.StrictFields;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Direction;

import java.util.Optional;

/**
 * How a structure has to sit, shared by line sites, district cores and outer structures (RAILWAYS.md §A8.15).
 * Flat in the JSON.
 *
 *   facing       which way the structure's front turns: track, away, along, station, core, random. Each use has
 *                its own default (line site: track, core: station, outer: core). Only structures that take their
 *                rotation from the generation random can be turned (minecraft:jigsaw does; frostline:prop does not)
 *   front        the side of the start template that is its front, as saved (north, south, east, west)
 *   max_slope    largest height difference of the ground under the structure's corners and centre
 *   allow_water  may stand where the noise terrain has water
 *   visible      line sites only: must be seen from the track over the terrain
 */
public record SiteFit(Optional<String> facing, Direction front, int maxSlope, boolean allowWater, boolean visible) {

    public static final MapCodec<SiteFit> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            StrictFields.optional(StrictFields.oneOf("track", "away", "along", "station", "core", "random"), "facing").forGetter(SiteFit::facing),
            StrictFields.optional(Direction.CODEC, "front", Direction.SOUTH).forGetter(SiteFit::front),
            StrictFields.optional(Codec.intRange(0, 512), "max_slope", 8).forGetter(SiteFit::maxSlope),
            StrictFields.optional(Codec.BOOL, "allow_water", false).forGetter(SiteFit::allowWater),
            StrictFields.optional(Codec.BOOL, "visible", true).forGetter(SiteFit::visible)
    ).apply(i, SiteFit::new));

    public String facingOr(String fallback) {
        return facing.orElse(fallback);
    }

    /** The front as a horizontal direction (a vertical one counts as south). */
    public Direction horizontalFront() {
        return front.getAxis().isHorizontal() ? front : Direction.SOUTH;
    }
}
