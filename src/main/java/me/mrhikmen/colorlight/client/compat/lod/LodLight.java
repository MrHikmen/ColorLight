package me.mrhikmen.colorlight.client.compat.lod;

import me.mrhikmen.colorlight.client.ColorLightClient;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngine;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngineHolder;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Owner of the current dimension's {@link LodLightMap}: creates it when a world is joined, keeps it fed from the
 * engine, and saves it ({@code <game dir>/colorlight/lodlight/}) so far-away light survives restarts.
 */
public final class LodLight {

    private static final long TICK_BUDGET_NANOS = 1_500_000L;
    private static final int RESCAN_DELAY_TICKS = 40;
    private static final int SAVE_INTERVAL_TICKS = 6000;

    private static LodLightMap map;
    private static String mapKey;
    private static ColorLightEngine mapEngine;
    private static Path mapFile;
    private static int rescanTimer;
    private static int saveTimer;

    /** The map of the dimension being played, or null outside a world. */
    public static LodLightMap map() {
        return map;
    }

    /** Sections the engine reported as changed. */
    public static void markDirty(long[] sectionKeys) {
        LodLightMap m = map;
        if (m != null)
            m.markDirty(sectionKeys);
    }

    public static void tick(Minecraft client) {
        ClientLevel level = client.level;
        ColorLightEngine engine = ColorLightEngineHolder.get();

        if (level == null || engine == null) {
            if (map != null) {
                save();
                map = null;
                mapKey = null;
                mapEngine = null;
            }
            return;
        }

        String key = dimensionKey(level);
        if (map == null || !key.equals(mapKey)) {
            save();
            map = new LodLightMap();
            mapKey = key;
            mapEngine = null;
            mapFile = fileFor(client, key);
            load();
            saveTimer = 0;
        }

        if (engine != mapEngine) {
            // new engine (join, dimension change): it knows nothing yet, so look at everything that lights up later
            mapEngine = engine;
            rescanTimer = RESCAN_DELAY_TICKS;
        }
        if (rescanTimer > 0 && --rescanTimer == 0) {
            LodLightMap m = map;
            engine.forEachLitSection(m::markDirty);
        }

        map.tick(engine, level, TICK_BUDGET_NANOS);

        if (++saveTimer >= SAVE_INTERVAL_TICKS) {
            saveTimer = 0;
            if (map.hasUnsavedChanges())
                save();
        }
    }

    public static void shutdown() {
        save();
    }

    // ------------------------------------------------------------------------------------------------------

    private static String dimensionKey(ClientLevel level) {
        return level.dimension().toString();
    }

    private static Path fileFor(Minecraft client, String dimension) {
        String world = worldKey(client);
        if (world == null)
            return null; // unknown world: still works for this session, just not remembered
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

    private static void load() {
        if (mapFile == null || !Files.isRegularFile(mapFile))
            return;
        try {
            map.load(mapFile);
            ColorLightClient.LOGGER.info("[ColorLight] Loaded far light for {} ({} columns)", mapKey, map.columnCount());
        } catch (Throwable t) {
            ColorLightClient.LOGGER.warn("[ColorLight] Could not read far light file {}: {}", mapFile, t.toString());
        }
    }

    private static void save() {
        if (map == null || mapFile == null || !map.hasUnsavedChanges())
            return;
        try {
            map.save(mapFile);
        } catch (Throwable t) {
            ColorLightClient.LOGGER.warn("[ColorLight] Could not save far light file {}: {}", mapFile, t.toString());
        }
    }

    private LodLight() {
    }
}
