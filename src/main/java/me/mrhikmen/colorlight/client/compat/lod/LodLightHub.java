package me.mrhikmen.colorlight.client.compat.lod;

import me.mrhikmen.colorlight.client.ColorLightClient;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Owner of the current dimension's {@link LodLightStore}, the same one for every LOD mod: creates it when a world
 * is joined, loads what was saved for that world ({@code <game dir>/colorlight/lodlight/<uuid>.bin}), saves it every
 * half minute (when something changed), when the world or dimension is left, and when the game closes.
 * <p>
 * Ticked before the mods' own owners ({@code LodLight}, {@code DhLodLight}), which pick the store up from here. Has no
 * LOD mod class in it.
 */
public final class LodLightHub {

    private static final long SAVE_INTERVAL_MS = 30_000L;

    private static LodLightStore store;
    private static String storeKey;
    private static Path storeFile;
    private static long lastSaveMs;

    /** The store of the dimension being played, or {@code null} outside a world or without any LOD mod. */
    public static LodLightStore store() {
        return store;
    }

    public static boolean anyModPresent() {
        FabricLoader l = FabricLoader.getInstance();
        return l.isModLoaded("voxy") || l.isModLoaded("distanthorizons");
    }

    public static void tick(Minecraft client) {
        ClientLevel level = client.level;
        if (level == null || !anyModPresent()) {
            discard();
            return;
        }

        String key = level.dimension().toString();
        if (store == null || !key.equals(storeKey)) {
            discard();
            store = new LodLightStore();
            storeKey = key;
            storeFile = fileFor(client, key);
            lastSaveMs = System.currentTimeMillis();
            store.loadAsync(storeFile);
        }

        long now = System.currentTimeMillis();
        if (storeFile != null && now - lastSaveMs >= SAVE_INTERVAL_MS) {
            lastSaveMs = now;
            if (store.hasUnsavedChanges())
                store.saveAsync(storeFile);
        }
    }

    public static void shutdown() {
        discard();
    }

    private static void discard() {
        if (store != null) {
            if (storeFile != null && store.hasUnsavedChanges()) {
                try {
                    store.save(storeFile);
                } catch (Throwable t) {
                    ColorLightClient.LOGGER.warn("[ColorLight] Could not save far light file {}: {}", storeFile, t.toString());
                }
            }
            store.shutdown();
            store = null;
            storeKey = null;
            storeFile = null;
        }
    }

    private static Path fileFor(Minecraft client, String dimension) {
        String world = worldKey(client);
        if (world == null)
            return null; // unknown world: works for this session, just not remembered
        String name = UUID.nameUUIDFromBytes((world + "|" + dimension).getBytes(StandardCharsets.UTF_8)).toString();
        return FabricLoader.getInstance().getGameDir().resolve("colorlight").resolve("lodlight").resolve(name + ".bin");
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

    private LodLightHub() {
    }
}
