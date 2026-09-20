package com.yamikhal.frostlinerailways.rail.world;

import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

/**
 * Seeded noise that roughens the bed's blending (RAILWAYS.md §A8.16), so berms and cut slopes read as hillside
 * instead of a ruled 45° ramp. A function of world seed and position only: every chunk, in any order and on any
 * thread, computes the same slope (SimplexNoise only reads its permutation table after construction).
 */
final class Relief {

    private static final long SALT = 0x6A3D51C7F00DL;
    private static volatile Relief cached;

    private final long seed;
    private final SimplexNoise noise;

    private Relief(long seed) {
        this.seed = seed;
        this.noise = new SimplexNoise(new LegacyRandomSource(seed ^ SALT));
    }

    static Relief of(long seed) {
        Relief relief = cached;
        if (relief == null || relief.seed != seed) {
            relief = new Relief(seed);
            cached = relief;
        }
        return relief;
    }

    /** -1..1: broad lumps (~11 blocks) with a rough layer (~4 blocks) on top. */
    double lumps(int x, int z) {
        return 0.7 * noise.getValue(x / 11.0, z / 11.0) + 0.3 * noise.getValue(x / 3.7 + 311.0, z / 3.7 - 173.0);
    }

    /** 0..1, slow along the line (~24 blocks) and independent per side: how much of the configured reach a row uses. */
    double reach(int side, int z) {
        return 0.5 + 0.5 * noise.getValue(z / 24.0, side * 1000.0 + 57.0);
    }
}
