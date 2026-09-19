package com.yamikhal.frostlinerailways.rail.sites;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

import java.util.List;

/**
 * A whole-number range from datapack JSON: {@code [min, max]}, {@code [n]} or a bare {@code n} (min = max = n).
 * The two ends may be written in either order.
 */
public record SiteRange(int min, int max) {

    public static Codec<SiteRange> codec(int lowest, int highest) {
        Codec<Integer> value = Codec.intRange(lowest, highest);
        return Codec.either(value, value.listOf()).comapFlatMap(either -> either.map(
                n -> DataResult.success(new SiteRange(n, n)),
                list -> list.isEmpty() || list.size() > 2
                        ? DataResult.error(() -> "expected a number or [min, max], got " + list)
                        : DataResult.success(new SiteRange(Math.min(list.get(0), list.get(list.size() - 1)),
                                Math.max(list.get(0), list.get(list.size() - 1))))),
                range -> Either.right(List.of(range.min, range.max)));
    }

    /** A whole number in [min, max] for a uniform roll in [0, 1). */
    public int roll(double uniform) {
        return Math.min(max, min + (int) Math.floor(uniform * (max - min + 1)));
    }

    public boolean contains(int value) {
        return value >= min && value <= max;
    }

    /** This range with min raised to at least {@code floor} and at least {@code width} + 1 values wide (max raised). */
    public SiteRange atLeast(int floor, int width) {
        int lo = Math.max(min, floor);
        return new SiteRange(lo, Math.max(max, lo + width));
    }

    @Override
    public String toString() {
        return "[" + min + ", " + max + "]";
    }
}
