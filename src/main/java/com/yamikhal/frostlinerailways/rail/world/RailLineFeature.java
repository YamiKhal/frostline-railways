package com.yamikhal.frostlinerailways.rail.world;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.yamikhal.frostlinerailways.FrostlineRailways;
import com.yamikhal.frostlinerailways.RailwaysConfig;
import com.yamikhal.frostlinerailways.rail.RailLineDef;
import com.yamikhal.frostlinerailways.rail.decor.RailContext;
import com.yamikhal.frostlinerailways.rail.layout.RailLayout;
import com.yamikhal.frostlinerailways.rail.layout.RailLayoutService;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicLong;

/**
 * frostline_railways:rail_line — the railway, written into each chunk as the world generates
 * (RAILWAYS.md §A3.2). Added to every Frostline biome at the end of top_layer_modification by
 * the mod's own biome modifier.
 *
 * Chunks away from the line return after one bounds check (R4). Line chunks bake the bed
 * ({@link BedBaker}), write track ({@link TrackWriter}) and place additions, termini and stations
 * ({@link Decorator}). No scheduled ticks, no graph work, no chunk loads (R1, R2). No Create classes
 * here: TrackWriter is only reached with Create loaded.
 */
public class RailLineFeature extends Feature<NoneFeatureConfiguration> {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicLong CHUNKS = new AtomicLong();
    private static final AtomicLong NANOS = new AtomicLong();
    /** Widest reach of anything the line places sideways: bed, tunnel lining, platforms, additions. */
    private static final int SIDE_REACH = 32;

    public RailLineFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        if (!FrostlineRailways.createLoaded() || !RailwaysConfig.railEnabled()) {
            return false;
        }
        WorldGenLevel level = ctx.level();
        RailLayout layout = RailLayoutService.layout(level.getLevel().dimension(), true);
        RailLineDef def = RailLayoutService.definition();
        if (layout == null || def == null) {
            return false;
        }
        ChunkPos chunk = new ChunkPos(ctx.origin());
        if (!layout.touches(chunk.getMinBlockX() - SIDE_REACH, chunk.getMaxBlockX() + SIDE_REACH,
                chunk.getMinBlockZ() - Decorator.REACH, chunk.getMaxBlockZ() + Decorator.REACH, 0)) {
            return false;
        }
        long start = System.nanoTime();
        RailContext context = new RailContext(level, layout, def);
        BedBaker.bake(level, chunk, context);
        TrackWriter.write(level, chunk, layout);
        Decorator.decorate(level, chunk, context);
        CoverLayer.apply(level, chunk, context);
        if (RailwaysConfig.railPerfLogging()) {
            long chunks = CHUNKS.incrementAndGet();
            long nanos = NANOS.addAndGet(System.nanoTime() - start);
            if (chunks % 128 == 0) {
                LOGGER.info("[railperf] rail worldgen: {} line chunks, {} µs average", chunks, nanos / chunks / 1000);
            }
        }
        return true;
    }
}
