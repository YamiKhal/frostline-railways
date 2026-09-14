package com.yamikhal.frostlinerailways.rail.decor;

import com.yamikhal.frostlinerailways.rail.RailLineDef;
import com.yamikhal.frostlinerailways.rail.layout.RailLayout;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the line is doing at each row of z, for one chunk's worldgen (RAILWAYS.md §A8.5).
 *
 * Everything here is computed from the layout, the seed and noise only — the stored ground samples,
 * getNoiseBiome, and hashes — never from the chunks, so every chunk that looks at a row (its own, or a
 * neighbour placing something across the border) sees the same answer whatever order they generate in.
 *
 *   kind     TUNNEL  ground at least tunnelMinCover above the style's tunnel height
 *            CUT     ground above the track
 *            BRIDGE  gap under the track deeper than maxFill
 *            OPEN    otherwise
 *   portal   a tunnel row next to a row that is not
 *   style    the section's style (256-block sections, see {@link RailStyle})
 */
public final class RailContext {

    public enum Kind { OPEN, CUT, TUNNEL, BRIDGE }

    public record Row(int z, int piece, double centreX, int bedY, int groundTop, Kind kind, boolean portal,
                      RailDecorData.Entry<RailStyle> style) {
        public int trackX() {
            return (int) Math.round(centreX);
        }
    }

    public static final int SECTION = 256;
    private static final int CACHE_LIMIT = 16384;

    public final RailLayout layout;
    public final RailLineDef def;
    public final long seed;
    private final ChunkGenerator generator;
    private final RandomState random;
    private final Map<Integer, Row> rows = new HashMap<>();

    private static volatile Object cacheOwner;
    private static final Map<Integer, RailDecorData.Entry<RailStyle>> SECTION_STYLES = new ConcurrentHashMap<>();

    public RailContext(WorldGenLevel level, RailLayout layout, RailLineDef def) {
        this.layout = layout;
        this.def = def;
        this.seed = level.getSeed();
        this.generator = level.getLevel().getChunkSource().getGenerator();
        this.random = level.getLevel().getChunkSource().randomState();
    }

    /** The row at z, or null outside the line. */
    public Row row(int z) {
        if (z > layout.zSouthEnd() || z < layout.zNorthEnd()) {
            return null;
        }
        return rows.computeIfAbsent(z, this::computeRow);
    }

    private Row computeRow(int z) {
        int piece = layout.pieceAt(z);
        double centreX = layout.centreX(piece, z);
        int bedY = (int) Math.floor(layout.centreY(piece, z));
        RailDecorData.Entry<RailStyle> style = style(z);
        Kind kind = kind(z, style.value());
        boolean portal = kind == Kind.TUNNEL && (neighbourKind(z + 1) != Kind.TUNNEL || neighbourKind(z - 1) != Kind.TUNNEL);
        return new Row(z, piece, centreX, bedY, layout.groundAt(z) - 1, kind, portal, style);
    }

    private Kind neighbourKind(int z) {
        if (z > layout.zSouthEnd() || z < layout.zNorthEnd()) {
            return Kind.OPEN;
        }
        return kind(z, style(z).value());
    }

    private Kind kind(int z, RailStyle style) {
        int piece = layout.pieceAt(z);
        int bedY = (int) Math.floor(layout.centreY(piece, z));
        int groundTop = layout.groundAt(z) - 1;
        if (groundTop >= bedY + style.tunnel().height() + def.bed().tunnelMinCover()) {
            return Kind.TUNNEL;
        }
        if (groundTop >= bedY) {
            return Kind.CUT;
        }
        if (bedY - 1 - groundTop > def.bed().maxFill()) {
            return Kind.BRIDGE;
        }
        return Kind.OPEN;
    }

    /** The style of the section containing z. */
    public RailDecorData.Entry<RailStyle> style(int z) {
        List<RailDecorData.Entry<RailStyle>> styles = RailDecorData.STYLES.entries();
        if (cacheOwner != layout || SECTION_STYLES.size() > CACHE_LIMIT) {
            SECTION_STYLES.clear();
            cacheOwner = layout;
        }
        int section = Math.floorDiv(layout.zSouthEnd() - z, SECTION);
        return SECTION_STYLES.computeIfAbsent(section, s -> pickStyle(s, styles));
    }

    private RailDecorData.Entry<RailStyle> pickStyle(int section, List<RailDecorData.Entry<RailStyle>> styles) {
        int z = Math.max(layout.zNorthEnd(), layout.zSouthEnd() - section * SECTION - SECTION / 2);
        int piece = layout.pieceAt(z);
        Holder<Biome> biome = biome((int) Math.round(layout.centreX(piece, z)), (int) layout.centreY(piece, z), z);
        int best = Integer.MIN_VALUE;
        List<RailDecorData.Entry<RailStyle>> candidates = new ArrayList<>();
        for (RailDecorData.Entry<RailStyle> entry : styles) {
            RailStyle style = entry.value();
            if (!BiomeFilter.allows(style.biomes(), style.excludeBiomes(), biome)) {
                continue;
            }
            if (style.priority() > best) {
                best = style.priority();
                candidates.clear();
            }
            if (style.priority() == best) {
                candidates.add(entry);
            }
        }
        if (candidates.isEmpty()) {
            return new RailDecorData.Entry<>(new net.minecraft.resources.ResourceLocation("frostline_railways", "line_default"),
                    RailStyle.fromLine(def));
        }
        int total = candidates.stream().mapToInt(e -> e.value().weight()).sum();
        double roll = random(0x5747_4C45L, section, 0) * total;
        for (RailDecorData.Entry<RailStyle> entry : candidates) {
            roll -= entry.value().weight();
            if (roll < 0) {
                return entry;
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    /** The biome the noise puts at a block, without touching any chunk. */
    public Holder<Biome> biome(int x, int y, int z) {
        return generator.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z), random.sampler());
    }

    /** Uniform [0, 1) from the seed, a salt and two coordinates. */
    public double random(long salt, int a, int b) {
        long h = seed ^ (salt * 0x9E3779B97F4A7C15L);
        h = mix(h + a * 0xBF58476D1CE4E5B9L);
        h = mix(h + b * 0x94D049BB133111EBL);
        return (h >>> 11) * 0x1.0p-53;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
