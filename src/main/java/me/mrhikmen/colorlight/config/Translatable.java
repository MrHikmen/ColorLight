package me.mrhikmen.colorlight.config;

import net.minecraft.network.chat.Component;

public class Translatable {
    public static Component CLEAN_ALL = Component.translatable("me.colorlight.command.clean_all");
    public static Component ENGINE_OFF = Component.translatable("me.colorlight.command.engine_off");
    public static Component LOOK_AT_BLOCK = Component.translatable("me.colorlight.command.look_at_block");
    public static Component LIGHT_ADD = Component.translatable("me.colorlight.command.light_add");
    public static Component LIGHT_DEL = Component.translatable("me.colorlight.command.light_del");

    public static Component GENERAL = Component.translatable("me.colorlight.general");
    public static Component BLOCK = Component.translatable("me.colorlight.block");
    public static Component COMPATIBILITY = Component.translatable("me.colorlight.compatibility");

    public static Component ENABLE = Component.translatable("me.colorlight.general.enable");
    public static Component ENABLE_Tooltip = Component.translatable("me.colorlight.general.enable.tooltip");
    public static Component LIGHT_RANGE = Component.translatable("me.colorlight.general.light_range");
    public static Component LIGHT_RANGE_Tooltip = Component.translatable("me.colorlight.general.light_range.tooltip");
    public static Component USE_GPU_LIGHTING = Component.translatable("me.colorlight.general.use_gpu_lighting");
    public static Component USE_GPU_LIGHTING_Tooltip = Component.translatable("me.colorlight.general.use_gpu_lighting.tooltip");
    public static Component SMOOTH_LIGHTING = Component.translatable("me.colorlight.general.smooth_lighting");
    public static Component SMOOTH_LIGHTING_Tooltip = Component.translatable("me.colorlight.general.smooth_lighting.tooltip");

    public static Component WEIGHT = Component.translatable("me.colorlight.general.weight");
    public static Component BRIGHTNESS_WEIGHT = Component.translatable("me.colorlight.general.brightness_weight");
    public static Component LOCAL_WEIGHT = Component.translatable("me.colorlight.general.local_weight");
    public static Component REGION_WEIGHT = Component.translatable("me.colorlight.general.region_weight");
    public static Component ALPHA_WEIGHT = Component.translatable("me.colorlight.general.alpha_weight");
    public static Component ANOMALY_WEIGHT = Component.translatable("me.colorlight.general.anomaly_weight");
    public static Component SATURATION_WEIGHT = Component.translatable("me.colorlight.general.saturation_weight");
    public static Component GLOWCOLORSCORE_WEIGHT = Component.translatable("me.colorlight.general.glowcolorscore_weight");
    public static Component WHITEPENALTY_WEIGHT = Component.translatable("me.colorlight.general.whitepenalty_weight");
    public static Component WEIGHT_Tooltip = Component.translatable("me.colorlight.general.weight.tooltip");

    public static Component ENTITY_TRACKING = Component.translatable("me.colorlight.compatibility.entity_tracking");
    public static Component ENTITY_TRACKING_ENABLED = Component.translatable("me.colorlight.compatibility.entity_tracking_enabled");
    public static Component ENTITY_TRACKING_ENABLED_Tooltip = Component.translatable("me.colorlight.compatibility.entity_tracking_enabled.tooltip");
    public static Component ENTITY_FOLLOW_RENDER_DISTANCE = Component.translatable("me.colorlight.compatibility.entity_follow_render_distance");
    public static Component ENTITY_FOLLOW_RENDER_DISTANCE_Tooltip = Component.translatable("me.colorlight.compatibility.entity_follow_render_distance.tooltip");
    public static Component ENTITY_CHECK_RADIUS_CHUNKS = Component.translatable("me.colorlight.compatibility.entity_check_radius_chunks");
    public static Component ENTITY_CHECK_RADIUS_CHUNKS_Tooltip = Component.translatable("me.colorlight.compatibility.entity_check_radius_chunks.tooltip");

    public static Component VOXY = Component.translatable("me.colorlight.compatibility.voxy");
    public static Component VOXY_COMPAT_ENABLED = Component.translatable("me.colorlight.compatibility.voxy_compat_enabled");
    public static Component VOXY_COMPAT_ENABLED_Tooltip = Component.translatable("me.colorlight.compatibility.voxy_compat_enabled.tooltip");
    public static Component VOXY_FOLLOW_LOD_DISTANCE = Component.translatable("me.colorlight.compatibility.voxy_follow_lod_distance");
    public static Component VOXY_FOLLOW_LOD_DISTANCE_Tooltip = Component.translatable("me.colorlight.compatibility.voxy_follow_lod_distance.tooltip");
    public static Component VOXY_LIGHT_RANGE = Component.translatable("me.colorlight.compatibility.voxy_light_range");
    public static Component VOXY_LIGHT_RANGE_Tooltip = Component.translatable("me.colorlight.compatibility.voxy_light_range.tooltip");

    public static Component BLOCKS_Value(int value) {
        if (value == 1) return Component.translatable("me.colorlight.block_value.1", value);
        if (value % 10 > 1 && value % 10 < 5 && !(value % 100 >= 12 && value % 100 <= 14))
            return Component.translatable("me.colorlight.block_value.1-5", value);
        return Component.translatable("me.colorlight.block_value", value);
    }

    public static Component CHUNKS_Value(int value) {
        if (value == 1) return Component.translatable("me.colorlight.chunks_value.1", value);
        if (value % 10 > 1 && value % 10 < 5 && !(value % 100 >= 12 && value % 100 <= 14))
            return Component.translatable("me.colorlight.chunks_value.1-5", value);
        return Component.translatable("me.colorlight.chunks_value", value);
    }

    public static Component WEIGHT_Value(int value) {
        if(value == 0) return Component.translatable("me.colorlight.value.0", value);
        return Component.translatable("me.colorlight.value", value);
    }

    public static Component BLOCK_Tooltip = Component.translatable("me.colorlight.block.block.tooltip");
    public static Component COLORED_LIGHTING = Component.translatable("me.colorlight.block.colored_lighting");
    public static Component COLORED_LIGHTING_2 = Component.translatable("me.colorlight.block.colored_lighting_2");
    public static Component SHADE = Component.translatable("me.colorlight.block.shade");
    public static Component RESET_TO_DEFAULT = Component.translatable("me.colorlight.block.reset_to_default");
    public static Component APPLY = Component.translatable("me.colorlight.block.apply");
}
