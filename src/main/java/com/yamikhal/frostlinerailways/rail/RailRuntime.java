package com.yamikhal.frostlinerailways.rail;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import com.yamikhal.frostlinerailways.RailwaysConfig;
import com.yamikhal.frostlinerailways.rail.decor.RailContext;
import com.yamikhal.frostlinerailways.rail.decor.StationPlanner;
import com.yamikhal.frostlinerailways.rail.graph.RailGraphService;
import com.yamikhal.frostlinerailways.rail.layout.RailLayout;
import com.yamikhal.frostlinerailways.rail.layout.RailLayoutService;
import com.yamikhal.frostlinerailways.rail.sites.RailSites;
import com.yamikhal.frostlinerailways.rail.sites.SitePlan;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.level.LevelEvent;
import org.slf4j.Logger;

import java.util.List;

/**
 * Wires Frostline Rail into the game: line datapack loading, layout on level load, world spawn
 * beside the line, graph on the server tick, reconciliation on chunk load, and /frostline rail.
 * Only called with Create loaded.
 */
public final class RailRuntime {

    private static final Logger LOGGER = LogUtils.getLogger();

    private RailRuntime() {
    }

    public static void init() {
        MinecraftForge.EVENT_BUS.addListener((AddReloadListenerEvent e) -> e.addListener(new RailLineLoader()));
        MinecraftForge.EVENT_BUS.addListener(com.yamikhal.frostlinerailways.rail.decor.RailDecorData::register);
        MinecraftForge.EVENT_BUS.addListener(RailLayoutService::onLevelLoad);
        MinecraftForge.EVENT_BUS.addListener(RailRuntime::onCreateSpawn);
        MinecraftForge.EVENT_BUS.addListener(RailGraphService::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(RailGraphService::onServerStopped);
        MinecraftForge.EVENT_BUS.addListener(RailGraphService::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(RailGraphService::onChunkLoad);
        MinecraftForge.EVENT_BUS.addListener(RailRuntime::onRegisterCommands);
        MinecraftForge.EVENT_BUS.addListener(StationRuntime::onChunkLoad);
        MinecraftForge.EVENT_BUS.addListener(StationRuntime::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(StationRuntime::onLogin);
        MinecraftForge.EVENT_BUS.addListener(StationRuntime::onServerStopped);
    }

    /**
     * A new world's spawn goes on the spawn station's platform when there is one (spawnOnPlatform),
     * else beside the line at spawnZ, spawnOffsetX blocks east of the track, on the ground. Only for new
     * worlds (vanilla fires this once, when the spawn is first chosen).
     */
    private static void onCreateSpawn(LevelEvent.CreateSpawnPosition event) {
        if (!RailwaysConfig.railEnabled() || !RailwaysConfig.spawnNearLine() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        RailLayout layout = RailLayoutService.layout(level.dimension(), true);
        if (layout == null || RailLayoutService.definition() == null) {
            return;
        }
        StationPlanner.Site site = RailwaysConfig.spawnOnPlatform()
                ? StationPlanner.spawnSite(new RailContext(level, layout, RailLayoutService.definition())) : null;
        BlockPos spawn;
        if (site != null) {
            spawn = new BlockPos(site.trackX() + site.sign() * (site.def().look().gap() + 2), site.bedY() + 1, site.zCentre());
        } else {
            int z = Math.max(layout.zNorthEnd() + 64, Math.min(layout.zSouthEnd() - 64, RailwaysConfig.spawnZ()));
            int piece = layout.pieceAt(z);
            int x = (int) Math.round(layout.centreX(piece, z)) + RailwaysConfig.spawnOffsetX();
            int y = level.getChunkSource().getGenerator().getBaseHeight(x, z, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    level, level.getChunkSource().randomState());
            spawn = new BlockPos(x, y, z);
        }
        event.getSettings().setSpawn(spawn, 0.0F);
        event.setCanceled(true);
        LOGGER.info("[FrostlineRailways] world spawn set beside the rail line at {}", spawn);
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("frostline")
                .then(Commands.literal("rail").requires(s -> s.hasPermission(2))
                        .then(Commands.literal("info").executes(c -> info(c.getSource())))
                        .then(Commands.literal("where").executes(c -> where(c.getSource())))
                        .then(Commands.literal("stations").executes(c -> stations(c.getSource())))
                        .then(Commands.literal("sites").executes(c -> sites(c.getSource()))
                                .then(Commands.literal("tp")
                                        .then(Commands.argument("number", IntegerArgumentType.integer(1))
                                                .executes(c -> siteTp(c.getSource(), IntegerArgumentType.getInteger(c, "number"))))))
                        .then(Commands.literal("tp")
                                .then(Commands.argument("blocks_from_south_end", IntegerArgumentType.integer(0))
                                        .executes(c -> tp(c.getSource(), IntegerArgumentType.getInteger(c, "blocks_from_south_end")))))
                        .then(Commands.literal("graph")
                                .then(Commands.literal("status").executes(c -> say(c.getSource(), RailGraphService.status())))
                                .then(Commands.literal("rebuild").executes(c -> {
                                    RailGraphService.queueAll();
                                    return say(c.getSource(), "Rail graph rebuild queued. " + RailGraphService.status());
                                })))
                        .then(Commands.literal("layout")
                                .then(Commands.literal("rebuild").executes(c -> layoutRebuild(c.getSource()))))));
    }

    private static RailLayout layout(CommandSourceStack source) {
        RailLayout layout = RailLayoutService.layout(RailLayoutService.dimension(), false);
        if (layout == null) {
            source.sendFailure(Component.literal(RailLayoutService.pending()
                    ? "The rail layout is still being built." : "No rail line in this world (check [frostline_rail] enabled and the line datapack)."));
        }
        return layout;
    }

    private static String typeName(byte type) {
        return switch (type) {
            case RailLayout.BEND -> "S-bend";
            case RailLayout.RAMP -> "ramp";
            case RailLayout.SHIFT -> "diagonal shift";
            default -> "straight";
        };
    }

    private static int info(CommandSourceStack source) {
        RailLayout layout = layout(source);
        if (layout == null) {
            return 0;
        }
        int[] counts = new int[4];
        int longest = 0;
        for (int i = 0; i < layout.count(); i++) {
            counts[layout.type(i)]++;
            if (layout.type(i) == RailLayout.STRAIGHT) {
                longest = Math.max(longest, layout.zSouth(i) - layout.zNorth(i) + 1);
            }
        }
        return say(source, "Line " + layout.lineId + ": z " + layout.zSouthEnd() + " to " + layout.zNorthEnd()
                + " (" + layout.length() + " blocks), " + layout.count() + " pieces: " + counts[RailLayout.STRAIGHT] + " straights (longest "
                + longest + "), " + counts[RailLayout.BEND] + " S-bends, " + counts[RailLayout.SHIFT] + " diagonal shifts, "
                + counts[RailLayout.RAMP] + " ramps; max corridor deviation " + String.format("%.1f", layout.maxDeviation)
                + " | " + RailGraphService.status());
    }

    private static int where(CommandSourceStack source) {
        RailLayout layout = layout(source);
        if (layout == null) {
            return 0;
        }
        int z = (int) Math.floor(source.getPosition().z);
        int piece = layout.pieceAt(z);
        if (piece < 0) {
            return say(source, "Outside the line (z " + layout.zSouthEnd() + " to " + layout.zNorthEnd() + ").");
        }
        return say(source, (layout.zSouthEnd() - z) + " blocks from the south end, on a " + typeName(layout.type(piece))
                + " (" + (layout.zSouth(piece) - layout.zNorth(piece) + 1) + " blocks), track at x "
                + Math.round(layout.centreX(piece, z)) + " y " + Math.round(layout.centreY(piece, z)));
    }

    private static int stations(CommandSourceStack source) {
        RailLayout layout = layout(source);
        ServerLevel level = layout == null ? null : source.getServer().getLevel(RailLayoutService.dimension());
        if (level == null || RailLayoutService.definition() == null) {
            return 0;
        }
        List<StationPlanner.Site> sites = StationPlanner.sites(new RailContext(level, layout, RailLayoutService.definition()));
        SitePlan plan = RailSites.plan();
        StringBuilder text = new StringBuilder(sites.size() + " stations, south to north:");
        for (StationPlanner.Site site : sites) {
            SitePlan.StationInfo info = plan == null ? null : plan.station(site);
            text.append("\n  ").append(site.name()).append(site.def().where().spawn() ? " (spawn)" : "")
                    .append(": ").append(layout.zSouthEnd() - site.zCentre()).append(" blocks from the south end, x ")
                    .append(site.trackX()).append(" y ").append(site.bedY()).append(" z ").append(site.zCentre())
                    .append(site.sign() > 0 ? ", east" : ", west");
            if (info != null && info.district() != null) {
                text.append(", district ").append(info.district()).append(info.override() != null ? " (own building)" : "");
            }
        }
        return say(source, text.toString());
    }

    /** Every planned rail site (RAILWAYS.md A8.15), numbered for /frostline rail sites tp. */
    private static int sites(CommandSourceStack source) {
        RailLayout layout = layout(source);
        if (layout == null) {
            return 0;
        }
        SitePlan plan = RailSites.plan();
        if (plan == null) {
            source.sendFailure(Component.literal("No site plan for this world."));
            return 0;
        }
        List<SitePlan.Placed> placed = plan.placed();
        StringBuilder text = new StringBuilder(placed.size() + " rail sites (planned in " + plan.millis + " ms; "
                + RailSites.dropped() + " other structures dropped near the line so far):");
        for (int i = 0; i < placed.size(); i++) {
            SitePlan.Placed p = placed.get(i);
            BoundingBox box = p.box();
            String from = switch (p.kind()) {
                case LINE -> "the track";
                case CORE -> "the station";
                case OUTER -> "the core";
            };
            text.append("\n  ").append(i + 1).append(". ").append(p.kind().name().toLowerCase(java.util.Locale.ROOT)).append(' ')
                    .append(p.structure().location()).append(" (").append(p.source()).append(") at x ")
                    .append((box.minX() + box.maxX()) / 2).append(" z ").append((box.minZ() + box.maxZ()) / 2)
                    .append(p.side() > 0 ? ", east, " : ", west, ").append(p.distance()).append(" blocks from ").append(from)
                    .append(p.facingMet() ? "" : ", facing not met");
        }
        if (!plan.failures.isEmpty()) {
            text.append("\nRejected spots by reason: ").append(plan.failures);
        }
        return say(source, text.toString());
    }

    private static int siteTp(CommandSourceStack source, int number) throws CommandSyntaxException {
        SitePlan plan = RailSites.plan();
        ServerLevel level = RailLayoutService.level();
        if (plan == null || level == null || number > plan.placed().size()) {
            source.sendFailure(Component.literal("No rail site " + number + " (see /frostline rail sites)."));
            return 0;
        }
        BoundingBox box = plan.placed().get(number - 1).box();
        int x = (box.minX() + box.maxX()) / 2;
        int z = (box.minZ() + box.maxZ()) / 2;
        int y = Math.max(box.maxY() + 2, level.getChunkSource().getGenerator().getBaseHeight(x, z, Heightmap.Types.MOTION_BLOCKING,
                level, level.getChunkSource().randomState()) + 2);
        source.getPlayerOrException().teleportTo(level, x + 0.5, y, z + 0.5, 0.0F, 30.0F);
        return 1;
    }

    private static int tp(CommandSourceStack source, int blocks) throws CommandSyntaxException {
        RailLayout layout = layout(source);
        if (layout == null) {
            return 0;
        }
        ServerPlayer player = source.getPlayerOrException();
        int z = Math.max(layout.zNorthEnd(), layout.zSouthEnd() - blocks);
        int piece = layout.pieceAt(z);
        ServerLevel level = source.getServer().getLevel(RailLayoutService.dimension());
        player.teleportTo(level, layout.centreX(piece, z) + 0.5, layout.centreY(piece, z) + 1, z + 0.5, 180.0F, 0.0F);
        return 1;
    }

    private static int layoutRebuild(CommandSourceStack source) {
        ServerLevel level = source.getServer().getLevel(RailLayoutService.dimension() == null
                ? Level.OVERWORLD : RailLayoutService.dimension());
        if (level == null || !RailLayoutService.start(level, true)) {
            source.sendFailure(Component.literal("Could not start a layout rebuild (no line for this world)."));
            return 0;
        }
        return say(source, "Rail layout rebuild started. Already generated chunks keep their old track; "
                + "run /frostline rail graph rebuild once /frostline rail info shows the new line.");
    }

    private static int say(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }
}
