package me.mrhikmen.colorlight.config;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedList;

public class ColorLightConfig {
    public boolean ENABLE = true;

    public static LinkedList<String> mod_id = new LinkedList<>();
    public static LinkedList<String> block_id = new LinkedList<>();
    public static LinkedList<Integer> rgb = new LinkedList<>();

    public static LinkedList<BlockState> State = new LinkedList<>();
    public static LinkedList<ResourceLocation> blocklist = new LinkedList<>();
    public static LinkedList<Integer> light = new LinkedList<>();
    public static int i;
}
