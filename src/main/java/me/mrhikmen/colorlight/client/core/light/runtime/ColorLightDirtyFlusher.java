package me.mrhikmen.colorlight.client.core.light.runtime;

import me.mrhikmen.colorlight.client.ColorLightClient;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngine;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngineHolder;
import me.mrhikmen.colorlight.client.core.light.util.LongIntMap;
import me.mrhikmen.colorlight.client.core.light.util.PosKey;
import me.mrhikmen.colorlight.client.core.util.ColorLightRenderUtil;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import java.util.Arrays;

/**
 * Once per tick, hands the render sections the engine reports as changed to the chunk renderer.
 * <ul>
 *     <li>Duplicates collapse (a section touched by 500 cells is one rebuild, not 500 requests).</li>
 *     <li>At most {@link #MAX_SECTIONS_PER_TICK} sections are marked per tick, nearest to the player
 *     first, so a burst (joining a world, dawn/dusk refresh) is spread over several ticks instead of
 *     landing in one frame.</li>
 *     <li>If the renderer isn't ready yet the sections are kept and retried.</li>
 * </ul>
 */
public final class ColorLightDirtyFlusher {

    private static final int MAX_SECTIONS_PER_TICK = 128;
    /** Hard time cap per tick on top of the count cap; whatever is left simply waits for the next tick. */
    private static final long MAX_NANOS_PER_TICK = 1_500_000L;
    private static final int MAX_FAILED_TICKS = 200;

    private static final LongIntMap PENDING = new LongIntMap(256);
    private static ColorLightEngine lastEngine;
    private static int failedTicks;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ClientLevel level = client.level;
            ColorLightEngine engine = ColorLightEngineHolder.get();

            if (level == null || client.player == null || engine == null) {
                PENDING.clear();
                lastEngine = null;
                return;
            }

            if (engine != lastEngine) {
                PENDING.clear(); // sections of a previous world/engine are meaningless now
                lastEngine = engine;
            }

            for (long key : engine.drainDirtySections()) {
                PENDING.put(key, 1);
            }
            if (PENDING.isEmpty())
                return;

            flush(level, client.player.blockPosition());
        });
    }

    private static void flush(ClientLevel level, BlockPos playerPos) {
        long[] keys = PENDING.keysToArray();
        int take = keys.length;

        if (keys.length > MAX_SECTIONS_PER_TICK) {
            keys = nearestFirst(keys, playerPos.getX() >> 4, playerPos.getY() >> 4, playerPos.getZ() >> 4);
            take = MAX_SECTIONS_PER_TICK;
        }

        long start = System.nanoTime();
        for (int i = 0; i < take; i++) {
            if ((i & 15) == 15 && System.nanoTime() - start > MAX_NANOS_PER_TICK)
                return; // out of time: the rest stays in PENDING for the next tick

            long key = keys[i];
            if (!ColorLightRenderUtil.setSectionDirty(level, PosKey.x(key), PosKey.y(key), PosKey.z(key))) {
                // renderer not ready: keep this and everything after it for the next tick
                if (++failedTicks > MAX_FAILED_TICKS) {
                    ColorLightClient.LOGGER.warn("[ColorLight] Renderer never became ready; dropping {} pending section updates.", PENDING.size());
                    PENDING.clear();
                    failedTicks = 0;
                }
                return;
            }
            PENDING.remove(key);
        }
        failedTicks = 0;
    }

    private static long[] nearestFirst(long[] keys, int psx, int psy, int psz) {
        long[] order = new long[keys.length];
        for (int i = 0; i < keys.length; i++) {
            long dx = PosKey.x(keys[i]) - psx;
            long dy = PosKey.y(keys[i]) - psy;
            long dz = PosKey.z(keys[i]) - psz;
            order[i] = ((dx * dx + dy * dy + dz * dz) << 32) | i;
        }
        Arrays.sort(order);

        long[] sorted = new long[keys.length];
        for (int i = 0; i < order.length; i++) {
            sorted[i] = keys[(int) (order[i] & 0xFFFFFFFFL)];
        }
        return sorted;
    }

    private ColorLightDirtyFlusher() {
    }
}
