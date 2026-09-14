package com.yamikhal.frostlinerailways.rail.create;

import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackMaterial;
import com.simibubi.create.content.trains.track.TrackShape;
import com.yamikhal.frostlinerailways.rail.layout.RailLayout;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Create track geometry of one non-straight layout piece (RAILWAYS.md §A8.4): the track blocks,
 * the curve block entities and the graph edges. Worldgen ({@code TrackWriter}) and the graph
 * ({@code RailGraphService}) both read it, so blocks and graph always agree.
 *
 * Curves mirror Create's TrackPlacement: starts on the block edge (or diagonal corner) facing the
 * curve, handles pointing at each other, normals up. Ramps are chains of slopes sharing their
 * middle blocks, smoothed exactly as TrackBlockEntityTilt#tryApplySmoothing would when a player
 * chains slopes, but computed here so no block is touched at runtime.
 *
 * Cached per layout; bounded.
 */
public final class PieceGeometry {

    public static final Vec3 UP = new Vec3(0, 1, 0);
    private static final Vec3 NORTH = new Vec3(0, 0, -1);
    private static final Vec3 SOUTH = new Vec3(0, 0, 1);
    private static final double ROOT_HALF = Math.sqrt(0.5);
    private static final int CACHE_LIMIT = 4096;

    public record TrackBlock(BlockPos pos, TrackShape shape, boolean hasBlockEntity) {
    }

    /** A track block entity: its curves (keyed by their far end in Create) and the tilt of a smoothed ramp block. */
    public record BlockEntity(BlockPos pos, List<BezierConnection> connections, double tilt) {
    }

    /**
     * A graph edge from its southern location to its northern one. Straight edges carry the track
     * axis; curve edges carry the curve, oriented south to north.
     */
    public record Edge(Vec3 south, int southYOffset, Vec3 north, int northYOffset, BezierConnection curve, Vec3 axis) {
    }

    public record Geometry(List<TrackBlock> blocks, List<BlockEntity> entities, List<Edge> edges) {
    }

    private static volatile RailLayout cachedLayout;
    private static final Map<Integer, Geometry> CACHE = new ConcurrentHashMap<>();

    private PieceGeometry() {
    }

    public static Geometry of(RailLayout layout, int i) {
        if (cachedLayout != layout) {
            CACHE.clear();
            cachedLayout = layout;
        }
        if (CACHE.size() > CACHE_LIMIT) {
            CACHE.clear();
        }
        return CACHE.computeIfAbsent(i, key -> build(layout, key));
    }

    private static Geometry build(RailLayout layout, int i) {
        return switch (layout.type(i)) {
            case RailLayout.BEND -> bend(layout, i);
            case RailLayout.RAMP -> ramp(layout, i);
            case RailLayout.SHIFT -> shift(layout, i);
            default -> new Geometry(List.of(), List.of(), List.of());
        };
    }

    /**
     * The real track centre x (block coordinate, like RailLayout#centreX) at block z of an S-bend or diagonal shift:
     * the mean x of the piece's Create curves where they cross row z. The layout's own centre line is a straight
     * interpolation, up to ~2 blocks off Create's bezier; everything built beside the track (walls, railings,
     * the train's space) follows this one. Other pieces, and rows no curve crosses: the layout's value.
     */
    public static double centreX(RailLayout layout, int i, int z) {
        byte type = layout.type(i);
        if (type != RailLayout.BEND && type != RailLayout.SHIFT) {
            return layout.centreX(i, z);
        }
        double[] table;
        synchronized (CENTRES) {
            if (centresLayout != layout || CENTRES.size() > CACHE_LIMIT) {
                CENTRES.clear();
                centresLayout = layout;
            }
            table = CENTRES.computeIfAbsent(i, key -> centres(layout, key));
        }
        int index = layout.zSouth(i) - z;
        double x = index >= 0 && index < table.length ? table[index] : Double.NaN;
        return Double.isNaN(x) ? layout.centreX(i, z) : x;
    }

    private static final Map<Integer, double[]> CENTRES = new java.util.HashMap<>();
    private static RailLayout centresLayout;
    private static final int SAMPLES_PER_BLOCK = 16;

    private static double[] centres(RailLayout layout, int i) {
        int rows = layout.zSouth(i) - layout.zNorth(i) + 1;
        double[] sum = new double[rows];
        int[] count = new int[rows];
        for (Edge edge : of(layout, i).edges()) {
            BezierConnection curve = edge.curve();
            if (curve == null) {
                continue;
            }
            int samples = (int) Math.ceil(curve.getLength() * SAMPLES_PER_BLOCK) + 1;
            for (int s = 0; s <= samples; s++) {
                Vec3 p = curve.getPosition(s / (double) samples);
                int index = layout.zSouth(i) - Mth.floor(p.z);
                if (index >= 0 && index < rows) {
                    // curve positions are block-space (x + 0.5 is a block's middle)
                    sum[index] += p.x - 0.5;
                    count[index]++;
                }
            }
        }
        double[] table = new double[rows];
        for (int r = 0; r < rows; r++) {
            table[r] = count[r] == 0 ? Double.NaN : sum[r] / count[r];
        }
        return table;
    }

