package me.mrhikmen.colorlight.client.compat.sable;

import me.mrhikmen.colorlight.client.ColorLightClient;
import me.mrhikmen.colorlight.client.compat.sable.SableBodies.Body;
import me.mrhikmen.colorlight.client.config.ColorLightConfig;
import me.mrhikmen.colorlight.client.core.light.color.ColorLightUtil;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngine;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngineHolder;
import me.mrhikmen.colorlight.client.core.light.engine.SourceSnapshot;
import me.mrhikmen.colorlight.client.core.render.gpu.ColorLightGpu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lets light cross between the world and Sable sub-levels, through ColorLight's dynamic light layer.
 * <p>
 * The static light field is blind to the boundary: a lamp on a ship lights the ship (its blocks live in plot space,
 * ~20M blocks away), a torch on the ground lights the ground, and neither reaches the other. The dynamic layer already
 * knows how to light a moving point in any coordinate space, so this class feeds it:
 * <ul>
 *     <li><b>sub-level -> world</b>: each (strong) light of a sub-level becomes a dynamic light in <i>world</i> space at
 *     the block's current world position, so the terrain around a ship is lit by the ship;</li>
 *     <li><b>world -> sub-level</b>: each light of the terrain that is <i>within reach of a sub-level</i> becomes a
 *     dynamic light in that sub-level's <i>plot</i> space (the world position mapped through the inverse pose), so the
 *     ship is lit by the lamps it passes. Only with the GPU pipeline: the footprint changes every tick the ship
 *     moves, which is free for the GPU windows but would rebuild the ship's meshes without them.</li>
 * </ul>
 * <b>Optimisation:</b> nothing is done while there is no sub-level near the player, and a terrain light that has no
 * sub-level within its reach gets no dynamic light at all (an existing one is removed).
 * <p>
 * Same machinery as {@code ColorLightEntityLightTicker}: the game thread only decides what each light should look like,
 * a worker thread floods it, newer requests replace older ones. Updates per tick are capped, so a fast ship with many
 * lamps lags its lights slightly instead of stalling the frame.
 */
public final class SableLightLinker {

    /** Source lights are re-collected this often (ticks); between scans only their positions are followed. */
    private static final int RESCAN_TICKS = 10;
    /** Strongest lights of one sub-level that light the world. */
    private static final int MAX_OWN_PER_SUBLEVEL = 16;
    /** Nearest terrain lights that light one sub-level. */
    private static final int MAX_WORLD_PER_SUBLEVEL = 16;
    /** Floods requested per tick, all links together. */
    private static final int MAX_UPDATES_PER_TICK = 8;
    /** A link is recomputed after its light moved this fraction of a block (1/4). */
    private static final double STEPS_PER_BLOCK = 4.0;
    /** Terrain lights this close to a sub-level (blocks, on top of the light's reach) are remembered between scans. */
    private static final double CANDIDATE_PAD = 24.0;

    private record Src(long key, int x, int y, int z, int color) {
        int brightness() {
            return Math.max(ColorLightUtil.r(color), Math.max(ColorLightUtil.g(color), ColorLightUtil.b(color)));
        }
    }

    /** null target = the world, null owner = the world; one link is one light in one space. */
    private record LinkKey(UUID target, UUID owner, long src) {
    }

    private static final class Tracked {
        int id;
        long qx, qy, qz;
        int color;
    }

    private record Request(ColorLightEngine engine, int id, boolean remove,
                           double x, double y, double z, int r, int g, int b) {
    }

    // ---- game thread only ----
    private static final Map<LinkKey, Tracked> TRACKED = new HashMap<>();
    private static final Set<LinkKey> SEEN = new HashSet<>();
    private static final Map<UUID, List<Src>> OWN = new HashMap<>();
    private static final Map<UUID, List<Src>> CANDIDATES = new HashMap<>();
    private static ColorLightEngine trackedEngine;
    private static int tickCounter;
    private static int rescanIn;
    private static int scannedVersion = -1;
    private static int lastScanTick;
    private static int budget;
    /** Dynamic light ids: negative, so they never meet an entity id. */
    private static int nextId = -1;
    private static final Vector3d SCRATCH = new Vector3d();

