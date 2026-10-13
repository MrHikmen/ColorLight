package me.mrhikmen.colorlight.client.compat.distanthorizons;

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
 * Owner of the current dimension's {@link DhLightCache}: creates it when a world is joined, loads what was saved
 * for that world ({@code <game dir>/colorlight/dhlight/}), ticks it, and saves it again every half minute (when
 * something changed), when the world or dimension is left, and when the game closes.
 * <p>
 * Deliberately has no import of any Distant Horizons class: {@link ColorLightClient} ticks this unconditionally
 * (Distant Horizons or not), so everything DH-specific stays behind {@link ColorLightDistantHorizonsCompat} and
 * inside {@link DhLightCache}, which is only ever touched from the branch below.
 */
public final class DhLodLight {

    private static DhLightCache cache;
    private static String cacheKey;
    private static Path cacheFile;
    private static long lastSaveMs;
    private static final long SAVE_INTERVAL_MS = 30_000L;

    /** The far-light cache for the dimension being played, or {@code null} outside a world or without Distant Horizons. */
    public static DhLightCache cache() {
        return cache;
    }

    /** Starts a sweep around the player right now, even if one is already running. False outside a world. */
    public static boolean startScan() {
        DhLightCache c = cache;
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (c == null || player == null)
            return false;
        c.forceRescan(player.getBlockX(), player.getBlockZ());
        return true;
    }

    public static boolean isScanning() {
        DhLightCache c = cache;
        return c != null && c.isScanning();
    }

    public static boolean hasScannedOnce() {
        DhLightCache c = cache;
        return c != null && c.hasScannedOnce();
    }

    public static int scanDone() {
        DhLightCache c = cache;
        return c == null ? 0 : c.scanDone();
    }

    public static int scanTotal() {
        DhLightCache c = cache;
        return c == null ? 0 : c.scanTotal();
    }

    public static int sourcesFound() {
        DhLightCache c = cache;
        return c == null ? 0 : c.sourcesFound();
    }

    public static void tick(Minecraft client) {
        if (!ColorLightDistantHorizonsCompat.isPresent()) {
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
            cache = new DhLightCache();
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
        return FabricLoader.getInstance().getGameDir().resolve("colorlight").resolve("dhlight").resolve(name + ".bin");
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

    private DhLodLight() {
    }
}
