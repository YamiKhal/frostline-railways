package com.yamikhal.frostlinerailways.rail.sites;

import com.mojang.logging.LogUtils;
import com.yamikhal.frostlinerailways.FrostlineRailways;
import com.yamikhal.frostlinerailways.RailwaysConfig;
import com.yamikhal.frostlinerailways.rail.RailLineDef;
import com.yamikhal.frostlinerailways.rail.decor.RailContext;
import com.yamikhal.frostlinerailways.rail.decor.RailDecorData;
import com.yamikhal.frostlinerailways.rail.decor.RailStation;
import com.yamikhal.frostlinerailways.rail.decor.StationPlanner;
import com.yamikhal.frostlinerailways.rail.layout.RailLayout;
import com.yamikhal.frostlinerailways.rail.layout.RailLayoutService;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Structures along the line, at chunk time (RAILWAYS.md §A8.15). Called from the two worldgen mixins, the
 * Decorator and /frostline rail sites.
 *
 *   plan          the world's {@link SitePlan}, built on first use by whichever thread asks (the others wait), rebuilt
 *                 when the layout or the station/site/district data are replaced (/reload, layout rebuild)
 *   createStarts  ChunkGenerator#createStructures, after vanilla's: the rail sites that start in this chunk
 *   exclude       Structure#generate, on its result: drop a structure start that comes too near the track, a station
 *                 or a rail site
 *
 * Only for the line's level: everything is keyed on the level's RandomState, so other dimensions (and worlds without
 * a line, or without Create) pass straight through.
 */
public final class RailSites {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Structures kept avoidMargin away from the track at every height (datapack tag). */
    public static final TagKey<Structure> AVOID = TagKey.create(Registries.STRUCTURE, new ResourceLocation("frostline", "rail/avoid"));
    private static final AtomicLong DROPPED = new AtomicLong();
    private static volatile SitePlan plan;

    private RailSites() {
    }

    /** The plan for the line level whose random state this is; null elsewhere (other levels, no line, no Create). */
    public static SitePlan plan(RandomState random) {
        if (!FrostlineRailways.createLoaded()) {
            return null;
        }
        RailLayout layout = RailLayoutService.layoutFor(random);
        ServerLevel level = RailLayoutService.level();
        RailLineDef def = RailLayoutService.definition();
        if (layout == null || level == null || def == null) {
            return null;
        }
        Object[] owners = {layout, RailDecorData.STATIONS.entries(), RailDecorData.SITES.entries(), RailDecorData.DISTRICTS.entries(),
                RailwaysConfig.railSites(), RailDecorData.STYLES.entries()};
        SitePlan current = plan;
        if (current != null && current.ownedBy(owners)) {
            return current;
        }
        synchronized (RailSites.class) {
            current = plan;
            if (current == null || !current.ownedBy(owners)) {
                current = SitePlanner.build(level, layout, def, RailwaysConfig.railSites(), owners);
                plan = current;
            }
            return current;
        }
    }

    /** The plan for the line's level (commands). */
    public static SitePlan plan() {
        ServerLevel level = RailLayoutService.level();
        return level == null ? null : plan(level.getChunkSource().randomState());
    }

    /** Wild structure starts dropped by the exclusion band since the game started. */
    public static long dropped() {
        return DROPPED.get();
    }

    /** A station's building override from its district, or null. */
    public static RailStation.Templates stationOverride(RandomState random, StationPlanner.Site site) {
        SitePlan current = plan(random);
        SitePlan.StationInfo info = current == null ? null : current.station(site);
        return info == null ? null : info.override();
    }

    /**
     * After vanilla's structure starts for a chunk: the rail sites planned to start here, regenerated from their recipe
     * (the same pieces the plan measured) and stored like any start.
     */
    public static void createStarts(ChunkGenerator generator, RegistryAccess access, ChunkGeneratorStructureState state,
                                    StructureManager manager, ChunkAccess chunk, StructureTemplateManager templates) {
        SitePlan current = plan(state.randomState());
        if (current == null) {
            return;
        }
        List<SitePlan.Placed> here = current.startsIn(chunk.getPos());
        if (here.isEmpty()) {
            return;
        }
        Registry<Structure> registry = access.registryOrThrow(Registries.STRUCTURE);
        SectionPos section = SectionPos.bottomOf(chunk);
        SiteGenerator.Env env = new SiteGenerator.Env(access, generator, state.randomState(), templates, state.getLevelSeed(), chunk);
        for (SitePlan.Placed placed : here) {
            Structure structure = registry.get(placed.structure());
            if (structure == null) {
                continue;
            }
            StructureStart existing = manager.getStartForStructure(section, structure, chunk);
            if (existing != null && existing.isValid()) {
                continue;
            }
            PiecesContainer pieces = SiteGenerator.pieces(env, structure, placed.structure().location(), placed.chunk(), placed.seed());
            if (pieces == null) {
                SiteGenerator.warnOnce("vanished " + placed.structure().location() + placed.chunk(),
                        "[FrostlineRailways] rail site {} at chunk {} did not generate again; skipped", placed.structure().location(), placed.chunk());
                continue;
            }
            if (!pieces.calculateBoundingBox().equals(placed.box())) {
                SiteGenerator.warnOnce("moved " + placed.structure().location(),
                        "[FrostlineRailways] rail site {} generated differently at chunk time than when planned ({} vs {}); its structure "
                                + "is not deterministic, placed as generated", placed.structure().location(), pieces.calculateBoundingBox(), placed.box());
            }
            manager.setStartForStructure(section, structure, new StructureStart(structure, chunk.getPos(), 0, pieces), chunk);
        }
    }