    // --- S-bend ----------------------------------------------------------------------------------

    private static Geometry bend(RailLayout l, int i) {
        BlockPos a = new BlockPos(l.xSouth(i), l.ySouth(i), l.zSouth(i));
        BlockPos b = new BlockPos(l.xNorth(i), l.yNorth(i), l.zNorth(i));
        BezierConnection curve = orthoCurve(a, b);
        return new Geometry(
                List.of(new TrackBlock(a, TrackShape.ZO, true), new TrackBlock(b, TrackShape.ZO, true)),
                List.of(new BlockEntity(a, List.of(curve), Double.NaN), new BlockEntity(b, List.of(curve.secondary()), Double.NaN)),
                List.of(curveEdge(curve)));
    }

    // --- ramp -----------------------------------------------------------------------------------

    private static Geometry ramp(RailLayout l, int i) {
        int steps = l.rampSteps(i);
        int run = l.rampRun(i);
        int dir = Integer.signum(l.yNorth(i) - l.ySouth(i));
        int x = l.xSouth(i);
        BlockPos[] ends = new BlockPos[steps + 1];
        for (int j = 0; j <= steps; j++) {
            ends[j] = new BlockPos(x, l.ySouth(i) + dir * j, l.zSouth(i) - j * run);
        }
        BezierConnection[] slopes = new BezierConnection[steps];
        for (int j = 0; j < steps; j++) {
            slopes[j] = orthoCurve(ends[j], ends[j + 1]);
        }
        double[] tilt = new double[steps + 1];
        java.util.Arrays.fill(tilt, Double.NaN);
        for (int j = 1; j < steps; j++) {
            BezierConnection fromShared = slopes[j - 1].secondary();
            tilt[j] = smooth(fromShared, slopes[j]);
            slopes[j - 1] = fromShared.secondary();
        }
        for (int j = 0; j < steps; j++) {
            slopes[j] = slopes[j].clone();
        }

        List<TrackBlock> blocks = new ArrayList<>();
        List<BlockEntity> entities = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        for (int j = 0; j <= steps; j++) {
            blocks.add(new TrackBlock(ends[j], TrackShape.ZO, true));
            List<BezierConnection> connections = new ArrayList<>(2);
            if (j > 0) {
                connections.add(slopes[j - 1].secondary());
            }
            if (j < steps) {
                connections.add(slopes[j]);
            }
            entities.add(new BlockEntity(ends[j], List.copyOf(connections), tilt[j]));
        }
        for (int j = 0; j < steps; j++) {
            if (j > 0) {
                // the tilted block between two slopes: one block of straight track from its southern to its northern edge
                Vec3 southEdge = new Vec3(x + 0.5, ends[j].getY(), ends[j].getZ() + 1);
                Vec3 northEdge = new Vec3(x + 0.5, ends[j].getY(), ends[j].getZ());
                edges.add(new Edge(southEdge, slopes[j - 1].yOffsetAt(southEdge), northEdge, slopes[j].yOffsetAt(northEdge), null, SOUTH));
            }
            edges.add(curveEdge(slopes[j]));
        }
        return new Geometry(List.copyOf(blocks), List.copyOf(entities), List.copyOf(edges));
    }

    /**
     * Port of TrackBlockEntityTilt#tryApplySmoothing for two slopes whose first end is the shared
     * block. Mutates both connections the same way Create does and returns the block's tilt angle.
     */
    private static double smooth(BezierConnection a, BezierConnection b) {
        BezierConnection low = a;
        BezierConnection high = b;
        if (low.starts.getSecond().y > high.starts.getSecond().y) {
            low = b;
            high = a;
        }
        Couple<Vec3> lowStarts = low.starts;
        Couple<Vec3> highStarts = high.starts;
        Vec3 lowest = lowStarts.getSecond();
        Vec3 highest = highStarts.getSecond();
        if (lowest.y > lowStarts.getFirst().y || highest.y < highStarts.getFirst().y) {
            return Double.NaN;
        }
        double hDistance = low.getLength() + high.getLength();
        Vec3 baseAxis = low.axes.getFirst();
        double baseAxisLength = baseAxis.x != 0 && baseAxis.z != 0 ? Math.sqrt(2) : 1;
        double m = (highest.y - lowest.y) / hDistance;

        Vec3 diff = highStarts.getFirst().subtract(lowStarts.getFirst());
        boolean flipRotation = diff.dot(new Vec3(1, 0, 2).normalize()) <= 0;
        double angle = Math.toDegrees(Mth.atan2(m, 1)) * (flipRotation ? -1 : 1);
        int smoothingParam = Mth.clamp((int) (m * baseAxisLength * 16), 0, 15);
        Vec3 raisedOffset = diff.normalize()
                .add(0, Mth.clamp(m, 0, 1 - 1 / 512.0), 0)
                .normalize()
                .scale(baseAxisLength);
        highStarts.setFirst(lowStarts.getFirst().add(raisedOffset));

        applySmoothing(low, 0, -m);
        applySmoothing(high, smoothingParam, m);
        return angle;
    }

