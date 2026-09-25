package me.mrhikmen.colorlight.client.compat.lambdynlights;

import me.mrhikmen.colorlight.client.ColorLightClient;
import me.mrhikmen.colorlight.client.config.BlockSettings;
import me.mrhikmen.colorlight.client.config.ColorLightConfig;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngine;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngineHolder;
import me.mrhikmen.colorlight.client.core.light.registry.ColorLightBlockRegistry;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps a coloured light glowing on entities that hold/are a light-emitting block.
 * <p>
 * The game thread only decides <i>what</i> each entity's light should currently look like; the
 * expensive part (flooding the light through the surrounding blocks) runs on a dedicated worker
 * thread into the engine's separate dynamic layer. Requests are coalesced per entity - if the worker
 * is busy the newest position simply replaces the older one - so walking around, or a crowd of
 * light-holding mobs, can never stall a frame or build up a backlog.
 */
public final class ColorLightEntityLightTicker {

    /** Same ceiling the old implementation used for entity light. */
    private static final int MAX_ENTITY_STRENGTH = 7;

    /** Light is only recomputed after the entity moved at least this fraction of a block (1/8). */
    private static final double POSITION_STEPS_PER_BLOCK = 8.0;

    private record Request(ColorLightEngine engine, int id, boolean remove,
                           double x, double y, double z, int r, int g, int b, int strength) {
    }

    /** Last state sent to the worker for one entity. Game thread only. */
    private static final class Tracked {
        long qx, qy, qz;
        BlockSettings settings;
        int strength;
        int lastSeenTick;
    }

    private static final Map<Integer, Tracked> TRACKED = new HashMap<>();
    private static ColorLightEngine trackedEngine;
    private static int tickCounter;

