package com.yamikhal.frostlinerailways.rail.decor;

import com.yamikhal.frostlinerailways.RailwaysConfig;

/**
 * The space trains drive through (RAILWAYS.md §A8.9): on every row of the line, the columns within
 * trainHalfWidth of the track centre (Create track is 3 wide, plus one free block each side: 5 by default),
 * from track height up trainHeight blocks. Nothing the line places — templates, additions, station blocks,
 * scatter — goes in there. Computed from the layout rows only, so every chunk agrees.
 */
public final class Envelope {

    private final RailContext ctx;
    private final int halfWidth;
    private final int height;

    private Envelope(RailContext ctx, int halfWidth, int height) {
        this.ctx = ctx;
        this.halfWidth = halfWidth;
        this.height = height;
    }

    public static Envelope of(RailContext ctx) {
        return new Envelope(ctx, RailwaysConfig.trainHalfWidth(), RailwaysConfig.trainHeight());
    }

    /** True if (x, y, z) is inside the space trains drive through (outside the line's rows: never). */
    public boolean contains(int x, int y, int z) {
        RailContext.Row row = ctx.row(z);
        if (row == null || y < row.bedY() || y >= row.bedY() + height) {
            return false;
        }
        return Math.abs(x - row.centreX()) <= halfWidth + 0.5;
    }
}