    private static void applySmoothing(BezierConnection connection, int smoothing, double axisLift) {
        if (connection.smoothing == null) {
            connection.smoothing = Couple.create(0, 0);
        }
        connection.smoothing.setFirst(smoothing);
        connection.axes.setFirst(connection.axes.getFirst().add(0, axisLift, 0).normalize());
    }

    // --- diagonal shift ----------------------------------------------------------------------------

    private static Geometry shift(RailLayout l, int i) {
        int dir = Integer.signum(l.xNorth(i) - l.xSouth(i));
        int k = l.shiftDiagonal(i);
        int y = l.ySouth(i);
        boolean east = dir > 0;
        TrackShape diagonal = east ? TrackShape.ND : TrackShape.PD;
        Vec3 diagonalAxis = east ? new Vec3(-1, 0, 1) : new Vec3(1, 0, 1);

        BlockPos a = new BlockPos(l.xSouth(i), y, l.zSouth(i));
        BlockPos b = a.offset(dir * RailLayout.SHIFT_CURVE_X, 0, -RailLayout.SHIFT_CURVE_Z);
        BlockPos c = b.offset(dir * k, 0, -k);
        BlockPos d = new BlockPos(l.xNorth(i), y, l.zNorth(i));

        // curve A (z-axis) -> B (diagonal): B's start is the corner facing back towards A
        Vec3 startA = new Vec3(a.getX() + 0.5, y, a.getZ());
        Vec3 startB = east ? new Vec3(b.getX(), y, b.getZ() + 1) : new Vec3(b.getX() + 1, y, b.getZ() + 1);
        Vec3 backFromB = east ? new Vec3(-ROOT_HALF, 0, ROOT_HALF) : new Vec3(ROOT_HALF, 0, ROOT_HALF);
        BezierConnection in = curve(a, b, startA, startB, NORTH, backFromB);

        // curve C (diagonal) -> D (z-axis): C's start is the corner facing on towards D
        Vec3 startC = east ? new Vec3(c.getX() + 1, y, c.getZ()) : new Vec3(c.getX(), y, c.getZ());
        Vec3 startD = new Vec3(d.getX() + 0.5, y, d.getZ() + 1);
        Vec3 onFromC = east ? new Vec3(ROOT_HALF, 0, -ROOT_HALF) : new Vec3(-ROOT_HALF, 0, -ROOT_HALF);
        BezierConnection out = curve(c, d, startC, startD, onFromC, SOUTH);

        List<TrackBlock> blocks = new ArrayList<>();
        blocks.add(new TrackBlock(a, TrackShape.ZO, true));
        for (int j = 0; j <= k; j++) {
            BlockPos p = b.offset(dir * j, 0, -j);
            blocks.add(new TrackBlock(p, diagonal, j == 0 || j == k));
        }
        blocks.add(new TrackBlock(d, TrackShape.ZO, true));

        List<BlockEntity> entities = List.of(
                new BlockEntity(a, List.of(in), Double.NaN),
                new BlockEntity(b, List.of(in.secondary()), Double.NaN),
                new BlockEntity(c, List.of(out), Double.NaN),
                new BlockEntity(d, List.of(out.secondary()), Double.NaN));

        List<Edge> edges = new ArrayList<>();
        edges.add(curveEdge(in));
        // diagonal run from B's start corner to C's start corner; graph nodes where the corner's x is a multiple of 16
        Vec3 previous = startB;
        for (int j = 1; j <= k; j++) {
            Vec3 corner = startB.add(dir * j, 0, -j);
            if (Math.floorMod((int) Math.round(corner.x), 16) == 0) {
                edges.add(new Edge(previous, 0, corner, 0, null, diagonalAxis));
                previous = corner;
            }
        }
        edges.add(new Edge(previous, 0, startC, 0, null, diagonalAxis));
        edges.add(curveEdge(out));
        return new Geometry(List.copyOf(blocks), entities, List.copyOf(edges));
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** Curve between two z-axis track blocks facing each other, the southern one first. */
    private static BezierConnection orthoCurve(BlockPos south, BlockPos north) {
        return curve(south, north,
                new Vec3(south.getX() + 0.5, south.getY(), south.getZ()),
                new Vec3(north.getX() + 0.5, north.getY(), north.getZ() + 1),
                NORTH, SOUTH);
    }

    private static BezierConnection curve(BlockPos first, BlockPos second, Vec3 firstStart, Vec3 secondStart,
                                          Vec3 firstAxis, Vec3 secondAxis) {
        return new BezierConnection(Couple.create(first, second), Couple.create(firstStart, secondStart),
                Couple.create(firstAxis, secondAxis), Couple.create(UP, UP), true, false, TrackMaterial.ANDESITE);
    }

    private static Edge curveEdge(BezierConnection curve) {
        Vec3 south = curve.starts.getFirst();
        Vec3 north = curve.starts.getSecond();
        return new Edge(south, curve.yOffsetAt(south), north, curve.yOffsetAt(north), curve, null);
    }
}
