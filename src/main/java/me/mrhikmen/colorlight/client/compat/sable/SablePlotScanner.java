package me.mrhikmen.colorlight.client.compat.sable;

import me.mrhikmen.colorlight.client.ColorLightClient;
import me.mrhikmen.colorlight.client.compat.sable.SableBodies.Body;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngine;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngineHolder;
import me.mrhikmen.colorlight.client.core.light.scan.ColorLightChunkScanner;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Finds the light sources of Sable sub-levels that were already there when they arrived.
 * <p>
 * ColorLight learns about light-emitting blocks from the chunk-load event. Sable loads the chunks of a sub-level's
 * plot through its own path (it cancels vanilla's {@code replaceWithPacketData}), so that event never fires for them:
 * a lamp placed on a ship after joining works (that goes through {@code setBlockState}), but every lamp that was
 * already on it when the world was entered stays dark until it is replaced. This class scans the plot chunks of
 * every sub-level itself: when the sub-level is first seen, whenever its set of chunks or its bounds change (data
 * still streaming in), and twice more shortly after as a safety net. Re-adding a known source is a no-op in the
 * engine, so a repeated scan costs only the scan.
 */
public final class SablePlotScanner {

    private static final int CHECK_EVERY_TICKS = 2;
    /** Extra scans, in checks since the sub-level was first seen (a check is {@value #CHECK_EVERY_TICKS} ticks). */
    private static final int[] RETRY_AT = {10, 50, 150, 400};

    private static final class State {
        long signature = Long.MIN_VALUE;
        int age;
        int retry;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();
    private static ColorLightEngine trackedEngine;
    private static int tickCounter;

    public static void tick(Minecraft client) {
        ClientLevel level = client.level;
        ColorLightEngine engine = ColorLightEngineHolder.get();
        if (level == null || engine == null || !ColorLightClient.config.ENABLE) {
            STATES.clear();
            trackedEngine = null;
            return;
        }
        if (engine != trackedEngine) { // new world / rebuilt engine: nothing it knew is left
            STATES.clear();
            trackedEngine = engine;
        }
        if (++tickCounter % CHECK_EVERY_TICKS != 0)
            return;

        java.util.List<Body> bodies = SableBodies.list(level);
        if (bodies.isEmpty()) {
            STATES.clear();
            return;
        }

        Set<UUID> present = new HashSet<>();
        for (Body body : bodies) {
            present.add(body.id());
            State state = STATES.computeIfAbsent(body.id(), id -> new State());
            state.age++;

            try {
                Collection<?> holders = holders(body);
                if (holders == null)
                    continue;

                long signature = holders.size() * 31L + Long.hashCode(java.util.Objects.hash(
                        body.pMinX(), body.pMinY(), body.pMinZ(), body.pMaxX(), body.pMaxY(), body.pMaxZ()));
                boolean retry = state.retry < RETRY_AT.length && state.age >= RETRY_AT[state.retry];
                if (signature == state.signature && !retry)
                    continue;
                if (retry)
                    state.retry++;
                state.signature = signature;

                scan(level, body, holders);
            } catch (Throwable t) {
                ColorLightClient.LOGGER.warn("[ColorLight] Could not scan the chunks of a Sable sub-level for light", t);
            }
        }
        STATES.keySet().retainAll(present);
    }

    private static Collection<?> holders(Body body) throws Exception {
        Object plot = SableAccess.call(body.subLevel(), "getPlot");
        return plot == null ? null : (Collection<?>) SableAccess.call(plot, "getLoadedChunks");
    }

    private static void scan(ClientLevel level, Body body, Collection<?> holders) throws Exception {
        // where the sub-level really is, so the scan queue treats it like the terrain around it
        int orderX = (int) Math.floor((body.gMinX() + body.gMaxX()) * 0.5) >> 4;
        int orderZ = (int) Math.floor((body.gMinZ() + body.gMaxZ()) * 0.5) >> 4;

        for (Object holder : holders.toArray()) {
            Object chunk = SableAccess.call(holder, "getChunk");
            if (chunk instanceof LevelChunk levelChunk)
                ColorLightChunkScanner.scan(level, levelChunk, orderX, orderZ);
        }
    }

    private SablePlotScanner() {
    }
}
