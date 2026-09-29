package me.mrhikmen.colorlight.client.core.light.runtime;

import me.mrhikmen.colorlight.client.core.light.color.ColorLightUtil;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngine;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngineHolder;
import me.mrhikmen.colorlight.client.core.light.engine.SourceSnapshot;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;

/**
 * Daylight suppresses coloured light, and the suppression changes in steps at dusk and dawn. When it
 * does, every source that can see the sky needs its surroundings re-meshed.
 * <p>
 * Sections are queued in the engine's dirty set (which de-duplicates them and is drained
 * nearest-first by {@link ColorLightDirtyFlusher}) instead of directly re-dirtying a full
 * (2R+2)^3 block cube per source; and the reach used is that of the source's actual brightness.
 */
public final class ColorLightDaylightRefresher {

    private static final int CHECK_INTERVAL_TICKS = 20;
    private static final int SOURCES_PER_TICK = 200;

    private static int tickCounter = 0;
    private static float lastFactor = Float.NaN;

    private static SourceSnapshot pending = null;
    private static ColorLightEngine pendingEngine = null;
    private static int pendingIndex = 0;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ClientLevel level = client.level;
            if (level == null || client.player == null)
                return;

            if (pending != null) {
                processBatch(level);
                return;
            }

            tickCounter++;
            if (tickCounter < CHECK_INTERVAL_TICKS)
                return;
            tickCounter = 0;

            float factor = ColorLightEngine.computeTimeOfDayFactor(level);
            if (Float.compare(factor, lastFactor) == 0)
                return;
            lastFactor = factor;

            ColorLightEngine engine = ColorLightEngineHolder.get();
            if (engine == null)
                return;

            SourceSnapshot sources = engine.getSourceSnapshot();
            if (sources.size() == 0)
                return;

            pending = sources;
            pendingEngine = engine;
            pendingIndex = 0;

            processBatch(level);
        });
    }

    private static void processBatch(ClientLevel level) {
        SourceSnapshot sources = pending;
        ColorLightEngine engine = pendingEngine;

        if (engine != ColorLightEngineHolder.get()) {
            pending = null; // world changed while the refresh was running
            pendingEngine = null;
            return;
        }

        int maxRange = engine.getMaxRangeBlocks();
        float decay = engine.getDecayPerOpacityUnit();
        int toIndex = Math.min(sources.size(), pendingIndex + SOURCES_PER_TICK);

        for (int i = pendingIndex; i < toIndex; i++) {
            int x = sources.x(i);
            int y = sources.y(i);
            int z = sources.z(i);

            if (level.getBrightness(LightLayer.SKY, new BlockPos(x, y, z)) <= 0)
                continue;

            int color = sources.color(i);
            int brightest = Math.max(ColorLightUtil.r(color), Math.max(ColorLightUtil.g(color), ColorLightUtil.b(color)));
            int reach = Math.min(maxRange, (int) Math.ceil(brightest / decay)) + 1;

            engine.markRegionDirty(x - reach, y - reach, z - reach, x + reach, y + reach, z + reach);
        }

        pendingIndex = toIndex;
        if (pendingIndex >= sources.size()) {
            pending = null;
            pendingEngine = null;
        }
    }

    private ColorLightDaylightRefresher() {
    }
}
