package me.mrhikmen.colorlight.client.compat.voxy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Owner of the current dimension's {@link VoxyLightCache}: creates it when a world is joined and ticks it.
 * <p>
 * Far-away colour now always comes straight from Voxy's own, already-persistent voxel storage (see
 * {@link VoxyLightCache}), so unlike the old file-backed {@code LodLightMap} this keeps nothing of ColorLight's
 * own on disk any more - there is no {@code colorlight/lodlight/} save file, and nothing here needs to know
 * about chunks unloading: Voxy already remembers the blocks, ColorLight just re-reads them.
 * <p>
 * Deliberately has no import of any Voxy class: {@link me.mrhikmen.colorlight.client.ColorLightClient} ticks
 * this unconditionally (Voxy or not), so everything Voxy-specific stays behind {@link ColorLightVoxyCompat} and
 * inside {@link VoxyLightCache}, which is only ever touched from the branch below.
 */
public final class LodLight {

    private static VoxyLightCache cache;
    private static String cacheKey;

    /** The far-light cache for the dimension being played, or {@code null} outside a world or without Voxy. */
    public static VoxyLightCache cache() {
        return cache;
    }

    /**
     * Kept so {@link me.mrhikmen.colorlight.client.core.light.runtime.ColorLightDirtyFlusher} needs no Voxy
     * presence check of its own. A no-op: {@link VoxyLightCache} reads Voxy's own voxel storage directly
     * instead of being fed from the engine's dirty-section list, so there is nothing to invalidate here.
     */
    public static void markDirty(long[] sectionKeys) {
    }

    public static void tick(Minecraft client) {
        if (!ColorLightVoxyCompat.isPresent()) {
            discard();
            return;
        }

        ClientLevel level = client.level;
        if (level == null) {
            discard();
            return;
        }

        String key = level.dimension().toString();
        if (cache == null || !key.equals(cacheKey)) {
            discard();
            cache = new VoxyLightCache();
            cacheKey = key;
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
            cacheKey = null;
        }
    }

    private LodLight() {
    }
}
