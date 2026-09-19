package com.yamikhal.frostlinerailways.rail.sites;

import com.mojang.logging.LogUtils;
import com.yamikhal.frostlinerailways.RailwaysConfig;
import com.yamikhal.frostlinerailways.rail.RailLineDef;
import com.yamikhal.frostlinerailways.rail.decor.BiomeFilter;
import com.yamikhal.frostlinerailways.rail.decor.RailContext;
import com.yamikhal.frostlinerailways.rail.decor.RailDecorData;
import com.yamikhal.frostlinerailways.rail.decor.RailStation;
import com.yamikhal.frostlinerailways.rail.decor.RailTemplates;
import com.yamikhal.frostlinerailways.rail.decor.StationPlanner;
import com.yamikhal.frostlinerailways.rail.layout.RailLayout;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.ToIntFunction;

/**
 * Plans every structure along the line (RAILWAYS.md §A8.15): station districts first (south to north), then line
 * sites (per file, sorted by id). Reads the layout, noise, registries and templates only — never a chunk — so the
 * plan is the same whichever thread builds it and whatever has generated.
 *
 * A candidate is generated with {@link SiteGenerator} at a first-guess chunk, moved by whole chunks along x until its
 * distance is in range (jigsaw starts at the chunk corner), then checked: reference reach, band and side, overlap,
 * one start per structure per chunk, ground (slope, water), rows and visibility for line sites. The first candidate
 * that passes is kept; failures are counted by reason for the summary log.
 */
final class SitePlanner {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** ChunkGenerator#createReferences looks for starts this many chunks around a chunk. */
    private static final int REFERENCE_CHUNKS = 8;
    /** First guess of the distance from a structure's start corner to its near edge. */
    private static final int GUESS = 8;
    private static final int MAX_SHIFTS = 3;
    /** Structures move in whole chunks, so a distance band is at least this wide. */
    private static final int MIN_BAND = 16;
    private static final int EYE = 3;
    private static final int SIGHT_STEP = 4;
    private static final int[] EYE_OFFSETS = {0, 48, -48};
    /** Station footprints reach this far past the station's ends. */
    private static final int FOOTPRINT_MARGIN = 4;
    private static final long DISTRICT_SALT = 0x44495354L;

    private record Occupied(BoundingBox box, int station, int group) {
    }

    private record Footprint(BoundingBox box, int outward) {
    }

    private record StartKey(ResourceKey<Structure> structure, long chunk) {
    }

    private final SiteGenerator.Env env;
    private final RailContext ctx;
    private final RailLayout layout;
    private final MinecraftServer server;
    private final Registry<Structure> registry;
    private final int clearance;
    private final int siteMargin;
    private final List<SitePlan.Placed> placed = new ArrayList<>();
    private final List<Occupied> occupied = new ArrayList<>();
    private final Map<Integer, SitePlan.StationInfo> stations = new HashMap<>();
    private final Set<StartKey> starts = new HashSet<>();
    private final Map<Long, Integer> surface = new HashMap<>();
    private final Map<Long, Integer> floor = new HashMap<>();
    private final Map<String, Integer> failures = new TreeMap<>();
    private int groups;

    private SitePlanner(ServerLevel level, RailLayout layout, RailLineDef def) {
        this.server = level.getServer();
        this.env = new SiteGenerator.Env(level.registryAccess(), level.getChunkSource().getGenerator(),
                level.getChunkSource().randomState(), level.getStructureManager(), level.getSeed(), level);
        this.ctx = new RailContext(level.getSeed(), env.generator(), env.random(), layout, def);
        this.layout = layout;
        this.registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        this.clearance = RailwaysConfig.clearance();
        this.siteMargin = RailwaysConfig.siteMargin();
    }

