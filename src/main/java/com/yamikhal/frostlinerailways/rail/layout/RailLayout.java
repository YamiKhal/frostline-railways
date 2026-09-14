package com.yamikhal.frostlinerailways.rail.layout;

import net.minecraft.nbt.CompoundTag;

/**
 * A generated railway line: an ordered list of pieces from the south end (index 0) to the north
 * end, covering every block of z exactly once (RAILWAYS.md §A3.1). Immutable; safe on any thread.
 *
 *   STRAIGHT  flat z-axis track at (xS, yS, z) for z in [zN, zS]
 *   BEND      flat S-bend from end block (xS, yS, zS) to (xN, yN, zN); |xN - xS| &lt;= BEND_MAX_SHIFT
 *   RAMP      |yN - yS| chained slopes of equal length along x = xS, each rising one block; the
 *             blocks between two slopes are tilted by Create's smoothing so the ramp is continuous
 *   SHIFT     flat diagonal jog: 45 degree curve from A = (xS, zS) to B, diagonal track B .. C,
 *             45 degree curve from C to D = (xN, zN); |xN - xS| = 2 * SHIFT_CURVE_X + k, k &gt;= 1
 *
 * A non-straight piece owns its two end blocks. Straights of at least one block separate them, so
 * every straight's neighbours are single end blocks.
 */
public final class RailLayout {

    public static final byte STRAIGHT = 0;
    public static final byte BEND = 1;
    public static final byte RAMP = 2;
    public static final byte SHIFT = 3;

    /** Largest S-bend shift whose two end blocks stay within Create's 32 block placement length. */
    public static final int BEND_MAX_SHIFT = 13;
    /**
     * Footprint of one 45 degree curve between a z-axis track and a diagonal track: 7 blocks across,
     * 17 blocks along z. The curve starts meet with handle lengths 9.5 and 6.5 * sqrt(2) = 9.19,
     * above Create's minimum turn size of 3.25 (TrackPlacement), on whole block positions.
     */
    public static final int SHIFT_CURVE_X = 7;
    public static final int SHIFT_CURVE_Z = 17;

    public final String hash;
    public final String lineId;
    public final double maxDeviation;
    private final byte[] type;
    private final int[] zS;
    private final int[] zN;
    private final int[] xS;
    private final int[] xN;
    private final int[] yS;
    private final int[] yN;
    private final int[] ground;
    private final int groundSpacing;
    private final int minX;
    private final int maxX;

    RailLayout(String hash, String lineId, double maxDeviation,
               byte[] type, int[] zS, int[] zN, int[] xS, int[] xN, int[] yS, int[] yN, int[] ground, int groundSpacing) {
        this.ground = ground;
        this.groundSpacing = Math.max(1, groundSpacing);
        this.hash = hash;
        this.lineId = lineId;
        this.maxDeviation = maxDeviation;
        this.type = type;
        this.zS = zS;
        this.zN = zN;
        this.xS = xS;
        this.xN = xN;
        this.yS = yS;
        this.yN = yN;
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        for (int i = 0; i < type.length; i++) {
            lo = Math.min(lo, Math.min(xS[i], xN[i]));
            hi = Math.max(hi, Math.max(xS[i], xN[i]));
        }
        this.minX = lo;
        this.maxX = hi;
    }

    public int count() {
        return type.length;
    }

    public byte type(int i) {
        return type[i];
    }

    public int zSouth(int i) {
        return zS[i];
    }

    public int zNorth(int i) {
        return zN[i];
    }

    public int xSouth(int i) {
        return xS[i];
    }

    public int xNorth(int i) {
        return xN[i];
    }

    public int ySouth(int i) {
        return yS[i];
    }

    public int yNorth(int i) {
        return yN[i];
    }

    public int zSouthEnd() {
        return zS[0];
    }

    public int zNorthEnd() {
        return zN[type.length - 1];
    }

    public int length() {
        return zSouthEnd() - zNorthEnd() + 1;
    }

