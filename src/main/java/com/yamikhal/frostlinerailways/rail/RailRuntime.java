package com.yamikhal.frostlinerailways.rail;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import com.yamikhal.frostlinerailways.RailwaysConfig;
import com.yamikhal.frostlinerailways.rail.graph.RailGraphService;
import com.yamikhal.frostlinerailways.rail.layout.RailLayout;
import com.yamikhal.frostlinerailways.rail.layout.RailLayoutService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.level.LevelEvent;
import org.slf4j.Logger;

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
    }

    /**
     * A new world's spawn goes beside the line at spawnZ, spawnOffsetX blocks east of the track, on
     * the ground. Only for new worlds (vanilla fires this once, when the spawn is first chosen).
     */
    private static void onCreateSpawn(LevelEvent.CreateSpawnPosition event) {
        if (!RailwaysConfig.railEnabled() || !RailwaysConfig.spawnNearLine() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        RailLayout layout = RailLayoutService.layout(level.dimension(), true);
        if (layout == null) {
            return;
        }
        int z = Math.max(layout.zNorthEnd() + 64, Math.min(layout.zSouthEnd() - 64, RailwaysConfig.spawnZ()));
        int piece = layout.pieceAt(z);
        int x = (int) Math.round(layout.centreX(piece, z)) + RailwaysConfig.spawnOffsetX();
        int y = level.getChunkSource().getGenerator().getBaseHeight(x, z, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                level, level.getChunkSource().randomState());
        BlockPos spawn = new BlockPos(x, y, z);
        event.getSettings().setSpawn(spawn, 0.0F);
        event.setCanceled(true);
        LOGGER.info("[FrostlineRailways] world spawn set beside the rail line at {}", spawn);
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("frostline")
                .then(Commands.literal("rail").requires(s -> s.hasPermission(2))
                        .then(Commands.literal("info").executes(c -> info(c.getSource())))
                        .then(Commands.literal("where").executes(c -> where(c.getSource())))
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
