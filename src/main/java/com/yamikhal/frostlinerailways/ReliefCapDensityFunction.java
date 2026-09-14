package com.yamikhal.frostlinerailways;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Compresses terrain above a target height, by a per-column weight.
 *
 * Wraps the router's DEPTH term. DEPTH is a Y gradient plus a column offset, and the
 * surface sits where it crosses zero, so
 *
 *   excess = depth - gradient(y) + gradient(target)
 *
 * is how far the column's ground stands above the target, in depth units, at any Y.
 * Where it is positive the column is pulled toward the target:
 *
 *   depth' = depth - weight * strength * excess
 *
 * which moves the surface to target + (1 - weight * strength) * (height - target).
 * Peaks shrink in proportion and keep their shape; valleys below the target are left
 * alone. Nothing is cut to a plane (REF.md rule 1), and a weight that eases to zero
 * leaves no step where the effect ends.
 *
 * gradient must match the Y gradient inside the wrapped DEPTH (Frostline routers use
 * -64..320, 1.5..-1.5, the defaults). Terms added after DEPTH (jaggedness, 3D noise)
 * still ride on top, so compressed peaks keep some texture.
 */
public record ReliefCapDensityFunction(
        DensityFunction input,
        DensityFunction weight,
        DensityFunction target,
        double strength,
        int fromY,
        int toY,
        double fromValue,
        double toValue
) implements DensityFunction {

    public static final KeyDispatchDataCodec<ReliefCapDensityFunction> CODEC =
            KeyDispatchDataCodec.of(RecordCodecBuilder.mapCodec(instance -> instance.group(
                    DensityFunction.HOLDER_HELPER_CODEC.fieldOf("input")
                            .forGetter(ReliefCapDensityFunction::input),
                    DensityFunction.HOLDER_HELPER_CODEC.fieldOf("weight")
                            .forGetter(ReliefCapDensityFunction::weight),
                    DensityFunction.HOLDER_HELPER_CODEC.fieldOf("target")
                            .forGetter(ReliefCapDensityFunction::target),
                    Codec.doubleRange(0.0D, 1.0D).fieldOf("strength")
                            .forGetter(ReliefCapDensityFunction::strength),
                    Codec.INT.optionalFieldOf("from_y", -64)
                            .forGetter(ReliefCapDensityFunction::fromY),
                    Codec.INT.optionalFieldOf("to_y", 320)
                            .forGetter(ReliefCapDensityFunction::toY),
                    Codec.DOUBLE.optionalFieldOf("from_value", 1.5D)
                            .forGetter(ReliefCapDensityFunction::fromValue),
                    Codec.DOUBLE.optionalFieldOf("to_value", -1.5D)
                            .forGetter(ReliefCapDensityFunction::toValue)
            ).apply(instance, ReliefCapDensityFunction::new)));

    @Override
    public double compute(FunctionContext ctx) {
        double depth = input.compute(ctx);
        double w = weight.compute(ctx);
        if (w <= 0.0D) {
            return depth;
        }
        double excess = depth - gradient(ctx.blockY()) + gradient(target.compute(ctx));
        if (excess <= 0.0D) {
            return depth;
        }
        return depth - Math.min(w, 1.0D) * strength * excess;
    }

    private double gradient(double y) {
        return Mth.clampedMap(y, fromY, toY, fromValue, toValue);
    }

    @Override
    public void fillArray(double[] array, ContextProvider provider) {
        provider.fillAllDirectly(array, this);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return visitor.apply(new ReliefCapDensityFunction(
                input.mapAll(visitor), weight.mapAll(visitor), target.mapAll(visitor),
                strength, fromY, toY, fromValue, toValue));
    }

    @Override
    public double minValue() {
        return input.minValue() - Math.abs(fromValue - toValue);
    }

    @Override
    public double maxValue() {
        return input.maxValue();
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
