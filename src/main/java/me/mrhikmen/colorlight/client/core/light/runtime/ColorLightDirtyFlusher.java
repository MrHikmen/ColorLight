package me.mrhikmen.colorlight.client.core.light.runtime;

import me.mrhikmen.colorlight.client.ColorLightClient;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngine;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngineHolder;
import me.mrhikmen.colorlight.client.core.light.util.LongIntMap;
import me.mrhikmen.colorlight.client.core.light.util.PosKey;
import me.mrhikmen.colorlight.client.core.render.ColorLightRenderUtil;
import me.mrhikmen.colorlight.client.core.render.gpu.ColorLightGpu;
import me.mrhikmen.colorlight.client.compat.voxy.LodLight;

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

    /**
     * Was 128. Too low for a single interactive edit: removing (or placing) one static source in an
     * open/low-opacity area floods {@code maxRangeBlocks} blocks in every direction, which easily touches
     * several hundred sections in one {@link ColorLightEngine} operation (see {@code darkenAndCollectSeeds}).
     * At 128/tick that burst used to be spread over many ticks; nearest-first order meant sections near the
     * player updated first and far ones kept showing the old (stale) colour for several more ticks, i.e. a
     * visible wavefront of flicker/patchy colour radiating outward every time a light source was removed or
     * added - not a single bad frame, but a glitch lasting up to a second or more. Raised so a normal
     * interactive edit finishes in one tick; {@link #MAX_NANOS_PER_TICK} remains the real safety valve against
     * pathological bursts (e.g. thousands of sections right after joining a world).
     */
    private static final int MAX_SECTIONS_PER_TICK = 2048;
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

            long[] drained = engine.drainDirtySections();

            // GPU pipeline: the shader reads light straight from the light volume, so no mesh depends on it
            // (except right after the pipeline switched on or off, when every mesh has to be built again).
            if (drained.length > 0)
                LodLight.markDirty(drained); // far-away light (Voxy), kept whatever way the terrain is lit

            boolean gpu = ColorLightGpu.isActive();
            if (gpu && drained.length > 0)
                ColorLightGpu.onSectionsChanged(drained);
            if (gpu && !ColorLightGpu.meshRebuildPending()) {
                PENDING.clear();
                return;
            }

            for (long key : drained) {
                PENDING.put(key, 1);
            }
            if (PENDING.isEmpty()) {
                ColorLightGpu.meshRebuildDone();
                return;
            }

            flush(level, client.player.blockPosition());
            if (PENDING.isEmpty())
                ColorLightGpu.meshRebuildDone();
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
