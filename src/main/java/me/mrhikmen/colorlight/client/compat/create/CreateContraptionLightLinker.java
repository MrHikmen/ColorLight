package me.mrhikmen.colorlight.client.compat.create;

import me.mrhikmen.colorlight.client.ColorLightClient;
import me.mrhikmen.colorlight.client.config.BlockSettings;
import me.mrhikmen.colorlight.client.config.ColorLightConfig;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngine;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngineHolder;
import me.mrhikmen.colorlight.client.core.light.registry.ColorLightBlockRegistry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
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
 * Light-emitting blocks of moving Create contraptions (and of Flywheel objects built the same way).
 * <p>
 * The blocks of a contraption are not in the level: Create keeps them in the contraption and Flywheel draws them, so
 * ColorLight's chunk scan never sees them and a lamp on a minecart contraption or a carriage stays dark and lights
 * nothing. Here every light of a nearby contraption becomes a <i>dynamic light</i> (the layer entity lights use) that
 * follows the contraption: its footprint lights the terrain around it and, because Flywheel objects read the same
 * light volume, the contraption itself.
 * <p>
 * Create is read through reflection ({@code AbstractContraptionEntity#getContraption / toGlobalVector},
 * {@code Contraption#getBlocks}); ColorLight does not compile against it. Written for Create-Fly (ZurrTum's Create
 * port for 1.21.11, package {@code com.zurrtum.create}); the original package is accepted too. The game thread decides
 * what each light looks like, a worker floods it, updates per tick are capped.
 */
public final class CreateContraptionLightLinker {

    /** Create-Fly (Minecraft 1.21.11) first, then the original Create's package. */
    private static final String[] ENTITY_CLASSES = {
            "com.zurrtum.create.content.contraptions.AbstractContraptionEntity",
            "com.simibubi.create.content.contraptions.AbstractContraptionEntity"
    };

    private static boolean isEntityClass(String name) {
        for (String candidate : ENTITY_CLASSES) {
            if (candidate.equals(name))
                return true;
        }
        return false;
    }

    private static final int MAX_PER_CONTRAPTION = 24;
    private static final double STEPS_PER_BLOCK = 4.0;
    private static final int RESCAN_TICKS = 40;

    private record Src(int x, int y, int z, int r, int g, int b, int strength, Vec3 center) {
    }

    private record Link(UUID entity, int src) {
    }

    private static final class Tracked {
        int id;
        long qx, qy, qz;
        int strength, color;
    }

    private static final class Known {
        List<Src> sources = List.of();
        int blockCount = -1;
        int age;
    }

    private record Request(ColorLightEngine engine, int id, boolean remove, double x, double y, double z,
                           int r, int g, int b, int strength) {
    }

    // ---- game thread ----
    private static final Map<Link, Tracked> TRACKED = new HashMap<>();
    private static final Set<Link> SEEN = new HashSet<>();
    private static final Map<UUID, Known> KNOWN = new HashMap<>();
    private static ColorLightEngine trackedEngine;
    private static int tickCounter;
    /**
     * Floods allowed per tick: new links have a small budget of their own that updates cannot use up, and the lights are
     * visited in a rotating order, so a fast contraption cannot starve the later lights.
     */
    private static final int UPDATE_SHARE = 3;
    private static final int NEW_SHARE = 2;
    private static int updateBudget, newBudget;
    /** Dynamic light ids: far below any entity id. */
    private static int nextId = -1_000_000_000;

    private static Method getContraption, getBlocks, toGlobalVector;
    private static volatile boolean broken;

    // ---- worker ----
    private static final ConcurrentHashMap<Integer, Request> REQUESTS = new ConcurrentHashMap<>();
    private static final AtomicBoolean DRAIN_SCHEDULED = new AtomicBoolean();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ColorLight Contraption Light");
        thread.setDaemon(true);
        thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        return thread;
    });
    private static ColorLightEngine workerEngine;
    private static net.minecraft.resources.Identifier workerMethod;
    private static ColorLightEngine.DynamicLightWorker worker;
    /** Method the worker should spread with (the "Create light method" setting); written on the game thread. */
    private static volatile net.minecraft.resources.Identifier wantedMethod;
    private static net.minecraft.resources.Identifier lastMethod;
    private static boolean loggedFailure;

    public static void tick(Minecraft client) {
        ClientLevel level = client.level;
        ColorLightEngine engine = ColorLightEngineHolder.get();
        ColorLightConfig config = ColorLightClient.config;

        if (broken || level == null || client.player == null || engine == null || !config.ENABLE
                || !config.CREATE_CONTRAPTION_LIGHT || ColorLightBlockRegistry.isEmpty()) {
            dropAll(engine);
            return;
        }
        if (engine != trackedEngine) {
            TRACKED.clear();
            REQUESTS.clear();
            KNOWN.clear();
            trackedEngine = engine;
        }

        net.minecraft.resources.Identifier method = config.sablePropagationId();
        wantedMethod = method;
        if (!java.util.Objects.equals(method, lastMethod)) { // the setting changed: every light is spread again with the new method
            lastMethod = method;
            dropAll(engine);
            trackedEngine = engine;
        }

        int chunks = config.ENTITY_CHECK_FOLLOW_RENDER_DISTANCE
                ? client.options.renderDistance().get()
                : config.ENTITY_CHECK_RADIUS_CHUNKS;
        double radius = chunks * 16.0;
        double radiusSq = radius * radius;

        SEEN.clear();
        tickCounter++;
        updateBudget = UPDATE_SHARE;
        newBudget = NEW_SHARE;
        Set<UUID> present = new HashSet<>();

        try {
            for (Entity entity : level.entitiesForRendering()) {
                if (!isContraption(entity))
                    continue;
                if (entity.distanceToSqr(client.player) > radiusSq)
                    continue;

                present.add(entity.getUUID());
                Known known = known(entity);
                if (known.sources.isEmpty())
                    continue;

                UUID uuid = entity.getUUID();
                int n = known.sources.size();
                for (int k = 0; k < n; k++) {
                    int i = (k + tickCounter) % n; // rotating start: a spent budget does not always hit the same lights
                    Src src = known.sources.get(i);
                    Vec3 global = (Vec3) toGlobalVector.invoke(entity, src.center(), 1.0f);

                    track(engine, new Link(uuid, i), global.x, global.y, global.z, src);
                }
            }
        } catch (Throwable t) {
            broken = true;
            ColorLightClient.LOGGER.warn("[ColorLight] Create's contraption classes are not what ColorLight expects; light of contraptions is off", t);
            dropAll(engine);
            return;
        }

        KNOWN.keySet().retainAll(present);

        Iterator<Map.Entry<Link, Tracked>> it = TRACKED.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Link, Tracked> entry = it.next();
            if (!SEEN.contains(entry.getKey())) {
                submitRemove(engine, entry.getValue().id);
                it.remove();
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------

    /** Whether a class is (a subclass of) Create's contraption entity; decided once per class, not per entity per tick. */
    private static final ClassValue<Boolean> IS_CONTRAPTION = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                if (isEntityClass(c.getName()))
                    return Boolean.TRUE;
            }
            return Boolean.FALSE;
        }
    };

    private static boolean isContraption(Entity entity) {
        if (!IS_CONTRAPTION.get(entity.getClass()))
            return false;
        if (toGlobalVector == null) {
            for (Class<?> c = entity.getClass(); c != null; c = c.getSuperclass()) {
                if (isEntityClass(c.getName())) {
                    resolve(c, entity);
                    break;
                }
            }
        }
        return !broken;
    }

    private static void resolve(Class<?> abstractEntity, Entity entity) {
        try {
            getContraption = abstractEntity.getMethod("getContraption");
            Class<?> contraption = getContraption.getReturnType();
            getBlocks = contraption.getMethod("getBlocks");
            toGlobalVector = abstractEntity.getMethod("toGlobalVector", Vec3.class, float.class);
        } catch (Throwable t) {
            broken = true;
            ColorLightClient.LOGGER.warn("[ColorLight] Create's contraption classes are not what ColorLight expects; light of contraptions is off", t);
        }
    }

    /** The light sources of a contraption's blocks; rebuilt when the block count changes and now and then. */
    @SuppressWarnings("unchecked")
    private static Known known(Entity entity) throws Exception {
        Known known = KNOWN.computeIfAbsent(entity.getUUID(), id -> new Known());
        Object contraption = getContraption.invoke(entity);
        if (contraption == null)
            return known;
        Map<BlockPos, StructureTemplate.StructureBlockInfo> blocks = (Map<BlockPos, StructureTemplate.StructureBlockInfo>) getBlocks.invoke(contraption);
        if (blocks == null)
            return known;

        known.age++;
        if (known.blockCount == blocks.size() && known.age < RESCAN_TICKS)
            return known;
        known.age = 0;
        known.blockCount = blocks.size();

        List<Src> found = new ArrayList<>();
        for (StructureTemplate.StructureBlockInfo info : blocks.values()) {
            BlockState state = info.state();
            int emission = state.getLightEmission();
            if (emission <= 0)
                continue;
            BlockSettings entry = ColorLightBlockRegistry.get(state.getBlock());
            if (entry == null)
                continue;
            BlockPos pos = info.pos();
            found.add(new Src(pos.getX(), pos.getY(), pos.getZ(), entry.r, entry.g, entry.b, Math.min(entry.light, emission), Vec3.atCenterOf(pos)));
        }
        found.sort((a, b) -> Integer.compare(b.strength(), a.strength()));
        if (found.size() > MAX_PER_CONTRAPTION)
            found = new ArrayList<>(found.subList(0, MAX_PER_CONTRAPTION));
        known.sources = found;
        return known;
    }

    private static void track(ColorLightEngine engine, Link key, double x, double y, double z, Src src) {
        SEEN.add(key);

        long qx = Math.round(x * STEPS_PER_BLOCK);
        long qy = Math.round(y * STEPS_PER_BLOCK);
        long qz = Math.round(z * STEPS_PER_BLOCK);
        int color = (src.r() << 16) | (src.g() << 8) | src.b();

        Tracked tracked = TRACKED.get(key);
        if (tracked == null) {
            if (newBudget > 0)
                newBudget--;
            else if (updateBudget > 0)
                updateBudget--;
            else
                return;
            tracked = new Tracked();
            tracked.id = nextId--;
            TRACKED.put(key, tracked);
        } else {
            if (tracked.qx == qx && tracked.qy == qy && tracked.qz == qz && tracked.strength == src.strength()
                    && tracked.color == color && !engine.isDynamicStale(tracked.id))
                return;
            if (updateBudget <= 0)
                return;
            updateBudget--;
        }

        tracked.qx = qx;
        tracked.qy = qy;
        tracked.qz = qz;
        tracked.strength = src.strength();
        tracked.color = color;

        // x as is, y minus the half block (z is shifted by the flood itself)
        REQUESTS.put(tracked.id, new Request(engine, tracked.id, false, x, y - 0.5, z, src.r(), src.g(), src.b(), src.strength()));
        scheduleDrain();
    }

    private static void dropAll(ColorLightEngine engine) {
        if (!TRACKED.isEmpty() && engine != null && engine == trackedEngine) {
            for (Tracked tracked : TRACKED.values())
                submitRemove(engine, tracked.id);
        }
        TRACKED.clear();
        KNOWN.clear();
        SEEN.clear();
        if (engine == null) {
            REQUESTS.clear();
            trackedEngine = null;
        }
    }

    private static void submitRemove(ColorLightEngine engine, int id) {
        REQUESTS.put(id, new Request(engine, id, true, 0, 0, 0, 0, 0, 0, 0));
        scheduleDrain();
    }

    private static void scheduleDrain() {
        if (DRAIN_SCHEDULED.compareAndSet(false, true))
            WORKER.execute(CreateContraptionLightLinker::drain);
    }

    private static void drain() {
        try {
            while (true) {
                Iterator<Request> it = REQUESTS.values().iterator();
                if (!it.hasNext())
                    break;
                Request request = it.next();
                if (!REQUESTS.remove(request.id(), request))
                    continue;
                try {
                    apply(request);
                } catch (Throwable t) {
                    if (!loggedFailure) {
                        loggedFailure = true;
                        ColorLightClient.LOGGER.warn("[ColorLight] Contraption light update failed (further failures are not logged)", t);
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
        net.minecraft.resources.Identifier method = wantedMethod;
        if (workerEngine != engine || !java.util.Objects.equals(workerMethod, method)) {
            workerEngine = engine;
            workerMethod = method;
            worker = engine.newDynamicWorker(method);
        }
        if (request.remove())
            worker.remove(request.id());
        else
            worker.update(request.id(), request.x(), request.y(), request.z(), request.r(), request.g(), request.b(), request.strength());
    }

    private CreateContraptionLightLinker() {
    }
}