    /**
     * Height of the first air above the ground along the track (noise height, before any rail work),
     * interpolated between the layout's samples.
     */
    public int groundAt(int z) {
        if (ground.length == 0) {
            return 0;
        }
        double f = (zSouthEnd() - z) / (double) groundSpacing;
        int i = Math.max(0, Math.min(ground.length - 1, (int) Math.floor(f)));
        int j = Math.min(ground.length - 1, i + 1);
        double t = Math.max(0.0, Math.min(1.0, f - i));
        return (int) Math.round(ground[i] + (ground[j] - ground[i]) * t);
    }

    /** Slopes in a ramp. */
    public int rampSteps(int i) {
        return Math.abs(yN[i] - yS[i]);
    }

    /** Blocks along z per slope of a ramp. */
    public int rampRun(int i) {
        return (zS[i] - zN[i]) / rampSteps(i);
    }

    /** Diagonal blocks of a shift beyond the two curves (k). */
    public int shiftDiagonal(int i) {
        return Math.abs(xN[i] - xS[i]) - 2 * SHIFT_CURVE_X;
    }

    /** True if a box (widened by margin) can contain any of the line's bed. */
    public boolean touches(int minBlockX, int maxBlockX, int minBlockZ, int maxBlockZ, int margin) {
        return maxBlockZ + margin >= zNorthEnd() && minBlockZ - margin <= zSouthEnd()
                && maxBlockX + margin >= minX && minBlockX - margin <= maxX;
    }

    /** Index of the piece covering block z, or -1 outside the line. Binary search. */
    public int pieceAt(int z) {
        int lo = 0;
        int hi = type.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (z > zS[mid]) {
                hi = mid - 1;
            } else if (z < zN[mid]) {
                lo = mid + 1;
            } else {
                return mid;
            }
        }
        return -1;
    }

    /** Track centre x at block z of piece i (block coordinate). */
    public double centreX(int i, int z) {
        switch (type[i]) {
            case BEND -> {
                double t = (zS[i] - z) / (double) (zS[i] - zN[i]);
                return xS[i] + (xN[i] - xS[i]) * t;
            }
            case SHIFT -> {
                int dir = Integer.signum(xN[i] - xS[i]);
                int k = shiftDiagonal(i);
                int bz = zS[i] - SHIFT_CURVE_Z;
                int bx = xS[i] + dir * SHIFT_CURVE_X;
                int cz = bz - k;
                int cx = bx + dir * k;
                if (z >= bz) {
                    return xS[i] + (bx - xS[i]) * (zS[i] - z) / (double) SHIFT_CURVE_Z;
                }
                if (z >= cz) {
                    return bx + dir * (bz - z);
                }
                return cx + (xN[i] - cx) * (cz - z) / (double) SHIFT_CURVE_Z;
            }
            default -> {
                return xS[i];
            }
        }
    }

    /** Track height at block z of piece i (ramps interpolate). */
    public double centreY(int i, int z) {
        if (type[i] != RAMP) {
            return yS[i];
        }
        double t = (zS[i] - z) / (double) (zS[i] - zN[i]);
        return yS[i] + (yN[i] - yS[i]) * t;
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Hash", hash);
        tag.putString("Line", lineId);
        tag.putDouble("MaxDeviation", maxDeviation);
        tag.putByteArray("Type", type);
        tag.putIntArray("ZS", zS);
        tag.putIntArray("ZN", zN);
        tag.putIntArray("XS", xS);
        tag.putIntArray("XN", xN);
        tag.putIntArray("YS", yS);
        tag.putIntArray("YN", yN);
        tag.putIntArray("Ground", ground);
        tag.putInt("GroundSpacing", groundSpacing);
        return tag;
    }

    public static RailLayout fromNbt(CompoundTag tag) {
        return new RailLayout(tag.getString("Hash"), tag.getString("Line"), tag.getDouble("MaxDeviation"),
                tag.getByteArray("Type"), tag.getIntArray("ZS"), tag.getIntArray("ZN"),
                tag.getIntArray("XS"), tag.getIntArray("XN"), tag.getIntArray("YS"), tag.getIntArray("YN"),
                tag.getIntArray("Ground"), tag.getInt("GroundSpacing"));
    }
}