    /** The plan for this level; with {@code sites} false only station footprints (still guarded), no rail sites. */
    static SitePlan build(ServerLevel level, RailLayout layout, RailLineDef def, boolean sites, Object[] owners) {
        long start = System.nanoTime();
        SitePlanner planner = new SitePlanner(level, layout, def);
        List<StationPlanner.Site> stationSites = StationPlanner.sites(planner.ctx);
        planner.footprints(stationSites);
        if (sites) {
            planner.districts(stationSites);
            planner.lineSites(stationSites);
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        SitePlan plan = new SitePlan(owners, planner.placed, planner.stations, millis, planner.failures);
        int[] counts = new int[SitePlan.Kind.values().length];
        for (SitePlan.Placed p : planner.placed) {
            counts[p.kind().ordinal()]++;
        }
        long districts = planner.stations.values().stream().filter(s -> s.district() != null).count();
        LOGGER.info("[FrostlineRailways] rail sites: {} line sites, {} district cores, {} outer structures ({} of {} stations with a district) "
                        + "planned in {} ms; rejected spots by reason: {}",
                counts[SitePlan.Kind.LINE.ordinal()], counts[SitePlan.Kind.CORE.ordinal()], counts[SitePlan.Kind.OUTER.ordinal()],
                districts, stationSites.size(), millis, planner.failures);
        return plan;
    }

    // --- stations ------------------------------------------------------------------------------

    private void footprints(List<StationPlanner.Site> sites) {
        for (int i = 0; i < sites.size(); i++) {
            StationPlanner.Site site = sites.get(i);
            Footprint fp = footprint(site, null);
            occupied.add(new Occupied(fp.box(), i, -1));
            stations.put(site.zCentre(), new SitePlan.StationInfo(site, null, null, fp.box()));
        }
    }

    /**
     * Where a station's building stands: along the line from its north end (4 blocks margin each end), across from
     * its inward side to its back edge (the farthest outward block: template rows outward of track_z, the plain
     * platform, or the Create stop), from its foundation to above its roof. {@code outward}: track centre to back edge.
     */
    private Footprint footprint(StationPlanner.Site site, RailStation.Templates override) {
        RailStation.Look look = site.def().look();
        StationPlanner.Building building = StationPlanner.building(ctx, server, site, override);
        int stop = Math.max(look.gap(), RailwaysConfig.trainHalfWidth() + 1);
        int outward = Math.max(stop, look.gap() + look.width() - 1);
        int inward = 0;
        int length = site.def().length() + 1;
        int height = look.clearance() + 1;
        int depth = 2;
        if (building.file() != null && building.spec().isPresent()) {
            RailTemplates.Structure template = RailTemplates.structure(server, building.file());
            if (template != null) {
                RailStation.Template spec = building.spec().get();
                outward = Math.max(stop, spec.trackZ());
                inward = Math.max(0, template.sizeZ() - 1 - spec.trackZ());
                length = template.sizeX();
                height = Math.max(1, template.sizeY() - spec.trackY());
                depth = spec.foundationDepth() + spec.trackY();
                if (override != null && template.sizeX() != site.def().length()) {
                    SiteGenerator.warnOnce("length " + building.file(),
                            "[FrostlineRailways] district station building {} is {} blocks long but station {} is {}; it will not line up with the platform",
                            building.file(), template.sizeX(), site.id(), site.def().length());
                }
            }
        }
        int sign = site.sign();
        int x0 = site.trackX() - sign * inward;
        int x1 = site.trackX() + sign * outward;
        int zNorth = site.zNorth();
        int zSouth = Math.max(site.zSouth(), zNorth + length - 1);
        BoundingBox box = new BoundingBox(Math.min(x0, x1), site.bedY() - depth, zNorth - FOOTPRINT_MARGIN,
                Math.max(x0, x1), site.bedY() + height + FOOTPRINT_MARGIN, zSouth + FOOTPRINT_MARGIN);
        return new Footprint(box, outward);
    }

    private void setFootprint(int station, BoundingBox box) {
        for (int i = 0; i < occupied.size(); i++) {
            if (occupied.get(i).station() == station) {
                occupied.set(i, new Occupied(box, station, -1));
                return;
            }
        }
    }

    // --- districts -----------------------------------------------------------------------------

    private void districts(List<StationPlanner.Site> sites) {
        List<RailDecorData.Entry<RailDistrict>> all = RailDecorData.DISTRICTS.entries();
        if (all.isEmpty()) {
            return;
        }
        for (int i = 0; i < sites.size(); i++) {
            StationPlanner.Site site = sites.get(i);
            Holder<Biome> biome = ctx.biome(site.trackX(), site.bedY(), site.zCentre());
            List<RailDecorData.Entry<RailDistrict>> eligible = new ArrayList<>();
            for (RailDecorData.Entry<RailDistrict> entry : all) {
                RailDistrict.Match match = entry.value().match();
                if (match.weight() > 0 && (match.stations().isEmpty() || match.stations().contains(site.id()))
                        && BiomeFilter.allows(match.biomes(), match.excludeBiomes(), biome)) {
                    eligible.add(entry);
                }
            }
            for (int roll = 0; !eligible.isEmpty(); roll++) {
                RailDecorData.Entry<RailDistrict> pick = weighted(eligible, ctx.random(DISTRICT_SALT, site.zCentre(), roll));
                if (pick.value().core().isEmpty()) {
                    // a district without a core is the "nothing here" choice
                    break;
                }
                if (district(i, site, pick)) {
                    break;
                }
                eligible.remove(pick);
            }
        }
    }

    private static RailDecorData.Entry<RailDistrict> weighted(List<RailDecorData.Entry<RailDistrict>> entries, double roll) {
        int total = entries.stream().mapToInt(e -> e.value().match().weight()).sum();
        double r = roll * total;
        for (RailDecorData.Entry<RailDistrict> entry : entries) {
            r -= entry.value().match().weight();
            if (r < 0) {
                return entry;
            }
        }
        return entries.get(entries.size() - 1);
    }

    /** Places a district's core and outer circle at station {@code index}; false (and nothing kept) if the core fails. */
    private boolean district(int index, StationPlanner.Site site, RailDecorData.Entry<RailDistrict> entry) {
        RailDistrict district = entry.value();
        RailDistrict.Core core = district.core().orElseThrow();
        RailStation.Templates override = district.station().filter(t -> !t.isEmpty()).orElse(null);
        SitePlan.StationInfo before = stations.get(site.zCentre());
        Footprint fp = footprint(site, override);
        setFootprint(index, fp.box());

        int sign = site.sign();
        int backEdge = site.trackX() + sign * fp.outward();
        RailContext.Row row = ctx.row(site.zCentre());
        // the core may not come nearer the track than the band allows, whatever its distance says
        int bandFloor = LineBand.halfWidth(ctx, row) + clearance + 1 - fp.outward();
        SiteRange range = core.distance().atLeast(bandFloor, MIN_BAND);
        long salt = entry.id().toString().hashCode();
        int group = groups++;
        ToIntFunction<BoundingBox> measure = box -> sign > 0 ? box.minX() - backEdge : backEdge - box.maxX();

        SitePlan.Placed corePlaced = null;
        for (int t = 0; t < core.tries() && corePlaced == null; t++) {
            int along = core.along().roll(ctx.random(salt, site.zCentre(), 100 + t));
            int distance = range.roll(ctx.random(salt, site.zCentre(), 200 + t));
            int z = site.zCentre() - along;
            int anchorX = backEdge + sign * (distance + GUESS);
            Holder.Reference<Structure> structure = pickStructure(core.structures(), anchorX, z, ctx.random(salt, site.zCentre(), 300 + t));
            if (structure == null) {
                fail("core_no_structure");
                continue;
            }
            Rotation wanted = rotation(core.fit(), "station", sign, anchorX, z, 0, 0, ctx.random(salt, site.zCentre(), 400 + t));
            SiteGenerator.Result result = shifted("core_", structure, anchorX, z, sign, wanted, seed(salt, site.zCentre(), t), measure, distance, range);
            if (result == null) {
                continue;
            }
            String why = check(result, sign, core.fit(), index, group, siteMargin, 0);
            if (why != null) {
                fail("core_" + why);
                continue;
            }
            corePlaced = add(SitePlan.Kind.CORE, entry.id(), result, sign, index, group, measure.applyAsInt(result.box()), wanted);
        }
        if (corePlaced == null) {
            setFootprint(index, before.footprint());
            return false;
        }
        stations.put(site.zCentre(), new SitePlan.StationInfo(site, entry.id(), override, fp.box()));
        if (district.outer().isPresent()) {
            outer(index, site, entry, district.outer().get(), corePlaced, group);
        }
        return true;
    }

    /** The outer circle: each slot tries spots in the half plane on the station's side, at the outer distance from the core. */
    private void outer(int index, StationPlanner.Site site, RailDecorData.Entry<RailDistrict> entry, RailDistrict.Outer outer,
                       SitePlan.Placed core, int group) {
        int sign = site.sign();
        long salt = entry.id().toString().hashCode() ^ 0x4F55544552L;
        BoundingBox coreBox = core.box();
        double cx = (coreBox.minX() + coreBox.maxX()) / 2.0;
        double cz = (coreBox.minZ() + coreBox.maxZ()) / 2.0;
        double coreHalf = Math.max(coreBox.getXSpan(), coreBox.getZSpan()) / 2.0;
        int count = outer.count().roll(ctx.random(salt, site.zCentre(), 0));
        for (int slot = 0; slot < count; slot++) {
            boolean done = false;
            for (int t = 0; t < outer.tries() && !done; t++) {
                int key = slot * 64 + t;
                double angle = Math.toRadians(-110 + 220 * ctx.random(salt, site.zCentre(), 1000 + key));
                int distance = outer.distance().roll(ctx.random(salt, site.zCentre(), 2000 + key));
                double reach = coreHalf + distance + GUESS;
                int ax = (int) Math.round(cx + sign * Math.cos(angle) * reach);
                int az = (int) Math.round(cz + Math.sin(angle) * reach);
                Holder.Reference<Structure> structure = pickStructure(outer.structures(), ax, az, ctx.random(salt, site.zCentre(), 3000 + key));
                if (structure == null) {
                    fail("outer_no_structure");
                    continue;
                }
                Rotation wanted = rotation(outer.fit(), "core", sign, ax, az, (int) cx, (int) cz, ctx.random(salt, site.zCentre(), 4000 + key));
                SiteGenerator.Result result = SiteGenerator.facing(env, structure, chunkAt(ax, az), seed(salt, site.zCentre(), key), wanted);
                if (result == null) {
                    fail("outer_generation");
                    continue;
                }
                int gap = LineBand.gap(result.box(), coreBox);
                if (gap < outer.distance().min() || gap > outer.distance().max() + MIN_BAND) {
                    fail("outer_distance");
                    continue;
                }
                String why = check(result, sign, outer.fit(), index, group, siteMargin, outer.spacing());
                if (why != null) {
                    fail("outer_" + why);
                    continue;
                }
                add(SitePlan.Kind.OUTER, entry.id(), result, sign, index, group, gap, wanted);
                done = true;
            }
        }
    }

    // --- line sites ----------------------------------------------------------------------------

    private void lineSites(List<StationPlanner.Site> stationSites) {
        for (RailDecorData.Entry<RailSite> entry : RailDecorData.SITES.entries()) {
            RailSite def = entry.value();
            RailSite.Where where = def.where();
            long salt = entry.id().toString().hashCode();
            int bandFloor = ctx.def.bed().halfWidth() + clearance + 1;
            SiteRange range = where.distance().atLeast(bandFloor, MIN_BAND);
            if (!range.equals(where.distance())) {
                SiteGenerator.warnOnce("range " + entry.id(), "[FrostlineRailways] site {}: distance {} widened to {} "
                        + "(at least {} to clear the track band, at least {} wide for 16-block steps)", entry.id(), where.distance(), range, bandFloor, MIN_BAND);
            }
            int step = Math.max(8, where.spacing() / 2 / where.tries());
            int count = 0;
            int slot = 0;
            for (int target = layout.zSouthEnd() - where.firstAt(); target > layout.zNorthEnd(); target -= where.spacing(), slot++) {
                if (where.maxCount() >= 0 && count >= where.maxCount()) {
                    break;
                }
                if (ctx.random(salt, slot, 1) >= where.chance()) {
                    continue;
                }
                for (int sign : sides(where.side(), salt, slot)) {
                    if (where.maxCount() >= 0 && count >= where.maxCount()) {
                        break;
                    }
                    if (lineSite(entry, range, stationSites, salt, slot, sign, target, step)) {
                        count++;
                    }
                }
            }
        }
    }

    private int[] sides(String side, long salt, int slot) {
        return switch (side.toLowerCase(Locale.ROOT)) {
            case "east", "right" -> new int[] {1};
            case "west", "left" -> new int[] {-1};
            case "both" -> new int[] {1, -1};
            case "alternate" -> new int[] {slot % 2 == 0 ? 1 : -1};
            default -> new int[] {ctx.random(salt, slot, 2) < 0.5 ? 1 : -1};
        };
    }

    private boolean lineSite(RailDecorData.Entry<RailSite> entry, SiteRange range, List<StationPlanner.Site> stationSites, long salt,
                             int slot, int sign, int target, int step) {
        RailSite def = entry.value();
        RailSite.Where where = def.where();
        int group = groups++;
        ToIntFunction<BoundingBox> measure = box -> LineBand.nearest(ctx, box, sign);
        for (int t = 0; t < where.tries(); t++) {
            int z = target - t * step;
            if (z < layout.zNorthEnd()) {
                break;
            }
            int key = slot * 128 + t * 2 + (sign > 0 ? 0 : 1);
            if (nearStation(stationSites, z, z, where.stationMargin())) {
                fail("line_near_station");
                continue;
            }
            RailContext.Row row = ctx.row(z);
            if (!where.rows().contains(row.kind().name().toLowerCase(Locale.ROOT))) {
                fail("line_row");
                continue;
            }
            int distance = range.roll(ctx.random(salt, key, 3));
            int anchorX = row.trackX() + sign * (distance + GUESS);
            Holder<Biome> anchorBiome = biomeAt(anchorX, z);
            if (!BiomeFilter.allows(where.biomes(), where.excludeBiomes(), anchorBiome)) {
                fail("line_biome");
                continue;
            }
            Holder.Reference<Structure> structure = pickStructure(def.structures(), anchorX, z, ctx.random(salt, key, 4));
            if (structure == null) {
                fail("line_no_structure");
                continue;
            }
            Rotation wanted = rotation(def.fit(), "track", sign, anchorX, z, 0, 0, ctx.random(salt, key, 5));
            SiteGenerator.Result result = shifted("line_", structure, anchorX, z, sign, wanted, seed(salt, slot, key), measure, distance, range);
            if (result == null) {
                continue;
            }
            BoundingBox box = result.box();
            if (nearStation(stationSites, box.minZ(), box.maxZ(), where.stationMargin())) {
                fail("line_near_station");
                continue;
            }
            int cx = (box.minX() + box.maxX()) / 2;
            int cz = (box.minZ() + box.maxZ()) / 2;
            if (!BiomeFilter.allows(where.biomes(), where.excludeBiomes(), biomeAt(cx, cz))) {
                fail("line_biome");
                continue;
            }
            if (!rowsAllowed(box, where.rows())) {
                fail("line_row");
                continue;
            }
            String why = check(result, sign, def.fit(), -1, group, Math.max(siteMargin, where.minSeparation()), 0);
            if (why != null) {
                fail("line_" + why);
                continue;
            }
            if (def.fit().visible() && !visible(box, sign)) {
                fail("line_not_visible");
                continue;
            }
            add(SitePlan.Kind.LINE, entry.id(), result, sign, -1, group, measure.applyAsInt(box), wanted);
            return true;
        }
        return false;
    }

    private static boolean nearStation(List<StationPlanner.Site> sites, int zFrom, int zTo, int margin) {
        int lo = Math.min(zFrom, zTo);
        int hi = Math.max(zFrom, zTo);
        for (StationPlanner.Site site : sites) {
            if (hi >= site.zNorth() - margin && lo <= site.zSouth() + margin) {
                return true;
            }
        }
        return false;
    }

    private boolean rowsAllowed(BoundingBox box, List<String> rows) {
        for (int z = box.minZ(); z <= box.maxZ(); z += 4) {
            if (z > layout.zSouthEnd() || z < layout.zNorthEnd()) {
                continue;
            }
            if (!rows.contains(ctx.row(z).kind().name().toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    // --- candidates ----------------------------------------------------------------------------

    /**
     * Generates at the chunk of (anchorX, anchorZ), then moves the start by whole chunks along x (towards {@code target})
     * until the measured distance is in {@code range}; the same seed each time, so a jigsaw keeps its shape and only
     * moves. Null if it does not generate or cannot be brought into range.
     */
    private SiteGenerator.Result shifted(String prefix, Holder.Reference<Structure> structure, int anchorX, int anchorZ, int sign,
                                         Rotation wanted, long seed, ToIntFunction<BoundingBox> measure, int target, SiteRange range) {
        SiteGenerator.Result result = SiteGenerator.facing(env, structure, chunkAt(anchorX, anchorZ), seed, wanted);
        if (result == null) {
            fail(prefix + "generation");
            return null;
        }
        for (int i = 0; i < MAX_SHIFTS; i++) {
            int distance = measure.applyAsInt(result.box());
            if (range.contains(distance)) {
                return result;
            }
            if (distance == Integer.MAX_VALUE) {
                // nothing of the line beside it (past an end): no distance to correct
                fail(prefix + "distance");
                return null;
            }
            int chunks = Math.round((target - distance) / 16.0F);
            if (chunks == 0) {
                chunks = distance < range.min() ? 1 : -1;
            }
            ChunkPos next = new ChunkPos(result.chunk().x + sign * chunks, result.chunk().z);
            SiteGenerator.Result moved = SiteGenerator.generate(env, structure, next, result.seed());
            if (moved == null) {
                fail(prefix + "generation");
                return null;
            }
            result = moved;
        }
        if (range.contains(measure.applyAsInt(result.box()))) {
            return result;
        }
        fail(prefix + "distance");
        return null;
    }

    /**
     * Checks shared by every kind; null if the candidate may stay, else the reason. {@code station}: the candidate's own
     * station (it may touch that footprint but not overlap it), or -1. {@code group}: its district or site; boxes of the
     * same group keep {@code groupGap}, other rail sites {@code siteGap}, other stations siteMargin.
     */
    private String check(SiteGenerator.Result result, int sign, SiteFit fit, int station, int group, int siteGap, int groupGap) {
        ChunkPos start = result.chunk();
        for (StructurePiece piece : result.pieces().pieces()) {
            BoundingBox box = piece.getBoundingBox();
            if (SectionPos.blockToSectionCoord(box.minX()) < start.x - REFERENCE_CHUNKS
                    || SectionPos.blockToSectionCoord(box.maxX()) > start.x + REFERENCE_CHUNKS
                    || SectionPos.blockToSectionCoord(box.minZ()) < start.z - REFERENCE_CHUNKS
                    || SectionPos.blockToSectionCoord(box.maxZ()) > start.z + REFERENCE_CHUNKS) {
                SiteGenerator.warnOnce("reach " + result.structure().key().location(),
                        "[FrostlineRailways] rail site structure {} reaches more than {} chunks from its start; such spots are skipped "
                                + "(lower its max_distance_from_center)", result.structure().key().location(), REFERENCE_CHUNKS);
                return "reference_reach";
            }
        }
        for (BoundingBox adjusted : result.adjusted()) {
            if (!LineBand.clearOn(ctx, adjusted, sign, clearance)) {
                return "band";
            }
        }
        BoundingBox box = result.box();
        for (Occupied other : occupied) {
            int need;
            if (other.station() >= 0) {
                need = other.station() == station ? 0 : siteMargin;
            } else {
                need = other.group() == group ? groupGap : siteGap;
            }
            if (LineBand.gap(box, other.box()) < need) {
                return other.station() >= 0 ? "overlap_station" : "overlap_site";
            }
        }
        if (starts.contains(new StartKey(result.structure().key(), result.chunk().toLong()))) {
            return "same_start_chunk";
        }
        return ground(box, fit);
    }

    /** Slope over the box's corners and centre, and noise water under any of them. */
    private String ground(BoundingBox box, SiteFit fit) {
        int[][] points = {
                {box.minX(), box.minZ()}, {box.maxX(), box.minZ()}, {box.minX(), box.maxZ()}, {box.maxX(), box.maxZ()},
                {(box.minX() + box.maxX()) / 2, (box.minZ() + box.maxZ()) / 2}};
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        for (int[] p : points) {
            int h = surface(p[0], p[1]);
            lo = Math.min(lo, h);
            hi = Math.max(hi, h);
            if (!fit.allowWater() && floor(p[0], p[1]) < h) {
                return "water";
            }
        }
        return hi - lo > fit.maxSlope() ? "slope" : null;
    }

    /**
     * A line of sight from an eye EYE above the track (at the box's middle row and 48 blocks either way) to a point EYE
     * above the ground at the middle of the box's near face clears the noise terrain. Columns the line clears beside
     * the track (bed, cleared width) are not tested.
     */
    private boolean visible(BoundingBox box, int sign) {
        int zt = (box.minZ() + box.maxZ()) / 2;
        int xt = sign > 0 ? box.minX() : box.maxX();
        int yt = Math.max(surface(xt, zt), box.minY()) + EYE;
        int cleared = ctx.def.bed().halfWidth() + (RailwaysConfig.clearAboveTrack() ? RailwaysConfig.clearExtraWidth() : 0) + 1;
        for (int offset : EYE_OFFSETS) {
            int ze = Math.max(layout.zNorthEnd(), Math.min(layout.zSouthEnd(), zt + offset));
            RailContext.Row eye = ctx.row(ze);
            double xe = eye.centreX();
            int ye = eye.bedY() + EYE;
            double dx = xt - xe;
            double dz = zt - ze;
            int samples = (int) (Math.sqrt(dx * dx + dz * dz) / SIGHT_STEP);
            boolean clear = true;
            for (int k = 1; k < samples && clear; k++) {
                double f = k / (double) samples;
                double x = xe + dx * f;
                int z = (int) Math.round(ze + dz * f);
                RailContext.Row row = z <= layout.zSouthEnd() && z >= layout.zNorthEnd() ? ctx.row(z) : null;
                if (row != null && Math.abs(x - row.centreX()) <= cleared) {
                    continue;
                }
                if (surface((int) Math.floor(x), z) > ye + (yt - ye) * f + 1) {
                    clear = false;
                }
            }
            if (clear) {
                return true;
            }
        }
        return false;
    }

    private SitePlan.Placed add(SitePlan.Kind kind, ResourceLocation source, SiteGenerator.Result result, int sign, int station,
                                int group, int distance, Rotation wanted) {
        SitePlan.Placed p = new SitePlan.Placed(kind, source, result.structure().key(), result.chunk(), result.seed(), result.box(),
                sign, station, distance, wanted == null || wanted == result.rotation());
        placed.add(p);
        occupied.add(new Occupied(result.box(), -1, group));
        starts.add(new StartKey(result.structure().key(), result.chunk().toLong()));
        return p;
    }

    // --- helpers -------------------------------------------------------------------------------

    private Holder.Reference<Structure> pickStructure(List<SiteEntry> entries, int x, int z, double roll) {
        SiteEntry entry = SiteEntry.pick(entries, biomeAt(x, z), roll);
        if (entry == null) {
            return null;
        }
        Optional<Holder.Reference<Structure>> holder = registry.getHolder(ResourceKey.create(Registries.STRUCTURE, entry.structure()));
        if (holder.isEmpty()) {
            SiteGenerator.warnOnce("unknown " + entry.structure(), "[FrostlineRailways] rail site structure {} is not a worldgen/structure "
                    + "in any datapack; skipped", entry.structure());
            return null;
        }
        return holder.get();
    }

    /**
     * The rotation that turns the structure's front (as saved) towards the wanted direction; null = any.
     * track/station: towards the track; away: away from it; along: north or south; core: towards (tx, tz).
     */
    private static Rotation rotation(SiteFit fit, String fallback, int sign, int x, int z, int tx, int tz, double roll) {
        Direction target = switch (fit.facingOr(fallback).toLowerCase(Locale.ROOT)) {
            case "track", "station" -> sign > 0 ? Direction.WEST : Direction.EAST;
            case "away" -> sign > 0 ? Direction.EAST : Direction.WEST;
            case "along" -> roll < 0.5 ? Direction.NORTH : Direction.SOUTH;
            case "core" -> Math.abs(tx - x) >= Math.abs(tz - z)
                    ? (tx > x ? Direction.EAST : Direction.WEST)
                    : (tz > z ? Direction.SOUTH : Direction.NORTH);
            default -> null;
        };
        if (target == null) {
            return null;
        }
        Direction front = fit.horizontalFront();
        for (Rotation r : Rotation.values()) {
            if (r.rotate(front) == target) {
                return r;
            }
        }
        return null;
    }

    private Holder<Biome> biomeAt(int x, int z) {
        return ctx.biome(x, surface(x, z), z);
    }

    private int surface(int x, int z) {
        return surface.computeIfAbsent(key(x, z), k -> env.generator().getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, env.height(), env.random()));
    }

    private int floor(int x, int z) {
        return floor.computeIfAbsent(key(x, z), k -> env.generator().getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, env.height(), env.random()));
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static ChunkPos chunkAt(int x, int z) {
        return new ChunkPos(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
    }

    private long seed(long salt, int a, int b) {
        return SiteGenerator.seed(ctx.seed ^ salt * 0x9E3779B97F4A7C15L, a * 31 + b);
    }

    private void fail(String reason) {
        failures.merge(reason, 1, Integer::sum);
    }
}
