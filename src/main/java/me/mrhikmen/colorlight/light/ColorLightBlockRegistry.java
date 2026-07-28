package me.mrhikmen.colorlight.light;

import me.mrhikmen.colorlight.config.ColorLightConfig;
import me.mrhikmen.colorlight.config.ColorLightSaveBlock;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.HashMap;
import java.util.Map;

public final class ColorLightBlockRegistry {

    private static final Map<Block, int[]> COLORS = new HashMap<>();

    public static void load(ColorLightConfig config) {
        COLORS.clear();

        if (config.blocks == null)
            return;

        for (ColorLightSaveBlock saved : config.blocks) {
            if (saved == null || saved.block == null)
                continue;

            Block block;
            try {
                block = BuiltInRegistries.BLOCK.get(saved.getBlock());
            } catch (Exception e) {
                continue;
            }

            if (block == null || block == Blocks.AIR)
                continue;

            COLORS.put(block, new int[]{saved.r, saved.g, saved.b, Math.max(1, saved.light)});
        }
    }

    public static int[] get(Block block) {
        return COLORS.get(block);
    }

    public static int size() {
        return COLORS.size();
    }

    private ColorLightBlockRegistry() {
    }
}