package me.mrhikmen.colorlight.light;

import me.mrhikmen.colorlight.ColorLightClient;
import me.mrhikmen.colorlight.config.BlockSettings;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ColorLightChunkScanner {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ColorLight Chunk Scanner");
        thread.setDaemon(true);
        return thread;
    });

    public static void register() {
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
            if (!ColorLightClient.config.ENABLE)
                return;
            if (ColorLightBlockRegistry.isEmpty())
                return;

            EXECUTOR.submit(() -> scanChunk(level, chunk));
        });
    }

    public static void rescanAll(ClientLevel level) {
        if (level == null)
            return;

        var player = Minecraft.getInstance().player;
        if (player == null)
            return;

        int renderDistance = Minecraft.getInstance().options.renderDistance().get();
        ChunkPos center = player.chunkPosition();

        for (int dx = -renderDistance; dx <= renderDistance; dx++) {
            for (int dz = -renderDistance; dz <= renderDistance; dz++) {

                int x = center.x + dx;
                int z = center.z + dz;

                if (level.hasChunk(x, z)) {
                    LevelChunk chunk = level.getChunk(x, z);
                    EXECUTOR.submit(() -> scanChunk(level, chunk));
                }
            }
        }
    }

    private static void scanChunk(ClientLevel level, LevelChunk chunk) {

        if (ColorLightBlockRegistry.isEmpty())
            return;

        ColorLightEngine engine = ColorLightEngineHolder.get();
        if (engine == null)
            return;

        LevelChunkSection[] sections = chunk.getSections();
        int minSectionY = level.getMinSection();

        int chunkBlockX = chunk.getPos().x << 4;
        int chunkBlockZ = chunk.getPos().z << 4;

        boolean foundAny = false;

        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {

            LevelChunkSection section = sections[sectionIndex];
            if (section == null || section.hasOnlyAir())
                continue; // пустая секция — нечего сканировать

            int sectionBlockY = (minSectionY + sectionIndex) << 4;

            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {

                        BlockState state = section.getBlockState(x, y, z);

                        BlockSettings entry = ColorLightBlockRegistry.get(state.getBlock());
                        if (entry == null)
                            continue;

                        BlockPos pos = new BlockPos(chunkBlockX + x, sectionBlockY + y, chunkBlockZ + z);

                        if (!engine.hasSource(pos)) {
                            engine.addSource(pos, entry.r, entry.g, entry.b, entry.light);
                            foundAny = true;
                        }
                    }
                }
            }
        }

        if (foundAny) {
            markChunkDirtyOnMainThread(level, chunk, engine.getMaxRangeBlocks());
        }
    }

    private static void markChunkDirtyOnMainThread(ClientLevel level, LevelChunk chunk, int rangeBlocks) {
        Minecraft.getInstance().execute(() -> {

            int radius = rangeBlocks + 1;
            int chunkBlockX = chunk.getPos().x << 4;
            int chunkBlockZ = chunk.getPos().z << 4;

            Minecraft.getInstance().levelRenderer.setBlocksDirty(
                    chunkBlockX - radius, level.getMinBuildHeight(), chunkBlockZ - radius,
                    chunkBlockX + 16 + radius, level.getMaxBuildHeight(), chunkBlockZ + 16 + radius
            );
        });
    }

    private ColorLightChunkScanner() {
    }
}