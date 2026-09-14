package com.yamikhal.frostlinerailways.rail.graph;

import com.mojang.logging.LogUtils;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation.DiscoveredLocation;
import com.simibubi.create.content.trains.signal.SignalPropagator;
import com.simibubi.create.content.trains.track.TrackMaterial;
import com.yamikhal.frostlinerailways.RailwaysConfig;
import com.yamikhal.frostlinerailways.rail.RailLineDef;
import com.yamikhal.frostlinerailways.rail.create.PieceGeometry;
import com.yamikhal.frostlinerailways.rail.decor.RailContext;
import com.yamikhal.frostlinerailways.rail.decor.StationPlanner;
import com.yamikhal.frostlinerailways.rail.layout.RailLayout;
import com.yamikhal.frostlinerailways.rail.layout.RailLayoutService;
import net.createmod.catnip.data.Couple;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Builds the line's Create track graph straight from the layout (RAILWAYS.md §A3.3).
 *
 * Never reads a block and never calls TrackPropagator, so the whole line's graph exists before
 * its chunks do and trains can path along all of it. Edges match what Create's propagation would
 * build over the same blocks:
 *
 *   straights  the z-axis blocks between pieces, with nodes at both ends and wherever the track
 *              crosses z % 16 == 0 (TrackPropagator#isValidGraphNodeLocation)
 *   pieces     S-bends, ramps (slopes plus their tilted middle blocks) and diagonal shifts, from
 *              {@link PieceGeometry}, the same geometry the worldgen writes
 *
 * Work runs on the server tick through a queue capped by graphEdgesPerTick and graphMillisPerTick
 * (R5). A marker file records the layout the graph was built for; on start the graph is only
 * rebuilt if the marker differs or the line's end nodes are missing. With reconcileOnChunkLoad,
 * a loaded line chunk re-queues any of its edges the graph lacks; it checks the graph only.
 */
public final class RailGraphService {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MARKER_FILE = "frostline_railways_graph.dat";
    private static final Vec3 Z_AXIS = new Vec3(0, 0, 1);

    private enum State { IDLE, WAITING, BUILDING, BUILT }

    private static volatile State state = State.IDLE;
    private static volatile MinecraftServer server;
    private static volatile RailLayout layout;
    private static volatile List<PieceGeometry.Edge> edges;
    private static volatile int[] edgeSouthZ;
    private static TrackGraph graph;

    private static final ConcurrentLinkedDeque<Integer> QUEUE = new ConcurrentLinkedDeque<>();
    private static final Set<Integer> QUEUED = ConcurrentHashMap.newKeySet();
    private static int built;

    /** Blocks either side of the spawn station whose edges are built first (RAILWAYS.md §A8.9). */
    private static final int PRIORITY_MARGIN = 128;
    private static volatile int priorityMin = Integer.MAX_VALUE;
    private static volatile int priorityMax = Integer.MIN_VALUE;
    /** Priority edges not yet connected; -1 before a build is queued. */
    private static volatile int priorityRemaining = -1;

    private RailGraphService() {
    }

    public static void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        state = RailwaysConfig.railEnabled() ? State.WAITING : State.IDLE;
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        server = null;
        state = State.IDLE;
        layout = null;
        edges = null;
        edgeSouthZ = null;
        graph = null;
        QUEUE.clear();
        QUEUED.clear();
        priorityRemaining = -1;
        priorityMin = Integer.MAX_VALUE;
        priorityMax = Integer.MIN_VALUE;
        RailLayoutService.stop();
    }

    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || server == null) {
            return;
        }
        if (state == State.WAITING) {
            prepare();
        }
        if (state == State.BUILDING || state == State.BUILT) {
            drain();
        }
    }

    private static void prepare() {
        RailLayout current = RailLayoutService.layout(RailLayoutService.dimension(), false);
        if (current == null) {
            if (!RailLayoutService.pending()) {
                state = State.IDLE;
            }
            return;
        }
        layout = current;
        List<PieceGeometry.Edge> list = edges(current);
        int[] keys = new int[list.size()];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = (int) Math.floor(list.get(i).south().z);
        }
        edges = list;
        edgeSouthZ = keys;
        graph = null;
        ServerLevel level = level();
        if (level == null) {
            state = State.IDLE;
            return;
        }
        priorityRange(level, current);
        if (current.hash.equals(readMarker()) && present(level, 0) && present(level, list.size() - 1)) {
            priorityRemaining = 0;
            state = State.BUILT;
            LOGGER.info("[FrostlineRailways] rail graph present for this line ({} edges)", list.size());
            return;
        }
        queueAll();
    }

    public static void queueAll() {
        List<PieceGeometry.Edge> current = edges;
        if (current == null) {
            return;
        }
        // the spawn station's surroundings first, so its starting train does not wait for the whole line
        int[] keys = edgeSouthZ;
        int priority = 0;
        for (int i = 0; i < current.size(); i++) {
            if (inPriority(keys[i])) {
                queue(i);
                priority++;
            }
        }
        for (int i = 0; i < current.size(); i++) {
            if (!inPriority(keys[i])) {
                queue(i);
            }
        }
        priorityRemaining = priority;
        built = 0;
        state = State.BUILDING;
        LOGGER.info("[FrostlineRailways] rail graph queued: {} edges", current.size());
    }

    private static void queue(int edge) {
        if (QUEUED.add(edge)) {
            QUEUE.add(edge);
        }
    }

    private static void drain() {
        ServerLevel level = level();
        if (level == null || edges == null) {
            return;
        }
        int budget = RailwaysConfig.graphEdgesPerTick();
        long start = System.nanoTime();
        long deadline = start + (long) (RailwaysConfig.graphMillisPerTick() * 1_000_000L);
        int done = 0;
        Integer edge;
        while (done < budget && System.nanoTime() < deadline && (edge = QUEUE.poll()) != null) {
            QUEUED.remove(edge);
            connect(level, edges.get(edge));
            if (state == State.BUILDING && priorityRemaining > 0 && inPriority(edgeSouthZ[edge])) {
                priorityRemaining--;
            }
            done++;
        }
        if (done > 0 && RailwaysConfig.railPerfLogging()) {
            LOGGER.info("[railperf] rail graph: {} edges in {} µs, {} queued", done, (System.nanoTime() - start) / 1000, QUEUE.size());
        }
        if (state == State.BUILDING && QUEUE.isEmpty()) {
            state = State.BUILT;
            if (graph != null) {
                graph.markDirty();
            }
            writeMarker(layout.hash);
            LOGGER.info("[FrostlineRailways] rail graph built: {} edges added, graph {}", built, graph == null ? "none" : graph.id);
        }
    }

    public static void onChunkLoad(ChunkEvent.Load event) {
        if (state != State.BUILT || !RailwaysConfig.reconcileOnChunkLoad()
                || !(event.getChunk() instanceof LevelChunk chunk) || !(event.getLevel() instanceof ServerLevel level)
                || level.dimension() != RailLayoutService.dimension()) {
            return;
        }
        RailLayout currentLayout = layout;
        int[] keys = edgeSouthZ;
        if (currentLayout == null || keys == null) {
            return;
        }
        int minZ = chunk.getPos().getMinBlockZ();
        int maxZ = chunk.getPos().getMaxBlockZ();
        if (!currentLayout.touches(chunk.getPos().getMinBlockX(), chunk.getPos().getMaxBlockX(), minZ, maxZ, 0)) {
            return;
        }
        for (int i = firstAtOrNorthOf(keys, maxZ + 1); i < keys.length && keys[i] >= minZ; i++) {
            if (!present(level, i)) {
                queue(i);
            }
        }
    }

    // --- graph operations ---------------------------------------------------------------

    private static void connect(ServerLevel level, PieceGeometry.Edge edge) {
        DiscoveredLocation south = location(level, edge, true);
        DiscoveredLocation north = location(level, edge, false);
        if (graph == null || !Create.RAILWAYS.trackNetworks.containsKey(graph.id)) {
            graph = Create.RAILWAYS.getGraph(level, south);
            if (graph == null) {
                graph = Create.RAILWAYS.getGraph(level, north);
            }
            if (graph == null) {
                graph = new TrackGraph();
                Create.RAILWAYS.putGraphWithDefaultGroup(graph);
            }
        }
        TrackNode a = ensureNode(south);
        TrackNode b = ensureNode(north);
        if (graph.getConnection(Couple.create(a, b)) != null) {
            return;
        }
        graph.connectNodes(level, south, north, edge.curve());
        built++;
    }

    private static TrackNode ensureNode(DiscoveredLocation location) {
        if (graph.createNodeIfAbsent(location)) {
            TrackNode node = graph.locateNode(location);
            SignalPropagator.notifySignalsOfNewNode(graph, node);
            return node;
        }
        return graph.locateNode(location);
    }

    private static boolean present(ServerLevel level, int i) {
        List<PieceGeometry.Edge> current = edges;
        if (current == null || i < 0 || i >= current.size()) {
            return false;
        }
        PieceGeometry.Edge edge = current.get(i);
        DiscoveredLocation south = location(level, edge, true);
        TrackGraph found = Create.RAILWAYS.getGraph(level, south);
        if (found == null) {
            return false;
        }
        TrackNode a = found.locateNode(south);
        TrackNode b = found.locateNode(location(level, edge, false));
        return a != null && b != null && found.getConnection(Couple.create(a, b)) != null;
    }

    private static DiscoveredLocation location(ServerLevel level, PieceGeometry.Edge edge, boolean south) {
        DiscoveredLocation location = new DiscoveredLocation(level, south ? edge.south() : edge.north())
                .materials(TrackMaterial.ANDESITE, TrackMaterial.ANDESITE)
                .withNormal(PieceGeometry.UP)
                .withYOffset(south ? edge.southYOffset() : edge.northYOffset());
        return edge.curve() != null ? location.viaTurn(edge.curve()) : location.withDirection(edge.axis());
    }

    // --- edges from the layout --------------------------------------------------------------

    /**
     * Every edge of the line, south to north. A straight piece's run also holds the end blocks of the
     * pieces either side of it, so it reaches from the next piece's first edge (its southern end
     * block's z) back to the previous piece's last edge (its northern end block's z + 1).
     */
    static List<PieceGeometry.Edge> edges(RailLayout layout) {
        List<PieceGeometry.Edge> list = new ArrayList<>();
        int n = layout.count();
        for (int i = 0; i < n; i++) {
            if (layout.type(i) != RailLayout.STRAIGHT) {
                list.addAll(PieceGeometry.of(layout, i).edges());
                continue;
            }
            double x = layout.xSouth(i) + 0.5;
            int y = layout.ySouth(i);
            int south = i > 0 ? layout.zSouth(i) + 2 : layout.zSouth(i) + 1;
            int north = i < n - 1 ? layout.zNorth(i) - 1 : layout.zNorth(i);
            int previous = south;
            for (int z = south - 1; z > north; z--) {
                if (Math.floorMod(z, 16) == 0) {
                    list.add(new PieceGeometry.Edge(new Vec3(x, y, previous), 0, new Vec3(x, y, z), 0, null, Z_AXIS));
                    previous = z;
                }
            }
            list.add(new PieceGeometry.Edge(new Vec3(x, y, previous), 0, new Vec3(x, y, north), 0, null, Z_AXIS));
        }
        return List.copyOf(list);
    }

    /** First index (keys descend south to north) whose key is at or north of z. */
    private static int firstAtOrNorthOf(int[] keys, int z) {
        int lo = 0;
        int hi = keys.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (keys[mid] > z) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    // --- status ------------------------------------------------------------------------------

    private static void priorityRange(ServerLevel level, RailLayout current) {
        priorityMin = Integer.MAX_VALUE;
        priorityMax = Integer.MIN_VALUE;
        RailLineDef def = RailLayoutService.definition();
        StationPlanner.Site site = def == null ? null : StationPlanner.spawnSite(new RailContext(level, current, def));
        if (site != null) {
            priorityMin = site.zNorth() - PRIORITY_MARGIN;
            priorityMax = site.zSouth() + PRIORITY_MARGIN;
        }
    }

    private static boolean inPriority(int key) {
        return key >= priorityMin && key <= priorityMax;
    }

    /** True if every edge between zMin and zMax is in the graph: the whole line is, or it lies in the spawn area built first. */
    public static boolean readyAround(int zMin, int zMax) {
        return state == State.BUILT || (priorityRemaining == 0 && zMin >= priorityMin && zMax <= priorityMax);
    }

    /** True once every edge of the line is in the graph. */
    public static boolean built() {
        return state == State.BUILT;
    }

    public static String status() {
        List<PieceGeometry.Edge> current = edges;
        TrackGraph currentGraph = graph;
        return "graph " + state.name().toLowerCase()
                + (current == null ? "" : ", " + current.size() + " edges")
                + ", " + QUEUE.size() + " queued"
                + (currentGraph == null ? "" : ", graph " + currentGraph.id + " (" + currentGraph.getNodes().size() + " nodes)");
    }

    private static ServerLevel level() {
        MinecraftServer current = server;
        return current == null || RailLayoutService.dimension() == null ? null : current.getLevel(RailLayoutService.dimension());
    }

    private static File markerFile() {
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(MARKER_FILE).toFile();
    }

    private static String readMarker() {
        try {
            File file = markerFile();
            return file.isFile() ? NbtIo.readCompressed(file).getString("BuiltFor") : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static void writeMarker(String hash) {
        try {
            CompoundTag tag = new CompoundTag();
            tag.putString("BuiltFor", hash);
            File file = markerFile();
            file.getParentFile().mkdirs();
            NbtIo.writeCompressed(tag, file);
        } catch (Exception e) {
            LOGGER.warn("[FrostlineRailways] could not write rail graph marker", e);
        }
    }
}
