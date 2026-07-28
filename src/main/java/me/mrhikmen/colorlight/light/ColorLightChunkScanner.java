package me.mrhikmen.colorlight.light;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

public final class ColorLightChunkScanner {

    public static void register() {
        ClientChunkEvents.CHUNK_LOAD.register(ColorLightChunkScanner::onChunkLoad);
    }

    private static void onChunkLoad(ClientLevel level, LevelChunk chunk) {

        if (ColorLightBlockRegistry.size() == 0)
            return;

        ColorLightEngine engine = ColorLightEngineHolder.get();
        if (engine == null)
            return;

        int chunkBaseX = chunk.getPos().getMinBlockX();
        int chunkBaseZ = chunk.getPos().getMinBlockZ();

        LevelChunkSection[] sections = chunk.getSections();
        int minSectionIndex = chunk.getMinSection();

        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {

            LevelChunkSection section = sections[sectionIndex];
            if (section == null || section.hasOnlyAir())
                continue;

            int sectionBaseY = (minSectionIndex + sectionIndex) << 4;

            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {

                        BlockState state = section.getBlockState(x, y, z);
                        Block block = state.getBlock();

                        int[] colorData = ColorLightBlockRegistry.get(block);
                        if (colorData == null)
                            continue;

                        BlockPos pos = new BlockPos(chunkBaseX + x, sectionBaseY + y, chunkBaseZ + z);

                        if (!engine.hasSource(pos)) {
                            engine.addSource(pos, colorData[0], colorData[1], colorData[2], colorData[3]);
                        }
                    }
                }
            }
        }
    }

    private ColorLightChunkScanner() {
    }
}
