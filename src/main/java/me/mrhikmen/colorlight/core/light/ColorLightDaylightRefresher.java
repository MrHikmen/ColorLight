package me.mrhikmen.colorlight.core.light;

import me.mrhikmen.colorlight.core.util.ColorLightRenderUtil;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;

import java.util.List;

public final class ColorLightDaylightRefresher {

    private static final int CHECK_INTERVAL_TICKS = 20;
    private static final int SOURCES_PER_TICK = 200;

    private static int tickCounter = 0;
    private static float lastFactor = Float.NaN;

    private static List<BlockPos> pendingSources = null;
    private static int pendingIndex = 0;
    private static int pendingRadius = 0;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {

            ClientLevel level = client.level;
            if (level == null || client.player == null)
                return;

            if (pendingSources != null) {
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

            List<BlockPos> sources = engine.getSourcePositions();
            if (sources.isEmpty())
                return;

            pendingSources = sources;
            pendingIndex = 0;
            pendingRadius = engine.getMaxRangeBlocks() + 1;

            processBatch(level);
        });
    }

    private static void processBatch(ClientLevel level) {
        List<BlockPos> sources = pendingSources;
        int radius = pendingRadius;

        int toIndex = Math.min(sources.size(), pendingIndex + SOURCES_PER_TICK);

        for (int i = pendingIndex; i < toIndex; i++) {
            BlockPos sourcePos = sources.get(i);

            if (level.getBrightness(LightLayer.SKY, sourcePos) <= 0)
                continue;

            ColorLightRenderUtil.setBlocksDirty(level,
                    sourcePos.getX() - radius, sourcePos.getY() - radius, sourcePos.getZ() - radius,
                    sourcePos.getX() + radius, sourcePos.getY() + radius, sourcePos.getZ() + radius
            );
        }

        pendingIndex = toIndex;
        if (pendingIndex >= sources.size()) {
            pendingSources = null;
        }
    }

    private ColorLightDaylightRefresher() {
    }
}
