package com.yamikhal.frostlinerailways.rail.decor;

import com.yamikhal.frostlinerailways.RailwaysConfig;
import com.yamikhal.frostlinerailways.rail.layout.RailLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Where the stations go (RAILWAYS.md §A8.5, §A8.6). Computed once per layout and station data from the
 * layout, the seed, noise and the config's spawnZ only, so every chunk and the runtime agree.
 */
public final class StationPlanner {

    public record Site(ResourceLocation id, RailStation def, int index, int zCentre, int trackX, int bedY, int sign) {
        public int zSouth() {
            return zCentre + def.length() / 2;
        }

        public int zNorth() {
            return zCentre - def.length() / 2;
        }

        /** The station's name: its "name" with {n} replaced by its number along the line. */
        public String name() {
            return def.service().name().replace("{n}", Integer.toString(index + 1));
        }

        /** Its Create station blocks. */
        public List<Stop> stops() {
            RailStation.Service service = def.service();
            if (!service.stationBlock()) {
                return List.of();
            }
            boolean north = !"south".equals(service.direction());
            boolean south = !"north".equals(service.direction());
            // never inside the space trains drive through
            int x = trackX + sign * Math.max(def.look().gap(), RailwaysConfig.trainHalfWidth() + 1);
            List<Stop> list = new ArrayList<>(2);
            if (north) {
                list.add(new Stop(new BlockPos(x, bedY, zNorth() + STOP_INSET), true, south ? name() + " (northbound)" : name()));
            }
            if (south) {
                list.add(new Stop(new BlockPos(x, bedY, zSouth() - STOP_INSET), false, north ? name() + " (southbound)" : name()));
            }
            return list;
        }
    }

    /**
     * A Create station block. A Create station serves one direction of travel. A northbound stop stands
     * at the platform's north end pointed south (TargetDirection positive): trains travelling north stop
     * with their front at it and their carriages along the platform, and a train assembled there stands
     * on the platform. A southbound stop mirrors it at the south end.
     */
    public record Stop(BlockPos pos, boolean northbound, String name) {
    }

    private static final int STOP_INSET = 2;
    private static final int MARGIN = 4;
    private static final int SEARCH_STEP = 8;
    private static final int SPAWN_SEARCH = 1024;
    private static final int SITE_GAP = 64;

    private static volatile Object ownerLayout;
    private static volatile Object ownerStations;
    private static volatile List<Site> sites = List.of();

    private StationPlanner() {
    }

    /** All sites, south to north. */
    public static List<Site> sites(RailContext ctx) {
        List<RailDecorData.Entry<RailStation>> stations = RailDecorData.STATIONS.entries();
        if (ownerLayout == ctx.layout && ownerStations == stations) {
            return sites;
        }
        synchronized (StationPlanner.class) {
            if (ownerLayout != ctx.layout || ownerStations != stations) {
                sites = plan(ctx, stations);
                ownerStations = stations;
                ownerLayout = ctx.layout;
            }
            return sites;
        }
    }

    /** The spawn station, or null. */
    public static Site spawnSite(RailContext ctx) {
        for (Site site : sites(ctx)) {
            if (site.def().where().spawn()) {
                return site;
            }
        }
        return null;
    }

