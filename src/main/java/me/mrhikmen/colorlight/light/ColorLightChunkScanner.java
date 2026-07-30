package me.mrhikmen.colorlight.light;

import me.mrhikmen.colorlight.ColorLightClient;
import me.mrhikmen.colorlight.config.BlockSettings;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

public final class ColorLightChunkScanner {

    public static void register() {
        ClientChunkEvents.CHUNK_LOAD.register(ColorLightChunkScanner::onChunkLoad);
    }

    private static void onChunkLoad(ClientLevel level, LevelChunk chunk) {

        if (!ColorLightClient.config.ENABLE)
            return;

        if (ColorLightBlockRegistry.isEmpty())
            return;

        ColorLightEngine engine = ColorLightEngineHolder.get();
        if (engine == null)
            return;

        LevelChunkSection[] sections = chunk.getSections();
        int minSectionY = level.getMinSection();

        int chunkBlockX = chunk.getPos().x << 4;
        int chunkBlockZ = chunk.getPos().z << 4;

        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {

            LevelChunkSection section = sections[sectionIndex];
            if (section == null || section.hasOnlyAir())
                continue;

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
                        }
                    }
                }
            }
        }
    }
    private ColorLightChunkScanner() {
    }
}