package com.yamikhal.frostlinerailways.rail.layout;

import com.mojang.logging.LogUtils;
import com.yamikhal.frostlinerailways.CorridorDensityFunction;
import com.yamikhal.frostlinerailways.rail.RailLineDef;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleUnaryOperator;
import java.util.stream.IntStream;

/**
 * Builds a {@link RailLayout} from the seed and a line definition (RAILWAYS.md §A3.1, §A8.4).
 *
 * The goal is a line a player can ride without being thrown around: long straights, level
 * stretches, and every correction made in one go.
 *
 *   1  ends       walk the corridor from spawn until the end climate values are reached
 *   2  steering   straights while the corridor stays within tolerance; then one maneuver to the
 *                 corridor's average ahead: an S-bend (shift &lt;= 13) or a diagonal shift
 *   3  heights    getBaseHeight along the planned track every sampleSpacing blocks (parallel)
 *   4  need       first air above the highest ground in the next lookahead blocks: the level to hold
 *   5  pieces     south to north: maneuvers where planned; otherwise a ramp when the need is above
 *                 the track (climbing to it in one continuous ramp) or more than hysteresis below
 *                 it (descending in one ramp, never below the ground ahead), else straight
 *
 * Reads only noise (corridor, router climate, getBaseHeight); no chunk is loaded.
 */
public final class LayoutBuilder {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int END_STEP = 16;
    private static final int STEER_STEP = 4;

    private LayoutBuilder() {
    }

    /** A planned horizontal maneuver: type, south end z, north end z, x before, x after. */
    private record Maneuver(byte type, int zSouth, int zNorth, int xFrom, int xTo) {
    }

    public static RailLayout build(ServerLevel level, RailLineDef def, String lineId, String hash) {
        long start = System.nanoTime();
        RandomState random = level.getChunkSource().randomState();
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        CorridorDensityFunction corridor = CorridorDensityFunction.find(random).orElse(null);
        if (corridor == null) {
            LOGGER.warn("[FrostlineRailways] {} has no frostline_railways:corridor; the line runs straight along x = 0",
                    level.dimension().location());
        }
        DoubleUnaryOperator cx = z -> corridor == null ? 0.0 : corridor.lineX(z);

        int zSouth = findEnd(random.router(), def.south(), cx, +1) - END_STEP;
        int zNorth = findEnd(random.router(), def.north(), cx, -1) + END_STEP;
        if (zSouth - zNorth < 512) {
            throw new IllegalStateException("line too short: z " + zSouth + " to " + zNorth);
        }

        List<Maneuver> maneuvers = steer(def.steering(), zSouth, zNorth, cx);
        int[] pathX = pathX(zSouth, zNorth, (int) Math.round(cx.applyAsDouble(zSouth)), maneuvers);

        int spacing = def.grade().sampleSpacing();
        int samples = (zSouth - zNorth) / spacing + 1;
        int[] height = new int[samples];
        IntStream.range(0, samples).parallel().forEach(i -> {
            int z = zSouth - i * spacing;
            height[i] = generator.getBaseHeight(pathX[zSouth - z], z, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, level, random);
        });
        int[] need = forwardMax(height, Math.max(1, def.grade().lookahead() / spacing));

        RailLayout layout = pieces(def, lineId, hash, zSouth, zNorth, cx, pathX, maneuvers, need, height, spacing);
        int bends = 0;
        int shifts = 0;
        int ramps = 0;
        for (int i = 0; i < layout.count(); i++) {
            switch (layout.type(i)) {
                case RailLayout.BEND -> bends++;
                case RailLayout.SHIFT -> shifts++;
                case RailLayout.RAMP -> ramps++;
                default -> { }
            }
        }
        LOGGER.info("[FrostlineRailways] rail layout {}: z {} to {} ({} blocks), {} pieces ({} S-bends, {} diagonal shifts, {} ramps), "
                        + "max corridor deviation {} blocks, built in {} ms",
                lineId, zSouth, zNorth, layout.length(), layout.count(), bends, shifts, ramps,
                String.format("%.1f", layout.maxDeviation), (System.nanoTime() - start) / 1_000_000);
        return layout;
    }

    // --- ends ------------------------------------------------------------------------------

