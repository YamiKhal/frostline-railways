package com.yamikhal.frostlinerailways.rail.layout;

import com.mojang.logging.LogUtils;
import com.yamikhal.frostlinerailways.CorridorDensityFunction;
import com.yamikhal.frostlinerailways.RailwaysConfig;
import net.minecraft.core.QuartPos;
import com.yamikhal.frostlinerailways.rail.RailLineDef;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.DoubleUnaryOperator;
import java.util.stream.IntStream;

/**
 * Builds a {@link RailLayout} from the seed and a line definition (RAILWAYS.md §A3.1, §A8.4, §A8.6).
 *
 * The goal is a line a player can ride without being thrown around and can walk onto: long
 * straights, long level stretches on the ground, and every correction made in one go.
 *
 *   1  ends       walk the corridor from spawn until the end climate values are reached
 *   2  steering   straights while the corridor stays within tolerance; then one maneuver to the
 *                 corridor's average ahead: an S-bend (shift &lt;= 13) or a diagonal shift
 *   3  heights    getBaseHeight along the planned track every sampleSpacing blocks (parallel)
 *   4  profile    one track height per ramp step (run blocks) for the whole line: the cheapest by the
 *                 grade's costs (blocks above the ground, below it, and a fixed cost per ramp), found
 *                 by dynamic programming over heights; no ramp next to a maneuver
 *   5  pieces     south to north: maneuvers where planned; ramps where the profile changes height
 *
 * Reads only noise (corridor, router climate, getBaseHeight); no chunk is loaded.
 */
public final class LayoutBuilder {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int END_STEP = 16;
    private static final int STEER_STEP = 4;
    private static final double INF = Double.MAX_VALUE / 4;

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

