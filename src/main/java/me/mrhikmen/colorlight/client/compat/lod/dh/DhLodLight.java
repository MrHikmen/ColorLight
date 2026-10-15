package me.mrhikmen.colorlight.client.compat.lod.dh;

import me.mrhikmen.colorlight.client.compat.lod.LodImport;
import me.mrhikmen.colorlight.client.compat.lod.LodLightHub;
import me.mrhikmen.colorlight.client.compat.lod.LodLightStore;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Owner of the Distant Horizons scanner ({@link DhLightCache}) for the current dimension. The light sources, their
 * save file and the GPU tables live in the {@link LodLightStore} that {@link LodLightHub} keeps for the dimension and
 * shares with the other LOD mods; this class only creates the DH scanner on top of it and ticks it.
 * <p>
 * Deliberately has no import of any Distant Horizons class: {@link me.mrhikmen.colorlight.client.ColorLightClient}
 * ticks this unconditionally (DH or not), so everything DH-specific stays behind
 * {@link ColorLightDistantHorizonsCompat} and inside {@link DhLightCache}.
 */
public final class DhLodLight {

    private static DhLightCache cache;
    private static LodLightStore cacheStore;

    /** The DH scanner for the dimension being played, or {@code null} outside a world or without Distant Horizons. */
    public static DhLightCache cache() {
        return cache;
    }

    /** What the shared "import light for LODs" screen uses; no DH class is involved. */
    public static final LodImport IMPORT = new LodImport() {
        @Override
        public boolean start() {
            DhLightCache c = cache;
            var player = Minecraft.getInstance().player;
            if (c == null || player == null)
                return false;
            c.forceRescan(player.getBlockX(), player.getBlockZ());
            return true;
        }

        @Override
        public void cancel() {
            DhLightCache c = cache;
            if (c != null)
                c.cancelScan();
        }

        @Override
        public boolean running() {
            DhLightCache c = cache;
            return c != null && c.isScanning();
        }

        @Override
        public int done() {
            DhLightCache c = cache;
            return c == null ? 0 : c.scanDone();
        }

        @Override
        public int total() {
            DhLightCache c = cache;
            return c == null ? 0 : c.scanTotal();
        }

        @Override
        public boolean everDone() {
            DhLightCache c = cache;
            return c != null && c.hasScannedOnce();
        }

        @Override
        public int sources() {
            LodLightStore s = cacheStore;
            return s == null ? 0 : s.sourceCount();
        }
    };

    public static void tick(Minecraft client) {
        if (!ColorLightDistantHorizonsCompat.isPresent()) {
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
            cache = new DhLightCache(store);
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

    private DhLodLight() {
    }
}
