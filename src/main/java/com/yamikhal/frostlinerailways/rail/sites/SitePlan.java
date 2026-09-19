package com.yamikhal.frostlinerailways.rail.sites;

import com.yamikhal.frostlinerailways.rail.decor.RailStation;
import com.yamikhal.frostlinerailways.rail.decor.StationPlanner;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The structures along the line for one world (RAILWAYS.md §A8.15): every planned rail site with the recipe that
 * regenerates it (structure, start chunk, seed), each station's district and building override, and the boxes no
 * other structure may come near (station footprints, rail sites). Immutable; safe on any thread.
 *
 * {@code owners} are what the plan was made from: layout and datapack entry lists (same instances) and the settings
 * it reads (same values). The plan is reused while they match.
 */
public final class SitePlan {

    public enum Kind { LINE, CORE, OUTER }

    /**
     * One rail site. {@code distance}: line sites from the track centre, cores from the station's back edge, outer
     * structures from the core (blocks to the nearest block). {@code station}: index in the station list, or -1.
     */
    public record Placed(Kind kind, ResourceLocation source, ResourceKey<Structure> structure, ChunkPos chunk, long seed,
                         BoundingBox box, int side, int station, int distance, boolean facingMet) {
    }

    /** A station's district (null = none), its building override (null = its own), and its footprint. */
    public record StationInfo(StationPlanner.Site site, ResourceLocation district, RailStation.Templates override,
                              BoundingBox footprint) {
    }

    final Object[] owners;
    private final List<Placed> placed;
    private final Map<Long, List<Placed>> byChunk;
    private final Map<Integer, StationInfo> stations;
    /** Station footprints and rail site boxes, sorted by minZ, for range lookups along the line. */
    private final BoundingBox[] guarded;
    private final int[] guardedMinZ;
    private final int guardedMaxSpanZ;
    public final long millis;
    public final Map<String, Integer> failures;

    SitePlan(Object[] owners, List<Placed> placed, Map<Integer, StationInfo> stations, long millis, Map<String, Integer> failures) {
        this.owners = owners;
        this.placed = List.copyOf(placed);
        this.stations = Map.copyOf(stations);
        this.millis = millis;
        this.failures = Map.copyOf(failures);
        Map<Long, List<Placed>> index = new HashMap<>();
        List<BoundingBox> boxes = new ArrayList<>();
        for (Placed p : this.placed) {
            index.computeIfAbsent(p.chunk().toLong(), k -> new ArrayList<>()).add(p);
            boxes.add(p.box());
        }
        for (StationInfo info : this.stations.values()) {
            boxes.add(info.footprint());
        }
        index.replaceAll((k, v) -> List.copyOf(v));
        this.byChunk = Map.copyOf(index);
        boxes.sort(java.util.Comparator.comparingInt(BoundingBox::minZ));
        this.guarded = boxes.toArray(new BoundingBox[0]);
        this.guardedMinZ = new int[guarded.length];
        int span = 0;
        for (int i = 0; i < guarded.length; i++) {
            guardedMinZ[i] = guarded[i].minZ();
            span = Math.max(span, guarded[i].getZSpan());
        }
        this.guardedMaxSpanZ = span;
    }

    boolean ownedBy(Object[] current) {
        if (current.length != owners.length) {
            return false;
        }
        for (int i = 0; i < owners.length; i++) {
            Object a = current[i];
            Object b = owners[i];
            boolean value = a instanceof Number || a instanceof Boolean;
            if (value ? !a.equals(b) : a != b) {
                return false;
            }
        }
        return true;
    }

    /** All rail sites, in planning order (districts south to north, then line sites per file). */
    public List<Placed> placed() {
        return placed;
    }

    /** Rail sites whose start is in this chunk. */
    public List<Placed> startsIn(ChunkPos chunk) {
        return byChunk.getOrDefault(chunk.toLong(), List.of());
    }

    /** The station's district, override and footprint; null if the station is not in this plan. */
    public StationInfo station(StationPlanner.Site site) {
        StationInfo info = stations.get(site.zCentre());
        return info != null && info.site().id().equals(site.id()) ? info : null;
    }

    /**
     * True if the box comes within {@code margin} blocks of a station footprint or a rail site: horizontally closer than
     * the margin, and overlapping it in height (widened by the margin), so something deep underground can pass below.
     */
    public boolean guardedNear(BoundingBox box, int margin) {
        int from = java.util.Arrays.binarySearch(guardedMinZ, box.minZ() - margin - guardedMaxSpanZ - 1);
        from = from < 0 ? -from - 1 : from;
        for (int i = from; i < guarded.length && guardedMinZ[i] <= box.maxZ() + margin; i++) {
            BoundingBox guard = guarded[i];
            if (LineBand.gap(box, guard) < margin && box.maxY() >= guard.minY() - margin && box.minY() <= guard.maxY() + margin) {
                return true;
            }
        }
        return false;
    }
}
