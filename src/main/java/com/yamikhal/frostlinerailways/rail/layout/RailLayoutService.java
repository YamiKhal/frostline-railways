package com.yamikhal.frostlinerailways.rail.layout;

import com.mojang.logging.LogUtils;
import com.yamikhal.frostlinerailways.RailwaysConfig;
import com.yamikhal.frostlinerailways.rail.RailLineDef;
import com.yamikhal.frostlinerailways.rail.RailLineLoader;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.level.LevelEvent;
import org.slf4j.Logger;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the world's line layout (RAILWAYS.md §A3.1, rule R3).
 *
 * When the line's level loads, the layout is read from &lt;world&gt;/data/frostline_railways_line.dat
 * or built on a dedicated thread and written there. Worldgen threads that need it wait on the
 * same future, so it is computed once even while spawn chunks generate.
 *
 * A stored layout is kept even if the line definition changed since, so an existing world's
 * track never moves; /frostline rail layout rebuild replaces it.
 */
public final class RailLayoutService {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Bump when the builder's output changes for the same inputs. */
    private static final int ALGORITHM = 3;
    private static final String FILE = "frostline_railways_line.dat";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "FrostlineRailways-Layout");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile CompletableFuture<RailLayout> future;
    private static volatile ResourceKey<Level> dimension;
    private static volatile RailLineDef definition;
    private static volatile ServerLevel level;

    private RailLayoutService() {
    }

    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && RailwaysConfig.railEnabled()) {
            start(level, false);
        }
    }

    /** Starts loading or building the layout for this level if it is the line's dimension. */
    public static boolean start(ServerLevel level, boolean rebuild) {
        ResourceLocation lineId = ResourceLocation.tryParse(RailwaysConfig.lineId());
        RailLineDef def = RailLineLoader.get(lineId);
        if (def == null) {
            if (level.dimension() == Level.OVERWORLD) {
                LOGGER.warn("[FrostlineRailways] rail line {} not found in any datapack; no railway", lineId);
            }
            return false;
        }
        if (!level.dimension().location().equals(def.dimension())) {
            return false;
        }
        File file = level.getServer().getWorldPath(LevelResource.ROOT).resolve("data").resolve(FILE).toFile();
        String hash = ALGORITHM + "|" + level.getSeed() + "|" + lineId + "|" + def + "|" + RaiseRules.fingerprint();
        dimension = level.dimension();
        definition = def;
        RailLayoutService.level = level;
        future = CompletableFuture.supplyAsync(() -> loadOrBuild(level, def, lineId.toString(), hash, file, rebuild), EXECUTOR);
        return true;
    }

    private static RailLayout loadOrBuild(ServerLevel level, RailLineDef def, String lineId, String hash, File file, boolean rebuild) {
        try {
            if (file.isFile() && !rebuild) {
                RailLayout stored = RailLayout.fromNbt(NbtIo.readCompressed(file));
                if (!stored.hash.equals(hash)) {
                    LOGGER.warn("[FrostlineRailways] rail line definition changed since this world's line was generated; "
                            + "keeping the existing line (/frostline rail layout rebuild replaces it for new chunks)");
                }
                LOGGER.info("[FrostlineRailways] rail layout loaded: {} pieces, z {} to {}",
                        stored.count(), stored.zSouthEnd(), stored.zNorthEnd());
                return stored;
            }
            RailLayout layout = LayoutBuilder.build(level, def, lineId, hash);
            file.getParentFile().mkdirs();
            NbtIo.writeCompressed(layout.toNbt(), file);
            return layout;
        } catch (Exception e) {
            LOGGER.error("[FrostlineRailways] rail layout failed; no railway in this world", e);
            return null;
        }
    }

    /** The layout for this dimension; waits for it if {@code wait}, else null while it is being built. */
    public static RailLayout layout(ResourceKey<Level> level, boolean wait) {
        CompletableFuture<RailLayout> current = future;
        if (current == null || level != dimension) {
            return null;
        }
        return wait ? current.join() : current.getNow(null);
    }

    public static boolean pending() {
        CompletableFuture<RailLayout> current = future;
        return current != null && !current.isDone();
    }

    public static ResourceKey<Level> dimension() {
        return dimension;
    }

    public static RailLineDef definition() {
        return definition;
    }

    /** The line's level, or null. Only for its generator, random state, registries and templates: never read its chunks here. */
    public static ServerLevel level() {
        return level;
    }

    /**
     * The layout if {@code random} is the line level's random state (i.e. worldgen for the line's dimension), waiting
     * for it while it is being built; else null. For code that only has worldgen objects (structure starts).
     */
    public static RailLayout layoutFor(net.minecraft.world.level.levelgen.RandomState random) {
        ServerLevel current = level;
        if (current == null || random == null || current.getChunkSource().randomState() != random) {
            return null;
        }
        return layout(current.dimension(), true);
    }

    public static void stop() {
        future = null;
        dimension = null;
        definition = null;
        level = null;
    }
}
