package me.mrhikmen.colorlight.client.compat.lod.voxy;

import me.mrhikmen.colorlight.client.compat.lod.LodImport;
import me.mrhikmen.colorlight.client.compat.lod.LodLightHub;
import me.mrhikmen.colorlight.client.compat.lod.LodLightStore;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Owner of the Voxy scanner ({@link VoxyLightCache}) for the current dimension. The light sources themselves, their
 * save file and the GPU tables live in the {@link LodLightStore} that {@link LodLightHub} keeps for the dimension and
 * shares with the other LOD mods; this class only creates the Voxy scanner on top of it and ticks it.
 * <p>
 * Deliberately has no import of any Voxy class: {@link me.mrhikmen.colorlight.client.ColorLightClient} ticks this
 * unconditionally (Voxy or not), so everything Voxy-specific stays behind {@link ColorLightVoxyCompat} and inside
 * {@link VoxyLightCache}, which is only ever touched from the branch below.
 */
public final class LodLight {

    private static VoxyLightCache cache;
    private static LodLightStore cacheStore;

    /** The Voxy scanner for the dimension being played, or {@code null} outside a world or without Voxy. */
    public static VoxyLightCache cache() {
        return cache;
    }

    /** Hands the screen "import light for LODs" what it needs; no Voxy class is involved. */
    public static final LodImport IMPORT = new LodImport() {
        @Override
        public boolean start() {
            VoxyLightCache c = cache;
            if (c == null)
                return false;
            c.startImport();
            return true;
        }

        @Override
        public void cancel() {
            VoxyLightCache c = cache;
            if (c != null)
                c.cancelImport();
        }

        @Override
        public boolean running() {
            VoxyLightCache c = cache;
            return c != null && c.isImporting();
        }

        @Override
        public int done() {
            VoxyLightCache c = cache;
            return c == null ? 0 : c.importDone();
        }

        @Override
        public int total() {
            VoxyLightCache c = cache;
            return c == null ? 0 : c.importTotal();
        }

        @Override
        public boolean everDone() {
            VoxyLightCache c = cache;
            return c != null && c.hasImported();
        }

        @Override
        public int sources() {
            LodLightStore s = cacheStore;
            return s == null ? 0 : s.sourceCount();
        }
    };

    /** Called by the dirty flusher with the engine's changed sections; the matching Voxy columns are re-read later. */
    public static void markDirty(long[] sectionKeys) {
        VoxyLightCache c = cache;
        if (c != null)
            c.markDirty(sectionKeys);
    }

    /** Called (from any thread) when Voxy queues a section for meshing: new or changed terrain, so possibly new lights. */
    public static void onVoxySection(long pos) {
        VoxyLightCache c = cache;
        if (c != null)
            c.onVoxySection(pos);
    }

    public static void tick(Minecraft client) {
        if (!ColorLightVoxyCompat.isPresent()) {
            discard();
            return;
        }

        ClientLevel level = client.level;
        LodLightStore store = LodLightHub.store();
        if (level == null || store == null) {
            discard();
            return;
        }

        if (cache == null || store != cacheStore) {
            discard();
            cacheStore = store;
            cache = new VoxyLightCache(store);
        }

        cache.tick(level);
    }

    public static void shutdown() {
        discard();
    }

    private static void discard() {
        if (cache != null) {
            cache.shutdown();
            cache = null;
        }
        cacheStore = null;
    }

    private LodLight() {
    }
}
