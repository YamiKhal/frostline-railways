package com.yamikhal.frostlinerailways;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * The line the railway follows north, as a 0..1 weight across it.
 *
 * One centre line bends across the whole world:
 *
 *   x_c(z) = center_x + amplitude * noise(z / wavelength)
 *
 * and each column answers "how close am I to it?":
 *
 *   |x - x_c| <= inner_width   1
 *   |x - x_c| >= outer_width   0
 *   between                    1 - smoothstep, so the edge has zero value and zero slope
 *
 * The datapack multiplies this by a region gate and hands it to frostline_railways:relief_cap,
 * which pulls mountains down toward a target height inside the corridor: a broad pass
 * through the high regions instead of a line of tunnels. The Railways Untold compat
 * reads the same x_c(z) through {@link #find}, so exploration targets aim down the
 * pass this carves.
 *
 * Needs X/Z, which vanilla density functions cannot read (same reason as
 * frostline:progression in the core mod). Seeded through a NoiseHolder, like
 * frostline:lake_field in the core mod.
 */
public record CorridorDensityFunction(
        DensityFunction.NoiseHolder noise,
        double centerX,
        double amplitude,
        double wavelength,
        double innerWidth,
        double outerWidth
) implements DensityFunction.SimpleFunction {

    public static final KeyDispatchDataCodec<CorridorDensityFunction> CODEC =
            KeyDispatchDataCodec.of(RecordCodecBuilder.mapCodec(instance -> instance.group(
                    DensityFunction.NoiseHolder.CODEC.fieldOf("noise")
                            .forGetter(CorridorDensityFunction::noise),
                    Codec.DOUBLE.optionalFieldOf("center_x", 0.0D)
                            .forGetter(CorridorDensityFunction::centerX),
                    Codec.doubleRange(0.0D, 8192.0D).optionalFieldOf("amplitude", 450.0D)
                            .forGetter(CorridorDensityFunction::amplitude),
                    Codec.doubleRange(64.0D, 1.0E6D).optionalFieldOf("wavelength", 4000.0D)
                            .forGetter(CorridorDensityFunction::wavelength),
                    Codec.doubleRange(0.0D, 4096.0D).optionalFieldOf("inner_width", 48.0D)
                            .forGetter(CorridorDensityFunction::innerWidth),
                    Codec.doubleRange(1.0D, 4096.0D).optionalFieldOf("outer_width", 240.0D)
                            .forGetter(CorridorDensityFunction::outerWidth)
            ).apply(instance, CorridorDensityFunction::new)));

    private static final Map<RandomState, Optional<CorridorDensityFunction>> BY_STATE =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** The wired, seeded corridor inside a dimension's final density, if it has one. */
    public static Optional<CorridorDensityFunction> find(RandomState state) {
        return BY_STATE.computeIfAbsent(state, s -> {
            CorridorDensityFunction[] found = new CorridorDensityFunction[1];
            s.router().finalDensity().mapAll(function -> {
                if (found[0] == null && function instanceof CorridorDensityFunction corridor) {
                    found[0] = corridor;
                }
                return function;
            });
            return Optional.ofNullable(found[0]);
        });
    }

    /** Centre line x at this z. */
    public double lineX(double z) {
        return centerX + amplitude * noise.getValue(0.0D, 0.0D, z / wavelength);
    }

    @Override
    public double compute(FunctionContext ctx) {
        double distance = Math.abs(ctx.blockX() - lineX(ctx.blockZ()));
        if (distance <= innerWidth) {
            return 1.0D;
        }
        double outer = Math.max(outerWidth, innerWidth + 1.0D);
        if (distance >= outer) {
            return 0.0D;
        }
        double t = (distance - innerWidth) / (outer - innerWidth);
        return 1.0D - t * t * (3.0D - 2.0D * t);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return visitor.apply(new CorridorDensityFunction(
                visitor.visitNoise(noise), centerX, amplitude, wavelength, innerWidth, outerWidth));
    }

    @Override
    public double minValue() {
        return 0.0D;
    }

    @Override
    public double maxValue() {
        return 1.0D;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
