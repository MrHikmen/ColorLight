package me.mrhikmen.colorlight.client.compat.voxy;

import me.mrhikmen.colorlight.client.ColorLightClient;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Owner of the current dimension's {@link VoxyLightCache}: creates it when a world is joined, loads what was saved
 * for that world ({@code <game dir>/colorlight/voxylight/}), ticks it, and saves it again every half minute (when
 * something changed), when the world or dimension is left, and when the game closes.
 * <p>
 * Deliberately has no import of any Voxy class: {@link me.mrhikmen.colorlight.client.ColorLightClient} ticks
 * this unconditionally (Voxy or not), so everything Voxy-specific stays behind {@link ColorLightVoxyCompat} and
 * inside {@link VoxyLightCache}, which is only ever touched from the branch below.
 */
public final class LodLight {

    private static VoxyLightCache cache;
    private static String cacheKey;
    private static Path cacheFile;
    private static long lastSaveMs;
    private static final long SAVE_INTERVAL_MS = 30_000L;

    /** The far-light cache for the dimension being played, or {@code null} outside a world or without Voxy. */
    public static VoxyLightCache cache() {
        return cache;
    }

    /**
     * Called by {@link me.mrhikmen.colorlight.client.core.light.runtime.ColorLightDirtyFlusher} with the engine's
     * changed sections; after an import, the matching Voxy columns are re-read in the background. Harmless
     * without Voxy ({@code cache} is then always null).
     */
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

    /** Forgets all light sources and reads them from Voxy again. */
    public static boolean startImport() {
        VoxyLightCache c = cache;
        if (c == null)
            return false;
        c.startImport();
        return true;
    }

    public static void cancelImport() {
        VoxyLightCache c = cache;
        if (c != null)
            c.cancelImport();
    }

    public static boolean isImporting() {
        VoxyLightCache c = cache;
        return c != null && c.isImporting();
    }

    public static boolean hasImported() {
        VoxyLightCache c = cache;
        return c != null && c.hasImported();
    }

    public static int importDone() {
        VoxyLightCache c = cache;
        return c == null ? 0 : c.importDone();
    }

    public static int importTotal() {
        VoxyLightCache c = cache;
        return c == null ? 0 : c.importTotal();
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
            cacheFile = fileFor(client, key);
            lastSaveMs = System.currentTimeMillis();
            if (cacheFile != null && Files.isRegularFile(cacheFile))
                cache.loadAsync(cacheFile);
        }

        cache.tick(level);

        long now = System.currentTimeMillis();
        if (cacheFile != null && now - lastSaveMs >= SAVE_INTERVAL_MS) {
            lastSaveMs = now;
            if (cache.hasUnsavedChanges())
                cache.saveAsync(cacheFile);
        }
    }

    public static void shutdown() {
        discard();
    }

    private static void discard() {
        if (cache != null) {
            if (cacheFile != null && cache.hasUnsavedChanges()) {
                try {
                    cache.save(cacheFile);
                } catch (Throwable t) {
                    ColorLightClient.LOGGER.warn("[ColorLight] Could not save far light file {}: {}", cacheFile, t.toString());
                }
            }
            cache.shutdown();
            cache = null;
            cacheKey = null;
            cacheFile = null;
        }
    }

    private static Path fileFor(Minecraft client, String dimension) {
        String world = worldKey(client);
        if (world == null)
            return null; // unknown world: works for this session, just not remembered
        String name = UUID.nameUUIDFromBytes((world + "|" + dimension).getBytes(StandardCharsets.UTF_8)).toString();
        return FabricLoader.getInstance().getGameDir().resolve("colorlight").resolve("voxylight").resolve(name + ".bin");
    }

    private static String worldKey(Minecraft client) {
        try {
            var server = client.getSingleplayerServer();
            if (server != null)
                return "sp:" + server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            var current = client.getCurrentServer();
            if (current != null)
                return "mp:" + current.ip;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private LodLight() {
    }
}