    private static int findEnd(NoiseRouter router, RailLineDef.End end, DoubleUnaryOperator cx, int direction) {
        DensityFunction slot = slot(router, end.climate());
        for (int d = 0; d <= end.searchLimit(); d += END_STEP) {
            int z = direction * d;
            double value = slot.compute(new DensityFunction.SinglePointContext((int) Math.round(cx.applyAsDouble(z)), 0, z));
            if (value >= end.value()) {
                return z;
            }
        }
        return direction * end.searchLimit();
    }

    private static DensityFunction slot(NoiseRouter router, String name) {
        return switch (name) {
            case "temperature" -> router.temperature();
            case "humidity", "vegetation" -> router.vegetation();
            case "continentalness", "continents" -> router.continents();
            case "erosion" -> router.erosion();
            case "depth" -> router.depth();
            case "weirdness", "ridges" -> router.ridges();
            default -> throw new IllegalArgumentException("unknown climate parameter: " + name);
        };
    }

    // --- steering -----------------------------------------------------------------------------

    private static List<Maneuver> steer(RailLineDef.Steering steering, int zSouth, int zNorth, DoubleUnaryOperator cx) {
        List<Maneuver> list = new ArrayList<>();
        int x = (int) Math.round(cx.applyAsDouble(zSouth));
        int straightFrom = zSouth;
        int z = zSouth - 2;
        int stop = zNorth + 2 * RailLayout.SHIFT_CURVE_Z + 64;
        while (z > stop) {
            int at = Integer.MIN_VALUE;
            for (int zz = z; zz > stop; zz -= STEER_STEP) {
                if (straightFrom - zz >= steering.minStraight() && Math.abs(cx.applyAsDouble(zz) - x) > steering.tolerance()) {
                    at = zz;
                    break;
                }
            }
            if (at == Integer.MIN_VALUE) {
                break;
            }
            int target = (int) Math.round(mean(cx, at - steering.averageWindow(), at));
            int shift = target - x;
            int abs = Math.abs(shift);
            byte type;
            int length;
            if (abs < 2) {
                straightFrom = at;
                z = at - STEER_STEP;
                continue;
            } else if (abs <= RailLayout.BEND_MAX_SHIFT + 1) {
                shift = Math.max(-RailLayout.BEND_MAX_SHIFT, Math.min(RailLayout.BEND_MAX_SHIFT, shift));
                abs = Math.abs(shift);
                type = RailLayout.BEND;
                length = bendLength(abs);
            } else {
                type = RailLayout.SHIFT;
                length = 2 * RailLayout.SHIFT_CURVE_Z + (abs - 2 * RailLayout.SHIFT_CURVE_X);
            }
            int end = at - length;
            if (end - 2 <= zNorth + 8) {
                break;
            }
            list.add(new Maneuver(type, at, end, x, x + shift));
            x += shift;
            straightFrom = end;
            z = end - 2;
        }
        return list;
    }

    /** Longest comfortable S-bend for a shift: three blocks per block of shift, capped by Create's 32 block reach. */
    private static int bendLength(int shift) {
        int length = Math.max(4, Math.max(2 * shift + 1, 3 * shift));
        while (length * length + shift * shift > 32 * 32) {
            length--;
        }
        return length;
    }

    private static double mean(DoubleUnaryOperator f, int zLow, int zHigh) {
        double sum = 0;
        int n = 0;
        for (int z = zHigh; z >= zLow; z -= STEER_STEP) {
            sum += f.applyAsDouble(z);
            n++;
        }
        return sum / Math.max(1, n);
    }

    /** Track x for every block z, index zSouth - z. */
    private static int[] pathX(int zSouth, int zNorth, int x0, List<Maneuver> maneuvers) {
        int[] xs = new int[zSouth - zNorth + 1];
        int x = x0;
        int m = 0;
        for (int z = zSouth; z >= zNorth; z--) {
            xs[zSouth - z] = x;
            if (m < maneuvers.size()) {
                Maneuver man = maneuvers.get(m);
                if (z <= man.zSouth() && z >= man.zNorth()) {
                    double t = (man.zSouth() - z) / (double) (man.zSouth() - man.zNorth());
                    xs[zSouth - z] = (int) Math.round(man.xFrom() + (man.xTo() - man.xFrom()) * t);
                    if (z == man.zNorth()) {
                        x = man.xTo();
                        m++;
                    }
                }
            }
        }
        return xs;
    }

    // --- vertical ------------------------------------------------------------------------------

