package me.mrhikmen.colorlight.config;

import net.minecraft.network.chat.Component;

public class Translatable {
    public static Component GENERAL = Component.translatable("me.colorlight.general");
    public static Component BLOCK = Component.translatable("me.colorlight.block");
    public static Component FUN = Component.translatable("me.colorlight.fun");

    public static Component ENABLE = Component.translatable("me.colorlight.general.enable");
    public static Component ENABLE_Tooltip = Component.translatable("me.colorlight.general.enable.tooltip");

    public static Component LIGHT_RANGE = Component.translatable("me.colorlight.general.light_range");
    public static Component LIGHT_RANGE_Tooltip = Component.translatable("me.colorlight.general.light_range.tooltip");
    public static Component LIGHT_RANGE_Value(int value) {
        if(value > 10 && value < 15) return Component.translatable("me.colorlight.general.light_range.value", value);
        if(value == 1) return Component.translatable("me.colorlight.general.light_range.value.1", value);
        if(value%10 > 1 && value%10 < 5) return Component.translatable("me.colorlight.general.light_range.value.1-5", value);
        return Component.translatable("me.colorlight.general.light_range.value", value);
    }

    public static Component WEIGHT = Component.translatable("me.colorlight.fun.weight");
    public static Component BRIGHTNESS_WEIGHT = Component.translatable("me.colorlight.fun.brightness_weight");
    public static Component LOCAL_WEIGHT = Component.translatable("me.colorlight.fun.local_weight");
    public static Component REGION_WEIGHT = Component.translatable("me.colorlight.fun.region_weight");
    public static Component ALPHA_WEIGHT = Component.translatable("me.colorlight.fun.alpha_weight");
    public static Component ANOMALY_WEIGHT = Component.translatable("me.colorlight.fun.anomaly_weight");
    public static Component SATURATION_WEIGHT = Component.translatable("me.colorlight.fun.saturation_weight");
    public static Component GLOWCOLORSCORE_WEIGHT = Component.translatable("me.colorlight.fun.glowcolorscore_weight");
    public static Component WHITEPENALTY_WEIGHT = Component.translatable("me.colorlight.fun.whitepenalty_weight");
    public static Component WEIGHT_Tooltip = Component.translatable("me.colorlight.fun.weight.tooltip");
    public static Component WEIGHT_Value(int value) {
        if(value == 0) return Component.translatable("me.colorlight.fun.weight.value.0", value);
        return Component.translatable("me.colorlight.fun.weight.value", value);
    }
}
