package com.yamikhal.frostlinerailways.rail.layout;

import com.mojang.logging.LogUtils;
import com.yamikhal.frostlinerailways.RailwaysConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-biome track height from config [frostline_rail.profile] biomeRaise (RAILWAYS.md §A8.8): entries
 * "biome_id=amount" or "#biome_tag=amount", first match wins.
 *
 *   amount &gt;= 0   the profile aims for the track that many blocks above the ground (0 = hug it)
 *   amount -1     hug the ground; with minusOneIsHighest, hold the highest ground of the next
 *                 highestLookahead blocks instead (the old §A8.4 profile), weighted by highestStrictness
 */
public final class RaiseRules {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** raise() result for "hold the highest ground ahead". */
    public static final int HIGHEST = Integer.MIN_VALUE;

    private record Rule(TagKey<Biome> tag, ResourceLocation id, int amount) {
    }

    private final List<Rule> rules;
    private final boolean minusOneIsHighest;

    private RaiseRules(List<Rule> rules, boolean minusOneIsHighest) {
        this.rules = rules;
        this.minusOneIsHighest = minusOneIsHighest;
    }

    public static RaiseRules fromConfig() {
        List<Rule> list = new ArrayList<>();
        for (String entry : RailwaysConfig.biomeRaise()) {
            try {
                int eq = entry.lastIndexOf('=');
                String key = entry.substring(0, eq).trim();
                int amount = Integer.parseInt(entry.substring(eq + 1).trim());
                if (key.startsWith("#")) {
                    list.add(new Rule(TagKey.create(Registries.BIOME, new ResourceLocation(key.substring(1))), null, amount));
                } else {
                    list.add(new Rule(null, new ResourceLocation(key), amount));
                }
            } catch (RuntimeException e) {
                LOGGER.warn("[FrostlineRailways] ignoring biomeRaise entry '{}' (expected biome_id=amount or #tag=amount)", entry);
            }
        }
        return new RaiseRules(List.copyOf(list), RailwaysConfig.minusOneIsHighest());
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /** Blocks above the ground the track should aim for in this biome, or {@link #HIGHEST}. */
    public int raise(Holder<Biome> biome) {
        for (Rule rule : rules) {
            boolean match = rule.tag() != null ? biome.is(rule.tag())
                    : biome.unwrapKey().map(key -> key.location().equals(rule.id())).orElse(false);
            if (match) {
                if (rule.amount() == -1) {
                    return minusOneIsHighest ? HIGHEST : 0;
                }
                return Math.max(0, rule.amount());
            }
        }
        return 0;
    }

    /** Everything that changes the profile, for the layout hash. */
    public static String fingerprint() {
        return RailwaysConfig.biomeRaise() + "|" + RailwaysConfig.minusOneIsHighest() + "|" + RailwaysConfig.highestLookahead()
                + "|" + RailwaysConfig.highestStrictness();
    }
}