    /**
     * For each sample, the highest value in it and the next {@code window} samples (towards the north).
     * Values are getBaseHeight results, the first air above the ground: the track block's own height.
     */
    private static int[] forwardMax(int[] values, int window) {
        int n = values.length;
        int[] out = new int[n];
        ArrayDeque<Integer> deque = new ArrayDeque<>();
        for (int i = n - 1; i >= 0; i--) {
            while (!deque.isEmpty() && values[deque.peekLast()] <= values[i]) {
                deque.pollLast();
            }
            deque.addLast(i);
            while (deque.peekFirst() > i + window) {
                deque.pollFirst();
            }
            out[i] = values[deque.peekFirst()];
        }
        return out;
    }

    // --- pieces --------------------------------------------------------------------------------

    private static RailLayout pieces(RailLineDef def, String lineId, String hash, int zSouth, int zNorth,
                                     DoubleUnaryOperator cx, int[] pathX, List<Maneuver> maneuvers, int[] need, int[] height, int spacing) {
        int run = def.grade().run();
        int hysteresis = def.grade().hysteresis();
        List<int[]> pieces = new ArrayList<>();
        java.util.function.IntUnaryOperator needAt = z -> need[Math.max(0, Math.min(need.length - 1, (zSouth - z) / spacing))];

        int z = zSouth;
        int x = pathX[0];
        int y = needAt.applyAsInt(zSouth);
        int straightSouth = z;
        boolean forceStraight = true;
        int m = 0;
        double maxDeviation = 0;

        while (z >= zNorth) {
            maxDeviation = Math.max(maxDeviation, Math.abs(cx.applyAsDouble(z) - pathX[zSouth - z]));
            if (m < maneuvers.size() && maneuvers.get(m).zSouth() == z) {
                Maneuver man = maneuvers.get(m);
                if (straightSouth >= z + 1) {
                    pieces.add(new int[] {RailLayout.STRAIGHT, straightSouth, z + 1, x, x, y, y});
                }
                pieces.add(new int[] {man.type(), z, man.zNorth(), man.xFrom(), man.xTo(), y, y});
                x = man.xTo();
                z = man.zNorth() - 1;
                straightSouth = z;
                forceStraight = true;
                m++;
                continue;
            }
            if (!forceStraight) {
                int limit = Math.max(zNorth + 1, m < maneuvers.size() ? maneuvers.get(m).zSouth() + 2 : Integer.MIN_VALUE);
                int maxSteps = (z - limit) / run;
                int want = needAt.applyAsInt(z);
                int steps = 0;
                int dir = 0;
                if (maxSteps >= 1 && want > y) {
                    dir = 1;
                    steps = Math.min(maxSteps, want - y);
                    while (steps < maxSteps && needAt.applyAsInt(z - steps * run) > y + steps) {
                        steps++;
                    }
                } else if (maxSteps >= 1 && want < y - hysteresis) {
                    dir = -1;
                    steps = Math.min(maxSteps, y - want);
                    while (steps > 0 && y - steps < needAt.applyAsInt(z - steps * run)) {
                        steps--;
                    }
                }
                if (steps >= 1) {
                    if (straightSouth >= z + 1) {
                        pieces.add(new int[] {RailLayout.STRAIGHT, straightSouth, z + 1, x, x, y, y});
                    }
                    int end = z - steps * run;
                    pieces.add(new int[] {RailLayout.RAMP, z, end, x, x, y, y + dir * steps});
                    y += dir * steps;
                    z = end - 1;
                    straightSouth = z;
                    forceStraight = true;
                    continue;
                }
            }
            forceStraight = false;
            z--;
        }
        if (straightSouth >= zNorth) {
            pieces.add(new int[] {RailLayout.STRAIGHT, straightSouth, zNorth, x, x, y, y});
        }

        int n = pieces.size();
        byte[] type = new byte[n];
        int[] zs = new int[n], zn = new int[n], xs = new int[n], xn = new int[n], ys = new int[n], yn = new int[n];
        for (int i = 0; i < n; i++) {
            int[] piece = pieces.get(i);
            type[i] = (byte) piece[0];
            zs[i] = piece[1];
            zn[i] = piece[2];
            xs[i] = piece[3];
            xn[i] = piece[4];
            ys[i] = piece[5];
            yn[i] = piece[6];
        }
        return new RailLayout(hash, lineId, maxDeviation, type, zs, zn, xs, xn, ys, yn, height, spacing);
    }
}