    /**
     * True if a freshly generated structure start in the line's level must be dropped: it comes within the band
     * (bed half width + clearance, at the heights the rail works on), or it is tagged #frostline:rail/avoid and comes
     * within bed half width + avoidMargin (every height), or it comes within siteMargin of a station or a rail site.
     * Rail sites themselves never pass through here (see {@link #createStarts}).
     */
    public static boolean exclude(Structure structure, RegistryAccess access, ChunkGenerator generator, RandomState random, long seed,
                                  StructureStart start) {
        if (!FrostlineRailways.createLoaded() || !RailwaysConfig.exclusion()) {
            return false;
        }
        RailLayout layout = RailLayoutService.layoutFor(random);
        RailLineDef def = RailLayoutService.definition();
        if (layout == null || def == null) {
            return false;
        }
        // already widened by the structure's terrain adaptation (StructureStart#getBoundingBox)
        BoundingBox whole = start.getBoundingBox();
        int clearance = RailwaysConfig.clearance();
        int avoidMargin = RailwaysConfig.avoidMargin();
        // widest the line reaches from its centre: bed or tunnel half width (6 at most in the codecs) plus a curve
        int reach = 8 + Math.max(clearance, avoidMargin);
        if (layout.touches(whole.minX(), whole.maxX(), whole.minZ(), whole.maxZ(), reach) && near(layout, whole, reach)) {
            RailContext ctx = new RailContext(seed, generator, random, layout, def);
            boolean avoid = access.registry(Registries.STRUCTURE)
                    .flatMap(registry -> registry.getResourceKey(structure).flatMap(registry::getHolder))
                    .map(holder -> holder.is(AVOID))
                    .orElse(false);
            for (StructurePiece piece : start.getPieces()) {
                BoundingBox box = structure.adjustBoundingBox(piece.getBoundingBox());
                if ((avoid && LineBand.hits(ctx, box, avoidMargin, false)) || LineBand.hits(ctx, box, clearance, true)) {
                    return dropped(structure, access, start, avoid ? "avoid margin" : "track band");
                }
            }
        }
        SitePlan current = plan(random);
        int siteMargin = RailwaysConfig.siteMargin();
        if (current != null && current.guardedNear(whole, siteMargin)) {
            // the whole box is near: test piece by piece, so a mineshaft passing deep below a station survives
            for (StructurePiece piece : start.getPieces()) {
                if (current.guardedNear(structure.adjustBoundingBox(piece.getBoundingBox()), siteMargin)) {
                    return dropped(structure, access, start, "station or rail site");
                }
            }
        }
        return false;
    }

    /**
     * Cheap test before building rows: is the box within {@code reach} of the track at its middle row, allowing for the
     * track moving at most one block across per block along (45 degree diagonals) over half the box's length?
     */
    private static boolean near(RailLayout layout, BoundingBox box, int reach) {
        int z = Math.max(layout.zNorthEnd(), Math.min(layout.zSouthEnd(), (box.minZ() + box.maxZ()) / 2));
        int piece = layout.pieceAt(z);
        if (piece < 0) {
            return true;
        }
        double centre = layout.centreX(piece, z);
        int slack = reach + box.getZSpan() / 2 + Math.abs(z - (box.minZ() + box.maxZ()) / 2) + 2;
        return box.maxX() >= centre - slack && box.minX() <= centre + slack;
    }

    private static boolean dropped(Structure structure, RegistryAccess access, StructureStart start, String why) {
        long n = DROPPED.incrementAndGet();
        if (RailwaysConfig.railPerfLogging()) {
            ResourceLocation id = access.registry(Registries.STRUCTURE).map(r -> r.getKey(structure)).orElse(null);
            LOGGER.info("[railperf] structure {} at chunk {} dropped near the rail line ({}); {} dropped so far", id, start.getChunkPos(), why, n);
        }
        return true;
    }
}