    private static List<Site> plan(RailContext ctx, List<RailDecorData.Entry<RailStation>> stations) {
        RailLayout layout = ctx.layout;
        List<Site> list = new ArrayList<>();
        for (RailDecorData.Entry<RailStation> entry : stations) {
            if (entry.value().where().spawn() && list.isEmpty()) {
                Site site = spawn(ctx, entry);
                if (site != null) {
                    list.add(site);
                }
            }
        }
        for (RailDecorData.Entry<RailStation> entry : stations) {
            RailStation def = entry.value();
            if (def.where().spawn()) {
                continue;
            }
            long salt = entry.id().toString().hashCode();
            int index = 0;
            for (int target = layout.zSouthEnd() - def.where().firstAt(); target - def.length() > layout.zNorthEnd();
                 target -= def.where().spacing(), index++) {
                // the flattest good spot in the window: one point per 32 blocks past the target, one per block off the ground
                Site best = null;
                double bestScore = Double.MAX_VALUE;
                for (int z = target; z > target - def.where().spacing() / 2 && z - def.length() > layout.zNorthEnd(); z -= SEARCH_STEP) {
                    Site site = tryAt(ctx, entry, salt, index, z, false);
                    if (site == null || !clear(list, site)) {
                        continue;
                    }
                    double score = (target - z) / 32.0 + offGround(ctx, site);
                    if (score < bestScore) {
                        best = site;
                        bestScore = score;
                    }
                }
                if (best != null) {
                    list.add(best);
                }
            }
        }
        list.sort(Comparator.comparingInt(Site::zCentre).reversed());
        return List.copyOf(list);
    }

    /** The best spot near spawnZ: one point per 32 blocks away, one per block the track is off the ground on average. */
    private static Site spawn(RailContext ctx, RailDecorData.Entry<RailStation> entry) {
        RailLayout layout = ctx.layout;
        int half = entry.value().length() / 2 + MARGIN + 1;
        int target = Math.max(layout.zNorthEnd() + half, Math.min(layout.zSouthEnd() - half, RailwaysConfig.spawnZ()));
        Site best = null;
        double bestScore = Double.MAX_VALUE;
        for (int d = 0; d <= SPAWN_SEARCH && d / 32.0 < bestScore; d += SEARCH_STEP) {
            for (int z : d == 0 ? new int[] {target} : new int[] {target - d, target + d}) {
                Site site = tryAt(ctx, entry, 0, 0, z, true);
                if (site == null) {
                    continue;
                }
                double score = d / 32.0 + offGround(ctx, site);
                if (score < bestScore) {
                    best = site;
                    bestScore = score;
                }
            }
        }
        return best;
    }

    /** Mean distance between the track and the ground along a site. */
    private static double offGround(RailContext ctx, Site site) {
        double sum = 0;
        int n = 0;
        for (int z = site.zSouth(); z >= site.zNorth(); z -= 2) {
            sum += Math.abs(site.bedY() - ctx.layout.groundAt(z));
            n++;
        }
        return sum / Math.max(1, n);
    }

    private static boolean clear(List<Site> sites, Site site) {
        for (Site other : sites) {
            if (Math.abs(other.zCentre() - site.zCentre()) < (other.def().length() + site.def().length()) / 2 + SITE_GAP) {
                return false;
            }
        }
        return true;
    }

    private static Site tryAt(RailContext ctx, RailDecorData.Entry<RailStation> entry, long salt, int index, int centre, boolean spawn) {
        RailStation def = entry.value();
        RailLayout layout = ctx.layout;
        int south = centre + def.length() / 2 + MARGIN;
        int north = centre - def.length() / 2 - MARGIN;
        if (south > layout.zSouthEnd() || north < layout.zNorthEnd()) {
            return null;
        }
        int piece = layout.pieceAt(south);
        if (piece < 0 || layout.type(piece) != RailLayout.STRAIGHT || layout.zNorth(piece) > north) {
            return null;
        }
        for (int z = south; z >= north; z -= 2) {
            RailContext.Kind kind = ctx.row(z).kind();
            if (kind == RailContext.Kind.TUNNEL || kind == RailContext.Kind.BRIDGE) {
                return null;
            }
        }
        RailContext.Row row = ctx.row(centre);
        if (!spawn && !BiomeFilter.allows(def.where().biomes(), def.where().excludeBiomes(), ctx.biome(row.trackX(), row.bedY(), centre))) {
            return null;
        }
        if (!spawn && ctx.random(salt, index, 0) >= def.where().chance()) {
            return null;
        }
        int sign = switch (def.where().side()) {
            case "east", "right" -> 1;
            case "west", "left" -> -1;
            default -> index % 2 == 0 ? 1 : -1;
        };
        return new Site(entry.id(), def, index, centre, row.trackX(), row.bedY(), sign);
    }
}
