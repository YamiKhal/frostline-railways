package com.yamikhal.frostlinerailways.rail.sites;

import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generates a structure's pieces for a rail site (RAILWAYS.md §A8.15): the structure's own
 * {@code findValidGenerationPoint}, with a {@link Structure.GenerationContext} whose random comes from the site's seed
 * instead of the chunk, and every biome accepted (the site/district file decides biomes).
 *
 * Everything a structure reads while generating is noise (heights, noise biomes) or templates, so the same
 * (structure, chunk, seed) gives the same pieces at plan time and again when the start chunk generates.
 */
final class SiteGenerator {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int FACING_TRIES = 12;
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /** What a structure needs to generate: registries, noise, templates, seed, build height. No chunks. */
    record Env(RegistryAccess access, ChunkGenerator generator, RandomState random, StructureTemplateManager templates, long seed,
               LevelHeightAccessor height) {
    }

    /**
     * A generated candidate: the pieces, their bounding box, each piece's box widened the way the structure's
     * terrain adaptation reaches ({@link Structure#adjustBoundingBox}), and the start piece's rotation (or null).
     */
    record Result(Holder.Reference<Structure> structure, ChunkPos chunk, long seed, PiecesContainer pieces, BoundingBox box,
                  List<BoundingBox> adjusted, Rotation rotation) {
    }

    private SiteGenerator() {
    }

    /** The structure's pieces started in {@code chunk} with {@code seed}; null if it does not generate there. */
    static PiecesContainer pieces(Env env, Structure structure, ResourceLocation id, ChunkPos chunk, long seed) {
        try {
            WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(seed));
            Structure.GenerationContext context = new Structure.GenerationContext(env.access(), env.generator(),
                    env.generator().getBiomeSource(), env.random(), env.templates(), random, env.seed(), chunk, env.height(),
                    biome -> true);
            Optional<Structure.GenerationStub> stub = structure.findValidGenerationPoint(context);
            if (stub.isEmpty()) {
                return null;
            }
            PiecesContainer pieces = stub.get().getPiecesBuilder().build();
            return pieces.isEmpty() ? null : pieces;
        } catch (RuntimeException e) {
            if (WARNED.add("error " + id)) {
                LOGGER.error("[FrostlineRailways] rail site structure {} failed to generate", id, e);
            }
            return null;
        }
    }

    static Result generate(Env env, Holder.Reference<Structure> structure, ChunkPos chunk, long seed) {
        PiecesContainer pieces = pieces(env, structure.value(), structure.key().location(), chunk, seed);
        if (pieces == null) {
            return null;
        }
        List<BoundingBox> adjusted = new ArrayList<>(pieces.pieces().size());
        for (StructurePiece piece : pieces.pieces()) {
            adjusted.add(structure.value().adjustBoundingBox(piece.getBoundingBox()));
        }
        return new Result(structure, chunk, seed, pieces, pieces.calculateBoundingBox(), List.copyOf(adjusted),
                pieces.pieces().get(0).getRotation());
    }

    /**
     * Like {@link #generate}, trying up to FACING_TRIES seeds derived from {@code base} until the start piece has the
     * {@code wanted} rotation (null = any); jigsaw draws its start rotation from the generation random, so each seed
     * is a one-in-four chance. If none matches, the first seed that generated is used. Structures whose rotation never
     * changes with the seed (they roll it from their own random) are logged once: facing cannot apply to them.
     */
    static Result facing(Env env, Holder.Reference<Structure> structure, ChunkPos chunk, long base, Rotation wanted) {
        Result first = null;
        int failed = 0;
        boolean turns = false;
        int generated = 0;
        for (int k = 0; k < FACING_TRIES; k++) {
            Result result = generate(env, structure, chunk, seed(base, k));
            if (result == null) {
                // two seeds that give nothing and none that worked: the structure does not start at this chunk
                if (++failed >= 2 && first == null) {
                    break;
                }
                continue;
            }
            generated++;
            if (wanted == null || result.rotation() == wanted) {
                return result;
            }
            if (first == null) {
                first = result;
            } else if (result.rotation() != first.rotation()) {
                turns = true;
            }
        }
        if (generated >= 4 && !turns && WARNED.add("facing " + structure.key().location())) {
            LOGGER.info("[FrostlineRailways] rail site structure {} does not turn with its generation random; facing is ignored for it "
                    + "(use a minecraft:jigsaw structure to face it)", structure.key().location());
        }
        return first;
    }

    /** Seed k of a family: splitmix of base and k. */
    static long seed(long base, int k) {
        long z = base + (k + 1) * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    static void warnOnce(String key, String message, Object... args) {
        if (WARNED.add(key)) {
            LOGGER.warn(message, args);
        }
    }
}