    private static final ConcurrentHashMap<Integer, Request> REQUESTS = new ConcurrentHashMap<>();
    private static final AtomicBoolean DRAIN_SCHEDULED = new AtomicBoolean();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ColorLight Dynamic Light");
        thread.setDaemon(true);
        thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        return thread;
    });

    // worker thread only
    private static ColorLightEngine workerEngine;
    private static ColorLightEngine.DynamicLightWorker worker;
    private static boolean loggedFailure;

    public static void register() {
        if (!ColorLightLambDynLightsCompat.isPresent())
            return;

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ClientLevel level = client.level;
            if (level == null || client.player == null)
                return;
            ColorLightEngine engine = ColorLightEngineHolder.get();
            if (engine == null)
                return;

            if (engine != trackedEngine) {
                // new world / rebuilt engine: everything tracked belonged to the old one
                TRACKED.clear();
                REQUESTS.clear();
                trackedEngine = engine;
            }

            if (!ColorLightClient.config.ENTITY_TRACKING_ENABLED) {
                removeAllTracked(engine);
                return;
            }

            tickCounter++;

            BlockPos playerPos = client.player.blockPosition();
            int checkRadiusBlocks = checkRadiusBlocks(client.options.renderDistance().get());

            for (Entity entity : level.entitiesForRendering()) {
                if (!isTrackableSource(entity))
                    continue;

                if (entity.blockPosition().distManhattan(playerPos) > checkRadiusBlocks)
                    continue;

                processEntity(engine, entity);
            }

            // anything not seen this tick left range, despawned or dropped the light
            Iterator<Map.Entry<Integer, Tracked>> it = TRACKED.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Integer, Tracked> entry = it.next();
                if (entry.getValue().lastSeenTick != tickCounter) {
                    submitRemove(engine, entry.getKey());
                    it.remove();
                }
            }
        });
    }

    private static boolean isTrackableSource(Entity entity) {
        return entity instanceof LivingEntity
                || entity instanceof ItemEntity
                || entity instanceof ItemFrame
                || entity instanceof AbstractMinecart;
    }

    private static int checkRadiusBlocks(int clientRenderDistanceChunks) {
        ColorLightConfig config = ColorLightClient.config;
        int chunks = config.ENTITY_CHECK_FOLLOW_RENDER_DISTANCE
                ? clientRenderDistanceChunks
                : config.ENTITY_CHECK_RADIUS_CHUNKS;
        return chunks * 16;
    }

    private static void removeAllTracked(ColorLightEngine engine) {
        if (TRACKED.isEmpty())
            return;

        for (Integer id : TRACKED.keySet()) {
            submitRemove(engine, id);
        }
        TRACKED.clear();
    }

    private static void processEntity(ColorLightEngine engine, Entity entity) {
        int id = entity.getId();
        Glow glow = findGlowingSource(entity);
        Tracked tracked = TRACKED.get(id);

        if (glow == null) {
            if (tracked != null) {
                submitRemove(engine, id);
                TRACKED.remove(id);
            }
            return;
        }

        BlockSettings settings = glow.settings();
        int strength = effectiveStrength(settings, glow.stack());

        double x = entity.getX();
        double y = entity.getY();
        double z = entity.getZ();
        long qx = Math.round(x * POSITION_STEPS_PER_BLOCK);
        long qy = Math.round(y * POSITION_STEPS_PER_BLOCK);
        long qz = Math.round(z * POSITION_STEPS_PER_BLOCK);

        if (tracked == null) {
            tracked = new Tracked();
            TRACKED.put(id, tracked);
        } else {
            tracked.lastSeenTick = tickCounter;
            // unchanged and no block changed inside its light: nothing to do
            if (tracked.qx == qx && tracked.qy == qy && tracked.qz == qz
                    && tracked.settings == settings && tracked.strength == strength && !engine.isDynamicStale(id))
                return;
        }

        tracked.lastSeenTick = tickCounter;
        tracked.qx = qx;
        tracked.qy = qy;
        tracked.qz = qz;
        tracked.settings = settings;
        tracked.strength = strength;

        REQUESTS.put(id, new Request(engine, id, false, x, y, z,
                settings.r, settings.g, settings.b, strength));
        scheduleDrain();
    }

    private static void submitRemove(ColorLightEngine engine, int id) {
        REQUESTS.put(id, new Request(engine, id, true, 0, 0, 0, 0, 0, 0, 0));
        scheduleDrain();
    }

    /** The strength ColorLight should actually emit at: whatever a resource pack says via LambDynamicLights for
     *  this exact stack, if it says anything, otherwise the block's own configured strength. */
    private static int effectiveStrength(BlockSettings settings, ItemStack stack) {
        if (!stack.isEmpty()) {
            int overrideLuminance = LdlItemLuminanceOverrides.matchedLuminance(stack);
            if (overrideLuminance >= 0)
                return Math.min(MAX_ENTITY_STRENGTH, overrideLuminance);
        }
        return Math.min(MAX_ENTITY_STRENGTH, settings.light);
    }

    // ---- item / block -> light settings (registry lookup, no config scans) ----

    /** A matched glow source: its settings, and the stack it came from (empty for the minecart/BlockState case). */
    private record Glow(BlockSettings settings, ItemStack stack) {
    }

    private static Glow findGlowingSource(Entity entity) {
        if (entity instanceof LivingEntity living) {
            ItemStack main = living.getMainHandItem();
            ItemStack off = living.getOffhandItem();
            if (main.isEmpty() && off.isEmpty())
                return null;

            Glow fromMain = glowOf(main);
            if (fromMain != null)
                return fromMain;
            return glowOf(off);
        }

        if (entity instanceof ItemEntity itemEntity) {
            return glowOf(itemEntity.getItem());
        }

        if (entity instanceof ItemFrame itemFrame) {
            return glowOf(itemFrame.getItem());
        }

        if (entity instanceof AbstractMinecart minecart) {
            BlockSettings settings = settingsOf(minecart.getDisplayBlockState());
            return settings == null ? null : new Glow(settings, ItemStack.EMPTY);
        }

        return null;
    }

    private static Glow glowOf(ItemStack stack) {
        BlockSettings settings = settingsOf(stack);
        return settings == null ? null : new Glow(settings, stack);
    }

    private static BlockSettings settingsOf(ItemStack stack) {
        if (stack.isEmpty())
            return null;
        if (!(stack.getItem() instanceof BlockItem blockItem))
            return null;

        BlockSettings settings = ColorLightBlockRegistry.get(blockItem.getBlock());
        if (settings == null)
            return null;

        // A resource pack set this item to "luminance": 0 for LambDynamicLights: it is not a light source, so
        // ColorLight doesn't light it either. Only stacks that would otherwise glow get here, so this is cheap.
        // Any other explicit luminance is applied later, in effectiveStrength().
        if (LdlItemLuminanceOverrides.isDisabled(stack))
            return null;

        return settings;
    }

    private static BlockSettings settingsOf(BlockState state) {
        if (state == null || state.isAir())
            return null;

        return ColorLightBlockRegistry.get(state.getBlock());
    }

    // ---- worker ----

    private static void scheduleDrain() {
        if (DRAIN_SCHEDULED.compareAndSet(false, true)) {
            WORKER.execute(ColorLightEntityLightTicker::drain);
        }
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
                        ColorLightClient.LOGGER.warn("[ColorLight] Dynamic light update failed (further failures are not logged)", t);
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

        if (request.remove()) {
            worker.remove(request.id());
        } else {
            worker.update(request.id(), request.x(), request.y(), request.z(),
                    request.r(), request.g(), request.b(), request.strength());
        }
    }

    private ColorLightEntityLightTicker() {
    }
}