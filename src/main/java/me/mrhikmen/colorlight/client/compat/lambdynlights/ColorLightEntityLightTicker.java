package me.mrhikmen.colorlight.client.compat.lambdynlights;

import me.mrhikmen.colorlight.client.ColorLightClient;
import me.mrhikmen.colorlight.client.config.BlockSettings;
import me.mrhikmen.colorlight.client.config.ColorLightConfig;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngine;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngineHolder;
import me.mrhikmen.colorlight.client.core.light.engine.LightBeam;
import me.mrhikmen.colorlight.client.core.light.registry.ColorLightBlockRegistry;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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

    /** Light is only recomputed after the entity moved at least this fraction of a block (1/8). */
    private static final double POSITION_STEPS_PER_BLOCK = 8.0;

    /** {@code beam} is non-null for a directional light: the cone along the holder's view. */
    private record Request(ColorLightEngine engine, int id, boolean remove,
                           double x, double y, double z, int r, int g, int b, int strength, LightBeam beam) {
    }

    /** Last state sent to the worker for one entity. Game thread only. */
    private static final class Tracked {
        long qx, qy, qz;
        /** View angles, in whole degrees, of the last directional light sent (0 for point lights). */
        int qYaw, qPitch;
        /** Flashlight range, in percent, of the last directional light sent. */
        int qRange;
        BlockSettings settings;
        int strength;
        int lastSeenTick;
    }

    /**
     * Plain white light for entities LambDynamicLights lights up but ColorLight has no colour for (a belt lantern, a
     * glow squid, a burning mob ...). ColorLight only knows blocks, and on the GPU path it draws all block light, so
     * without this such an entity gave no light at all. One shared instance: the tracker compares settings by identity.
     */
    private static final BlockSettings UNKNOWN_ENTITY_WHITE = whiteSettings();

    private static BlockSettings whiteSettings() {
        BlockSettings white = new BlockSettings(Identifier.fromNamespaceAndPath("colorlight", "unknown_entity"), 15, true);
        white.r = 255;
        white.g = 255;
        white.b = 255;
        return white;
    }

    /**
     * Illuminated's flashlight. Its light is a LambDynamicLights behaviour (a cone written into vanilla block light),
     * and ColorLight never reads vanilla block light, so without this the flashlight lit nothing at all. Here it is a
     * white directional light: a cone from the holder's eyes along the view, with the same shape as Illuminated's
     * (radius 8 at depth 20).
     */
    private static final BlockSettings FLASHLIGHT_SETTINGS = flashlightSettings();

    /** Light strength at the flashlight (0-15 scale, like a torch at full power). */
    private static final int FLASHLIGHT_STRENGTH = 15;
    /** Illuminated's cone: RADIUS 8, DEPTH 20, DISTANCE_DELTA 3 (same constants as its FlashlightLightBehavior). */
    private static final double FLASHLIGHT_RADIUS = 8.0;
    private static final double FLASHLIGHT_DEPTH = 20.0;
    private static final double FLASHLIGHT_OFFSET = 3.0;

    private static BlockSettings flashlightSettings() {
        BlockSettings warm = new BlockSettings(Identifier.fromNamespaceAndPath("colorlight", "illuminated_flashlight"), 15, true);
        warm.r = 255;
        warm.g = 255;
        warm.b = 255;
        return warm;
    }

    private static final Identifier FLASHLIGHT_ITEM_ID = Identifier.fromNamespaceAndPath("illuminated", "flashlight");
    private static final Identifier FLASHLIGHT_ON_COMPONENT_ID = Identifier.fromNamespaceAndPath("illuminated", "on");

    // Resolved lazily, once the registries exist; null while Illuminated is not loaded (then never matches).
    private static Item flashlightItem;
    private static DataComponentType<?> flashlightOnComponent;

    /** True if the entity holds an Illuminated flashlight that is switched on, in either hand (same rule as Illuminated). */
    private static boolean holdsLitIlluminatedFlashlight(LivingEntity living) {
        if (flashlightItem == null) {
            Item item = BuiltInRegistries.ITEM.getValue(FLASHLIGHT_ITEM_ID);
            if (item == null || item == Items.AIR)
                return false;
            flashlightItem = item;
        }
        if (flashlightOnComponent == null) {
            flashlightOnComponent = BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(FLASHLIGHT_ON_COMPONENT_ID);
            if (flashlightOnComponent == null)
                return false;
        }
        return isLitFlashlight(living.getMainHandItem()) || isLitFlashlight(living.getOffhandItem());
    }

    private static boolean isLitFlashlight(ItemStack stack) {
        return stack.is(flashlightItem) && Boolean.TRUE.equals(stack.get(flashlightOnComponent));
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
        int strength = glow.strengthOverride() >= 0
                ? glow.strengthOverride()
                : effectiveStrength(settings, glow.stack());

        double x = entity.getX();
        double y = glow.directional() ? entity.getEyeY() : entity.getY();
        double z = entity.getZ();
        long qx = Math.round(x * POSITION_STEPS_PER_BLOCK);
        long qy = Math.round(y * POSITION_STEPS_PER_BLOCK);
        long qz = Math.round(z * POSITION_STEPS_PER_BLOCK);

        // a cone also depends on where the holder looks: track the view in steps of 2 degrees
        int qYaw = glow.directional() ? 2 * Math.round(entity.getYRot() / 2f) : 0;
        int qPitch = glow.directional() ? 2 * Math.round(entity.getXRot() / 2f) : 0;
        int qRange = glow.directional() ? ColorLightClient.config.FLASHLIGHT_RANGE_PERCENT : 0;

        if (tracked == null) {
            tracked = new Tracked();
            TRACKED.put(id, tracked);
        } else {
            tracked.lastSeenTick = tickCounter;
            // unchanged and no block changed inside its light: nothing to do
            if (tracked.qx == qx && tracked.qy == qy && tracked.qz == qz
                    && tracked.qYaw == qYaw && tracked.qPitch == qPitch && tracked.qRange == qRange
                    && tracked.settings == settings && tracked.strength == strength && !engine.isDynamicStale(id))
                return;
        }

        tracked.lastSeenTick = tickCounter;
        tracked.qx = qx;
        tracked.qy = qy;
        tracked.qz = qz;
        tracked.qYaw = qYaw;
        tracked.qPitch = qPitch;
        tracked.qRange = qRange;
        tracked.settings = settings;
        tracked.strength = strength;

        // range scales radius, depth and offset together, so the cone keeps its angle
        double scale = qRange / 100.0;
        LightBeam beam = glow.directional()
                ? LightBeam.fromView(qYaw, qPitch, FLASHLIGHT_RADIUS * scale, FLASHLIGHT_DEPTH * scale, FLASHLIGHT_OFFSET * scale)
                : null;
        REQUESTS.put(id, new Request(engine, id, false, x, y, z,
                settings.r, settings.g, settings.b, strength, beam));
        scheduleDrain();
    }

    private static void submitRemove(ColorLightEngine engine, int id) {
        REQUESTS.put(id, new Request(engine, id, true, 0, 0, 0, 0, 0, 0, 0, null));
        scheduleDrain();
    }

    /** The strength ColorLight should actually emit at: whatever a resource pack says via LambDynamicLights for
     *  this exact stack - 0, 15, or anything in between - if it says anything, otherwise the block's own
     *  configured strength. No ceiling is applied here: {@link me.mrhikmen.colorlight.client.core.light.engine.DynamicFlood}
     *  already normalises whatever strength it's given against the real 0-15 scale, so clamping it a second time
     *  to some smaller, ColorLight-specific number would just quietly cut off the top of the range a resource pack
     *  is explicitly asking for. */
    private static int effectiveStrength(BlockSettings settings, ItemStack stack) {
        return stack.isEmpty()
                ? settings.light
                : LdlItemLuminanceOverrides.resolve(stack, settings.light);
    }

    // ---- item / block -> light settings (registry lookup, no config scans) ----

    /** A matched glow source: its settings, and the stack it came from (empty for the minecart/BlockState case). */
    /** {@code directional}: a cone from the eyes along the view (the flashlight), instead of a point at the entity. */
    private record Glow(BlockSettings settings, ItemStack stack, int strengthOverride, boolean directional) {
        Glow(BlockSettings settings, ItemStack stack) {
            this(settings, stack, -1, false);
        }

        Glow(BlockSettings settings, ItemStack stack, int strengthOverride) {
            this(settings, stack, strengthOverride, false);
        }
    }

    private static Glow findGlowingSource(Entity entity) {
        Glow known = findKnownGlow(entity);
        if (known != null)
            return known;

        // ColorLight has no colour for this entity. If LambDynamicLights still lights it (a belt lantern, a glow
        // squid, a burning mob ...), give it a plain white light of the same brightness instead of no light at all.
        int luminance = LdlEntityLuminance.of(entity);
        return luminance > 0 ? new Glow(UNKNOWN_ENTITY_WHITE, ItemStack.EMPTY, luminance) : null;
    }

    private static Glow findKnownGlow(Entity entity) {
        if (entity instanceof LivingEntity living) {
            // A switched-on flashlight comes first: the player turned it on on purpose.
            if (holdsLitIlluminatedFlashlight(living))
                return new Glow(FLASHLIGHT_SETTINGS, ItemStack.EMPTY, FLASHLIGHT_STRENGTH, true);

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

        // Whether a resource pack turned this item's light off, dimmed it, or left it alone is decided later, in
        // effectiveStrength() - a single place instead of two, using the same LdlItemLuminanceOverrides.resolve()
        // for both. A pack that explicitly sets 0 naturally ends up with no visible light there (the propagator
        // has nothing to spread), with no separate "is it disabled" check needed here.
        return ColorLightBlockRegistry.get(blockItem.getBlock());
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
        } else if (request.beam() != null) {
            // a directional light keeps its full strength (see LightBeam)
            worker.update(request.id(), request.x(), request.y(), request.z(),
                    request.r(), request.g(), request.b(), request.strength(), request.beam());
        } else {
            worker.update(request.id(), request.x(), request.y(), request.z(),
                    request.r(), request.g(), request.b(), request.strength()/2);
        }
    }

    private ColorLightEntityLightTicker() {
    }
}