        RaiseRules rules = RaiseRules.fromConfig();
        int spacing = def.grade().sampleSpacing();
        int samples = (zSouth - zNorth) / spacing + 1;
        int[] height = new int[samples];
        int[] raise = new int[samples];
        IntStream.range(0, samples).parallel().forEach(i -> {
            int z = zSouth - i * spacing;
            int x = pathX[zSouth - z];
            height[i] = generator.getBaseHeight(x, z, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, level, random);
            if (!rules.isEmpty()) {
                raise[i] = rules.raise(generator.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(height[i]),
                        QuartPos.fromBlock(z), random.sampler()));
            }
        });
        double[] weight = new double[samples];
        int[] target = targets(height, raise, spacing, weight);
        long raised = Arrays.stream(raise).filter(r -> r != 0).count();
        int[] levels = profile(def, zSouth, zNorth, maneuvers, target, weight, spacing);

        RailLayout layout = pieces(def, lineId, hash, zSouth, zNorth, cx, pathX, maneuvers, levels, height, spacing);
        int bends = 0;
        int shifts = 0;
        int ramps = 0;
        long offset = 0;
        for (int i = 0; i < layout.count(); i++) {
            switch (layout.type(i)) {
                case RailLayout.BEND -> bends++;
                case RailLayout.SHIFT -> shifts++;
                case RailLayout.RAMP -> ramps++;
                default -> { }
            }
        }
        for (int z = zSouth; z >= zNorth; z -= spacing) {
            offset += Math.abs((long) Math.floor(layout.centreY(layout.pieceAt(z), z)) - layout.groundAt(z));
        }
        LOGGER.info("[FrostlineRailways] rail layout {}: z {} to {} ({} blocks), {} pieces ({} S-bends, {} diagonal shifts, {} ramps), "
                        + "max corridor deviation {} blocks, track {} blocks from the ground on average, {}% of it in raised biomes, built in {} ms",
                lineId, zSouth, zNorth, layout.length(), layout.count(), bends, shifts, ramps,
                String.format("%.1f", layout.maxDeviation), String.format("%.1f", offset / (double) samples),
                raised * 100 / samples, (System.nanoTime() - start) / 1_000_000);
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
     * The height the profile aims for at every sample: the ground plus the biome's raise, or, for
     * {@link RaiseRules#HIGHEST}, the highest ground of the next highestLookahead blocks with its cost
     * weighted by highestStrictness so the track holds it.
     */
    private static int[] targets(int[] height, int[] raise, int spacing, double[] weight) {
        int[] highest = forwardMax(height, Math.max(1, RailwaysConfig.highestLookahead() / spacing));
        int[] target = new int[height.length];
        for (int i = 0; i < height.length; i++) {
            if (raise[i] == RaiseRules.HIGHEST) {
                target[i] = highest[i];
                weight[i] = RailwaysConfig.highestStrictness();
            } else {
                target[i] = height[i] + raise[i];
                weight[i] = 1.0;
            }
        }
        return target;
    }

    /** For each sample, the highest value in it and the next {@code window} samples (towards the north). */
    private static int[] forwardMax(int[] values, int window) {
        int n = values.length;
        int[] out = new int[n];
        java.util.ArrayDeque<Integer> deque = new java.util.ArrayDeque<>();
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

    /**
     * The track height of every ramp step k (blocks zSouth - k * run down to zSouth - (k + 1) * run + 1),
     * minimising terrain cost plus rampCost per ramp. States are (height, mode) with mode level, climbing
     * or descending; a climb can only follow level or a climb (likewise descents), so every ramp is one
     * continuous change. Heights are getBaseHeight values: the first air above the ground, i.e. a track
     * block lying on the ground.
     */
    private static int[] profile(RailLineDef def, int zSouth, int zNorth, List<Maneuver> maneuvers, int[] target, double[] weight,
                                 int spacing) {
        RailLineDef.Grade grade = def.grade();
        int run = grade.run();
        int steps = (zSouth - zNorth) / run + 1;
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        for (int h : target) {
            lo = Math.min(lo, h);
            hi = Math.max(hi, h);
        }
        lo -= 4;
        hi += 8;
        int levels = hi - lo + 1;

        boolean[] noRamp = new boolean[steps];
        noRamp[0] = true;
        int pad = run + 4;
        for (Maneuver man : maneuvers) {
            int from = Math.max(0, Math.floorDiv(zSouth - man.zSouth() - pad, run));
            int to = Math.min(steps - 1, Math.floorDiv(zSouth - man.zNorth() + pad, run));
            for (int k = from; k <= to; k++) {
                noRamp[k] = true;
            }
        }

        double rampCost = grade.rampCost();
        double[] terrain = new double[levels];
        double[] cost = new double[levels * 3];
        double[] next = new double[levels * 3];
        byte[] back = new byte[steps * levels * 3];
        Arrays.fill(cost, INF);
        terrain(terrain, 0, lo, target, weight, spacing, def);
        for (int y = 0; y < levels; y++) {
            cost[y * 3] = terrain[y];
        }
        for (int k = 1; k < steps; k++) {
            terrain(terrain, k, lo, target, weight, spacing, def);
            int b = k * levels * 3;
            for (int y = 0; y < levels; y++) {
                int s = y * 3;
                int mode = 0;
                double best = cost[s];
                if (cost[s + 1] < best) {
                    best = cost[s + 1];
                    mode = 1;
                }
                if (cost[s + 2] < best) {
                    best = cost[s + 2];
                    mode = 2;
                }
                next[s] = best + terrain[y];
                back[b + s] = (byte) mode;
                next[s + 1] = INF;
                next[s + 2] = INF;
                if (noRamp[k]) {
                    continue;
                }
                if (y > 0) {
                    int p = (y - 1) * 3;
                    boolean keep = cost[p + 1] <= cost[p] + rampCost;
                    next[s + 1] = (keep ? cost[p + 1] : cost[p] + rampCost) + terrain[y];
                    back[b + s + 1] = (byte) (keep ? 1 : 0);
                }
                if (y < levels - 1) {
                    int p = (y + 1) * 3;
                    boolean keep = cost[p + 2] <= cost[p] + rampCost;
                    next[s + 2] = (keep ? cost[p + 2] : cost[p] + rampCost) + terrain[y];
                    back[b + s + 2] = (byte) (keep ? 2 : 0);
                }
            }
            double[] swap = cost;
            cost = next;
            next = swap;
        }

        int y = 0;
        int mode = 0;
        for (int i = 0; i < cost.length; i++) {
            if (cost[i] < cost[y * 3 + mode]) {
                y = i / 3;
                mode = i % 3;
            }
        }
        int[] out = new int[steps];
        for (int k = steps - 1; k >= 0; k--) {
            out[k] = lo + y;
            int previous = back[k * levels * 3 + y * 3 + mode];
            if (mode == 1) {
                y--;
            } else if (mode == 2) {
                y++;
            }
            mode = previous;
        }
        return out;
    }

    /** Cost of every height for ramp step k, from the target samples inside it (distance to the target, as if it were the ground). */
    private static void terrain(double[] out, int k, int lo, int[] height, double[] weight, int spacing, RailLineDef def) {
        RailLineDef.Grade grade = def.grade();
        RailLineDef.Bed bed = def.bed();
        int run = grade.run();
        double cutCap = bed.clearance() + bed.tunnelMinCover();
        int first = Math.max(0, (k * run + spacing - 1) / spacing);
        int last = Math.min(height.length - 1, ((k + 1) * run - 1) / spacing);
        if (first > last) {
            first = Math.min(height.length - 1, Math.round(k * run / (float) spacing));
            last = first;
        }
        Arrays.fill(out, 0.0);
        for (int i = first; i <= last; i++) {
            int ground = height[i];
            for (int y = 0; y < out.length; y++) {
                int d = lo + y - ground;
                double c;
                if (d >= 0) {
                    c = grade.fillCost() * Math.min(d, bed.maxFill())
                            + (grade.fillCost() + grade.bridgeCost()) * Math.max(0, d - bed.maxFill());
                } else {
                    c = grade.cutCost() * Math.min(-d, cutCap);
                }
                out[y] += c * spacing * weight[i];
            }
        }
    }

    /** Height changes of the profile, one entry per continuous climb or descent: {z where it starts, height it ends at}. */
    private static List<int[]> ramps(int[] levels, int zSouth, int run) {
        List<int[]> list = new ArrayList<>();
        for (int k = 1; k < levels.length; k++) {
            int dir = Integer.signum(levels[k] - levels[k - 1]);
            if (dir == 0) {
                continue;
            }
            boolean continues = k >= 2 && Integer.signum(levels[k - 1] - levels[k - 2]) == dir;
            if (continues && !list.isEmpty()) {
                list.get(list.size() - 1)[1] = levels[k];
            } else {
                list.add(new int[] {zSouth - k * run, levels[k]});
            }
        }
        return list;
    }

    // --- pieces --------------------------------------------------------------------------------

    private static RailLayout pieces(RailLineDef def, String lineId, String hash, int zSouth, int zNorth,
                                     DoubleUnaryOperator cx, int[] pathX, List<Maneuver> maneuvers, int[] levels, int[] height, int spacing) {
        int run = def.grade().run();
        List<int[]> ramps = ramps(levels, zSouth, run);
        List<int[]> pieces = new ArrayList<>();

        int z = zSouth;
        int x = pathX[0];
        int y = levels[0];
        int straightSouth = z;
        boolean forceStraight = true;
        int m = 0;
        int r = 0;
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
            if (!forceStraight && r < ramps.size()) {
                // a later ramp already reached supersedes an unfinished one: its end height is absolute
                while (r + 1 < ramps.size() && z <= ramps.get(r + 1)[0]) {
                    r++;
                }
                int[] ramp = ramps.get(r);
                if (z <= ramp[0]) {
                    int target = ramp[1];
                    if (target == y) {
                        r++;
                    } else {
                        int limit = Math.max(zNorth + 1, m < maneuvers.size() ? maneuvers.get(m).zSouth() + 2 : Integer.MIN_VALUE);
                        int steps = Math.min((z - limit) / run, Math.abs(target - y));
                        if (steps >= 1) {
                            int dir = Integer.signum(target - y);
                            if (straightSouth >= z + 1) {
                                pieces.add(new int[] {RailLayout.STRAIGHT, straightSouth, z + 1, x, x, y, y});
                            }
                            int end = z - steps * run;
                            pieces.add(new int[] {RailLayout.RAMP, z, end, x, x, y, y + dir * steps});
                            y += dir * steps;
                            if (y == target) {
                                r++;
                            }
                            z = end - 1;
                            straightSouth = z;
                            forceStraight = true;
                            continue;
                        }
                    }
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
