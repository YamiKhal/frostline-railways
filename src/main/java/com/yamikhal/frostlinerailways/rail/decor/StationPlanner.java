package com.yamikhal.frostlinerailways.rail.decor;

import com.yamikhal.frostlinerailways.rail.layout.RailLayout;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the stations go (RAILWAYS.md §A8.5). Computed once per layout and station data from the layout,
 * the seed and noise only, so every chunk agrees.
 */
public final class StationPlanner {

    public record Site(ResourceLocation id, RailStation def, int zCentre, int trackX, int bedY, int sign) {
        public int zSouth() {
            return zCentre + def.length() / 2;
        }

        public int zNorth() {
            return zCentre - def.length() / 2;
        }
    }

    private static final int MARGIN = 4;
    private static final int SEARCH_STEP = 8;

    private static volatile Object ownerLayout;
    private static volatile Object ownerStations;
    private static volatile List<Site> sites = List.of();

    private StationPlanner() {
    }

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

    private static List<Site> plan(RailContext ctx, List<RailDecorData.Entry<RailStation>> stations) {
        RailLayout layout = ctx.layout;
        List<Site> list = new ArrayList<>();
        for (RailDecorData.Entry<RailStation> entry : stations) {
            RailStation def = entry.value();
            long salt = entry.id().toString().hashCode();
            int index = 0;
            for (int target = layout.zSouthEnd() - def.firstAt(); target - def.length() > layout.zNorthEnd(); target -= def.spacing(), index++) {
                for (int z = target; z > target - def.spacing() / 2 && z - def.length() > layout.zNorthEnd(); z -= SEARCH_STEP) {
                    Site site = tryAt(ctx, entry, salt, index, z);
                    if (site != null) {
                        list.add(site);
                        break;
                    }
                }
            }
        }
        return List.copyOf(list);
    }

    private static Site tryAt(RailContext ctx, RailDecorData.Entry<RailStation> entry, long salt, int index, int centre) {
        RailStation def = entry.value();
        RailLayout layout = ctx.layout;
        int south = centre + def.length() / 2 + MARGIN;
        int north = centre - def.length() / 2 - MARGIN;
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
        if (!BiomeFilter.allows(def.biomes(), def.excludeBiomes(), ctx.biome(row.trackX(), row.bedY(), centre))) {
            return null;
        }
        if (ctx.random(salt, index, 0) >= def.chance()) {
            return null;
        }
        int sign = switch (def.side()) {
            case "east", "right" -> 1;
            case "west", "left" -> -1;
            default -> index % 2 == 0 ? 1 : -1;
        };
        return new Site(entry.id(), def, centre, row.trackX(), row.bedY(), sign);
    }
}
