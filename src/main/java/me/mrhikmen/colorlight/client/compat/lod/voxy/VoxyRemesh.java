package me.mrhikmen.colorlight.client.compat.lod.voxy;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;

import me.mrhikmen.colorlight.client.ColorLightClient;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Asks Voxy to build the sections around a light source again once the source is known (or gone): which quads are
 * split into single cells ({@link LodSplit}) depends on where the sources are, and the sections are first meshed before
 * the scanner has read them.
 * <p>
 * Only the sources that changed are looked at, and only the sections their light can reach, on the levels that are
 * split.
 * <p>
 * Voxy's {@code RenderGenerationService} is reached by reflection (it is only a name here: {@code enqueueTask(long)}), so a
 * Voxy that renamed or removed it only costs the rebuilds, never the game.
 */
public final class VoxyRemesh {

    private static volatile WeakReference<Object> service = new WeakReference<>(null);
    private static final ThreadLocal<Boolean> INTERNAL = new ThreadLocal<>();

    private static Method enqueue;
    private static boolean enqueueResolved;
    private static boolean failed;

    private VoxyRemesh() {
    }

    /** Called by the mixin whenever Voxy queues a section: remembers Voxy's service. */
    public static void remember(Object s) {
        if (service.get() != s)
            service = new WeakReference<>(s);
    }

    /** True while this thread is queueing a section on our own account (the hook then does not treat it as a change). */
    public static boolean isInternal() {
        return INTERNAL.get() != null;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((y + 2048) & 0xFFFL);
    }

    private static boolean enqueue(Object svc, long id) {
        if (failed)
            return false;
        try {
            if (!enqueueResolved) {
                enqueueResolved = true;
                Method m = svc.getClass().getDeclaredMethod("enqueueTask", long.class);
                m.setAccessible(true);
                enqueue = m;
            }
            if (enqueue == null)
                return false;
            enqueue.invoke(svc, id);
            return true;
        } catch (Throwable t) {
            failed = true;
            ColorLightClient.LOGGER.warn("[ColorLight] Asking Voxy to rebuild sections near lights is not possible with this Voxy; far quads are split only when Voxy meshes them anyway ({})", t.toString());
            return false;
        }
    }

    /**
     * @param old   the column's sources before ({@code x, y, z, rgb} each), or null
     * @param fresh the column's sources now
     * @param all   build around every source of {@code fresh} (the user asked for a new import), not only the changed ones
     */
    static void request(WorldEngine engine, int[] old, int[] fresh, boolean all) {
        Object svc = service.get();
        if (svc == null || failed || !LodSplit.enabled())
            return;

        Map<Long, Integer> before = new HashMap<>();
        if (old != null)
            for (int i = 0; i + 3 < old.length; i += 4)
                before.put(key(old[i], old[i + 1], old[i + 2]), old[i + 3]);

        Set<Long> sections = new HashSet<>();
        Set<Long> kept = new HashSet<>();
        for (int i = 0; i + 3 < fresh.length; i += 4) {
            long k = key(fresh[i], fresh[i + 1], fresh[i + 2]);
            Integer prev = before.get(k);
            kept.add(k);
            if (all || prev == null || prev != fresh[i + 3])
                collect(sections, fresh[i], fresh[i + 1], fresh[i + 2], fresh[i + 3]);
        }
        if (old != null)
            for (int i = 0; i + 3 < old.length; i += 4)
                if (!kept.contains(key(old[i], old[i + 1], old[i + 2])))
                    collect(sections, old[i], old[i + 1], old[i + 2], old[i + 3]);

        if (sections.isEmpty())
            return;

        INTERNAL.set(Boolean.TRUE);
        try {
            for (long id : sections) {
                WorldSection section = engine.acquireIfExists(WorldEngine.getLevel(id), WorldEngine.getX(id), WorldEngine.getY(id), WorldEngine.getZ(id));
                if (section == null)
                    continue;
                section.release();
                if (!enqueue(svc, id))
                    return;
            }
        } finally {
            INTERNAL.remove();
        }
    }

    private static void collect(Set<Long> out, int x, int y, int z, int rgb) {
        int ext = LodSplit.extentOf(rgb);
        if (ext <= 0)
            return;
        for (int lvl = 0; lvl <= LodSplit.MAX_LOD; lvl++) {
            int span = 32 << lvl;
            int sx0 = Math.floorDiv(x - ext, span), sx1 = Math.floorDiv(x + ext, span);
            int sy0 = Math.floorDiv(y - ext, span), sy1 = Math.floorDiv(y + ext, span);
            int sz0 = Math.floorDiv(z - ext, span), sz1 = Math.floorDiv(z + ext, span);
            for (int sx = sx0; sx <= sx1; sx++)
                for (int sy = sy0; sy <= sy1; sy++)
                    for (int sz = sz0; sz <= sz1; sz++)
                        out.add(WorldEngine.getWorldSectionId(lvl, sx, sy, sz));
        }
    }
}