    // ---- worker ----
    private static final ConcurrentHashMap<Integer, Request> REQUESTS = new ConcurrentHashMap<>();
    private static final AtomicBoolean DRAIN_SCHEDULED = new AtomicBoolean();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ColorLight Sable Light");
        thread.setDaemon(true);
        thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        return thread;
    });
    private static ColorLightEngine workerEngine;
    private static ColorLightEngine.DynamicLightWorker worker;
    private static boolean loggedFailure;

    public static void tick(Minecraft client) {
        ClientLevel level = client.level;
        ColorLightEngine engine = ColorLightEngineHolder.get();
        ColorLightConfig config = ColorLightClient.config;

        if (level == null || client.player == null || engine == null || !config.ENABLE || !config.SABLE_CROSS_LIGHT) {
            dropAll(engine);
            return;
        }

        if (engine != trackedEngine) { // new world / rebuilt engine: everything tracked belonged to the old one
            TRACKED.clear();
            REQUESTS.clear();
            OWN.clear();
            CANDIDATES.clear();
            rescanIn = 0;
            scannedVersion = -1;
            trackedEngine = engine;
        }

        // Nothing to do (and nothing kept) while no sub-level is near.
        List<Body> near = nearBodies(client, level, config);
        if (near.isEmpty()) {
            dropAll(engine);
            return;
        }

        tickCounter++;

        for (Body body : near) {
            if (!OWN.containsKey(body.id()))
                rescanIn = 0; // a sub-level we have not scanned yet
        }
        // a light was placed or removed (the engine's source set changed): pick it up now, not at the next periodic scan
        if (engine.getSourceSnapshot().version() != scannedVersion && tickCounter - lastScanTick >= 2)
            rescanIn = 0;
        if (--rescanIn <= 0) {
            rescanIn = RESCAN_TICKS;
            rescan(engine, near);
        }

        SEEN.clear();
        budget = MAX_UPDATES_PER_TICK;
        boolean gpu = ColorLightGpu.isActive();
        double reach = ColorLightEngineHolder.getMaxRangeBlocks() + 1;
        double reachSq = reach * reach;
        // a light reaches as far as it is bright, so the sub-level light distance is a dimming factor on the maximum range
        double rangeFactor = Math.min(1.0, config.SABLE_LIGHT_RANGE_BLOCKS / (double) ColorLightEngineHolder.getMaxRangeBlocks());

        int bodyOffset = tickCounter % near.size();
        for (int bi = 0; bi < near.size(); bi++) {
            Body body = near.get((bi + bodyOffset) % near.size());

            // sub-level -> world: the sub-level's own lights, at their current world position
            List<Src> own = OWN.getOrDefault(body.id(), List.of());
            for (int i = 0; i < own.size(); i++) {
                Src src = own.get((i + tickCounter) % own.size());
                body.toWorld(src.x() + 0.5, src.y() + 0.5, src.z() + 0.5, SCRATCH);
                track(engine, new LinkKey(null, body.id(), src.key()), SCRATCH.x, SCRATCH.y, SCRATCH.z, scaleColor(src.color(), rangeFactor));
            }

            // world -> sub-level: terrain lights whose reach touches the sub-level, in its plot space
            if (gpu) {
                List<Src> candidates = CANDIDATES.getOrDefault(body.id(), List.of());
                if (!candidates.isEmpty()) {
                    Src[] best = new Src[MAX_WORLD_PER_SUBLEVEL];
                    double[] bestDist = new double[MAX_WORLD_PER_SUBLEVEL];
                    int count = nearest(body, candidates, reachSq, best, bestDist);
                    for (int i = 0; i < count; i++) {
                        Src src = best[(i + tickCounter) % count];
                        body.toPlot(src.x() + 0.5, src.y() + 0.5, src.z() + 0.5, SCRATCH);
                        track(engine, new LinkKey(body.id(), null, src.key()), SCRATCH.x, SCRATCH.y, SCRATCH.z, src.color());
                    }
                }
            }
        }

        // every link not asked for this tick: its light is gone, or no sub-level is in reach of it any more
        Iterator<Map.Entry<LinkKey, Tracked>> it = TRACKED.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<LinkKey, Tracked> entry = it.next();
            if (!SEEN.contains(entry.getKey())) {
                submitRemove(engine, entry.getValue().id);
                it.remove();
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------

    /** Sub-levels within the entity-check radius of the player (the same distance LambDynamicLights entities use). */
    private static List<Body> nearBodies(Minecraft client, ClientLevel level, ColorLightConfig config) {
        List<Body> all = SableBodies.list(level);
        if (all.isEmpty())
            return all;

        int chunks = config.ENTITY_CHECK_FOLLOW_RENDER_DISTANCE
                ? client.options.renderDistance().get()
                : config.ENTITY_CHECK_RADIUS_CHUNKS;
        double radius = chunks * 16.0;
        double radiusSq = radius * radius;

        List<Body> near = new ArrayList<>(all.size());
        for (Body body : all) {
            if (body.distSqToBounds(client.player.getX(), client.player.getY(), client.player.getZ()) <= radiusSq)
                near.add(body);
        }
        return near;
    }

    /**
     * One pass over every static light of the world: the ones inside a sub-level's plot are that sub-level's own lights
     * (strongest first, capped); the rest are terrain lights, remembered for every sub-level they are near enough to
     * ever reach. Lights of plots that are not near are ignored: they are ~20M blocks from everything.
     */
    private static void rescan(ColorLightEngine engine, List<Body> near) {
        Map<UUID, List<Src>> own = new HashMap<>();
        Map<UUID, List<Src>> candidates = new HashMap<>();
        for (Body body : near) {
            own.put(body.id(), new ArrayList<>());
            candidates.put(body.id(), new ArrayList<>());
        }

        double pad = ColorLightEngineHolder.getMaxRangeBlocks() + 1 + CANDIDATE_PAD;
        double padSq = pad * pad;

        SourceSnapshot snapshot = engine.getSourceSnapshot();
        scannedVersion = snapshot.version();
        lastScanTick = tickCounter;
        for (int i = 0, n = snapshot.size(); i < n; i++) {
            int color = snapshot.color(i);
            if (color == 0)
                continue;
            int x = snapshot.x(i), y = snapshot.y(i), z = snapshot.z(i);
            Src src = new Src(me.mrhikmen.colorlight.client.core.light.util.PosKey.pack(x, y, z), x, y, z, color);

            Body owner = null;
            for (Body body : near) {
                if (body.inPlot(x, y, z)) {
                    owner = body;
                    break;
                }
            }
            if (owner != null) {
                own.get(owner.id()).add(src);
                continue;
            }

            for (Body body : near) {
                if (body.distSqToBounds(x + 0.5, y + 0.5, z + 0.5) <= padSq)
                    candidates.get(body.id()).add(src);
            }
        }

        for (List<Src> list : own.values()) {
            list.sort((a, b) -> Integer.compare(b.brightness(), a.brightness()));
            if (list.size() > MAX_OWN_PER_SUBLEVEL)
                list.subList(MAX_OWN_PER_SUBLEVEL, list.size()).clear();
        }

        OWN.clear();
        OWN.putAll(own);
        CANDIDATES.clear();
        CANDIDATES.putAll(candidates);
    }

    /**
     * The (up to {@code best.length}) candidates closest to the sub-level that are inside {@code reachSq}, nearest
     * first. Insertion into small sorted arrays: no allocation, no sort of the whole candidate list.
     */
    private static int nearest(Body body, List<Src> candidates, double reachSq, Src[] best, double[] bestDist) {
        int count = 0;
        for (int c = 0, n = candidates.size(); c < n; c++) {
            Src src = candidates.get(c);
            double d = body.distSqToBounds(src.x() + 0.5, src.y() + 0.5, src.z() + 0.5);
            if (d > reachSq)
                continue; // no sub-level in reach of this light: no dynamic light for it

            int at = count;
            if (count == best.length) {
                if (d >= bestDist[count - 1])
                    continue;
                at = count - 1;
            } else {
                count++;
            }
            while (at > 0 && bestDist[at - 1] > d) {
                best[at] = best[at - 1];
                bestDist[at] = bestDist[at - 1];
                at--;
            }
            best[at] = src;
            bestDist[at] = d;
        }
        return count;
    }

    /**
     * Keeps one link's dynamic light at {@code (x, y, z)} (the centre of the light's block, in the target space).
     * New and moved lights are sent to the worker while this tick's budget lasts; the others keep their old light.
     */
    private static void track(ColorLightEngine engine, LinkKey key, double x, double y, double z, int color) {
        SEEN.add(key);

        long qx = Math.round(x * STEPS_PER_BLOCK);
        long qy = Math.round(y * STEPS_PER_BLOCK);
        long qz = Math.round(z * STEPS_PER_BLOCK);

        Tracked tracked = TRACKED.get(key);
        if (tracked == null) {
            if (budget <= 0)
                return;
            tracked = new Tracked();
            tracked.id = nextId--;
            TRACKED.put(key, tracked);
        } else {
            if (tracked.qx == qx && tracked.qy == qy && tracked.qz == qz && tracked.color == color
                    && !engine.isDynamicStale(tracked.id))
                return;
            if (budget <= 0)
                return;
        }
        budget--;

        tracked.qx = qx;
        tracked.qy = qy;
        tracked.qz = qz;
        tracked.color = color;

        // The flood takes a "cell coordinate" (block n = n, and it shifts x and z by half a block itself, like it
        // does for an entity standing at n + 0.5), so: x as is, y and z-centre minus the half block.
        REQUESTS.put(tracked.id, new Request(engine, tracked.id, false, x, y - 0.5, z,
                ColorLightUtil.r(color), ColorLightUtil.g(color), ColorLightUtil.b(color)));
        scheduleDrain();
    }

    private static int scaleColor(int color, double factor) {
        if (factor >= 1.0)
            return color;
        return ColorLightUtil.pack((int) Math.round(ColorLightUtil.r(color) * factor),
                (int) Math.round(ColorLightUtil.g(color) * factor),
                (int) Math.round(ColorLightUtil.b(color) * factor));
    }

    private static void dropAll(ColorLightEngine engine) {
        if (!TRACKED.isEmpty() && engine != null && engine == trackedEngine) {
            for (Tracked tracked : TRACKED.values())
                submitRemove(engine, tracked.id);
        }
        TRACKED.clear();
        OWN.clear();
        CANDIDATES.clear();
        SEEN.clear();
        rescanIn = 0;
        if (engine == null) {
            REQUESTS.clear();
            trackedEngine = null;
        }
    }

    private static void submitRemove(ColorLightEngine engine, int id) {
        REQUESTS.put(id, new Request(engine, id, true, 0, 0, 0, 0, 0, 0));
        scheduleDrain();
    }

    // ---- worker ----

    private static void scheduleDrain() {
        if (DRAIN_SCHEDULED.compareAndSet(false, true))
            WORKER.execute(SableLightLinker::drain);
    }

    private static void drain() {
        try {
            while (true) {
                Iterator<Request> it = REQUESTS.values().iterator();
                if (!it.hasNext())
                    break;

                Request request = it.next();
                // if a newer request replaced it meanwhile, skip this one and pick up the newer next round
                if (!REQUESTS.remove(request.id(), request))
                    continue;

                try {
                    apply(request);
                } catch (Throwable t) {
                    if (!loggedFailure) {
                        loggedFailure = true;
                        ColorLightClient.LOGGER.warn("[ColorLight] Sable light link update failed (further failures are not logged)", t);
                    }
                }
            }
        } finally {
            DRAIN_SCHEDULED.set(false);
            if (!REQUESTS.isEmpty())
                scheduleDrain();
        }
    }

    private static void apply(Request request) {
        ColorLightEngine engine = request.engine();
        if (workerEngine != engine) {
            workerEngine = engine;
            worker = engine.newDynamicWorker();
        }

        if (request.remove())
            worker.remove(request.id());
        else // colours are already scaled by the source's strength, so the flood runs at full strength
            worker.update(request.id(), request.x(), request.y(), request.z(), request.r(), request.g(), request.b(), 15);
    }

    private SableLightLinker() {
    }
}
