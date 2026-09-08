package me.mrhikmen.colorlight.config.gui.sodium;

import me.mrhikmen.colorlight.ColorLightClient;
import me.mrhikmen.colorlight.compat.lambdynlights.ColorLightLambDynLightsCompat;
import me.mrhikmen.colorlight.compat.lod.voxy.ColorLightVoxyCompat;
import me.mrhikmen.colorlight.config.BlockSettings;
import me.mrhikmen.colorlight.config.ColorLightConfig;
import me.mrhikmen.colorlight.config.Translatable;
import me.mrhikmen.colorlight.config.gui.screen.ColorLightBlockConfigScreen;
import me.mrhikmen.colorlight.core.light.ColorLightBlockRegistry;
import me.mrhikmen.colorlight.core.light.ColorLightChunkScanner;
import me.mrhikmen.colorlight.core.light.ColorLightEngineHolder;
import me.mrhikmen.colorlight.core.util.ColorLightRenderUtil;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPointForge;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionPageBuilder;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

@ConfigEntryPointForge("colorlight")
public class ColorLightSodiumConfig implements ConfigEntryPoint {

    private final ColorLightConfig config = ColorLightClient.config;

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        config.load();
        boolean ldl = ColorLightLambDynLightsCompat.isPresent();
        boolean voxy = ColorLightVoxyCompat.isPresent();
        var modOptions = builder.registerOwnModOptions()
                .setNonTintedIcon(Identifier.parse("colorlight:icon.png"))
                .setColorTheme(builder.createColorTheme().setBaseThemeRGB(0x73efff));

        modOptions.addPage(this.GeneralPage(builder));
        modOptions.addPage(this.BlockPage(builder));
        if (ldl || voxy) {
            modOptions.addPage(this.CompatibilityPage(builder, ldl, voxy));
        }
    }
    private OptionPageBuilder GeneralPage(ConfigBuilder builder) {
        OptionPageBuilder page = builder.createOptionPage().setName(Translatable.GENERAL)
                .addOptionGroup(builder.createOptionGroup()
                        .addOption(builder.createBooleanOption(Identifier.parse("colorlight:enable"))
                                .setName(Translatable.ENABLE)
                                .setTooltip(Translatable.ENABLE_Tooltip)
                                .setStorageHandler(this::save)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                                .setBinding(value -> config.ENABLE = value, () -> config.ENABLE)
                                .setDefaultValue(config.ENABLE)
                        )
                        .addOption(builder.createIntegerOption(Identifier.parse("colorlight:light_range"))
                                .setName(Translatable.LIGHT_RANGE)
                                .setTooltip(Translatable.LIGHT_RANGE_Tooltip)
                                .setRange(2, 32, 1)
                                .setValueFormatter(value -> Translatable.BLOCKS_Value(value))
                                .setStorageHandler(this::save)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                                .setBinding(value -> config.lightRangeBlocks = value, () -> config.lightRangeBlocks)
                                .setDefaultValue(config.lightRangeBlocks)
                        )
                        .addOption(builder.createBooleanOption(Identifier.parse("colorlight:use_gpu_lighting"))
                                .setName(Translatable.USE_GPU_LIGHTING)
                                .setTooltip(Translatable.USE_GPU_LIGHTING_Tooltip)
                                .setStorageHandler(this::save)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                                .setBinding(value -> config.USE_GPU_LIGHTING = value, () -> config.USE_GPU_LIGHTING)
                                .setDefaultValue(config.USE_GPU_LIGHTING)  //GPU_BACKEND.isSupported()
                        )
                        .addOption(builder.createBooleanOption(Identifier.parse("colorlight:smooth_lighting"))
                                .setName(Translatable.SMOOTH_LIGHTING)
                                .setTooltip(Translatable.SMOOTH_LIGHTING_Tooltip)
                                .setStorageHandler(this::save)
                                .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                                .setBinding(value -> config.SMOOTH_LIGHTING = value, () -> config.SMOOTH_LIGHTING)
                                .setDefaultValue(config.SMOOTH_LIGHTING)
                        )
                )
                .addOptionGroup(builder.createOptionGroup()
                        .setName(Translatable.WEIGHT)
                        .addOption(builder.createIntegerOption(Identifier.parse("colorlight:brightness_weight"))
                                .setName(Translatable.BRIGHTNESS_WEIGHT)
                                .setTooltip(Translatable.WEIGHT_Tooltip)
                                .setRange(0, 100, 1)
                                .setValueFormatter(value -> Translatable.WEIGHT_Value(value))
                                .setStorageHandler(this::save)
                                .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                                .setBinding(value -> config.BRIGHTNESS_WEIGHT = value, () -> config.BRIGHTNESS_WEIGHT)
                                .setDefaultValue(config.BRIGHTNESS_WEIGHT)
                        )
                        .addOption(builder.createIntegerOption(Identifier.parse("colorlight:local_weight"))
                                .setName(Translatable.LOCAL_WEIGHT)
                                .setTooltip(Translatable.WEIGHT_Tooltip)
                                .setRange(0, 100, 1)
                                .setValueFormatter(value -> Translatable.WEIGHT_Value(value))
                                .setStorageHandler(this::save)
                                .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                                .setBinding(value -> config.LOCAL_WEIGHT = value, () -> config.LOCAL_WEIGHT)
                                .setDefaultValue(config.LOCAL_WEIGHT)
                        )
                        .addOption(builder.createIntegerOption(Identifier.parse("colorlight:region_weight"))
                                .setName(Translatable.REGION_WEIGHT)
                                .setTooltip(Translatable.WEIGHT_Tooltip)
                                .setRange(0, 100, 1)
                                .setValueFormatter(value -> Translatable.WEIGHT_Value(value))
                                .setStorageHandler(this::save)
                                .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                                .setBinding(value -> config.REGION_WEIGHT = value, () -> config.REGION_WEIGHT)
                                .setDefaultValue(config.REGION_WEIGHT)
                        )
                        .addOption(builder.createIntegerOption(Identifier.parse("colorlight:alpha_weight"))
                                .setName(Translatable.ALPHA_WEIGHT)
                                .setTooltip(Translatable.WEIGHT_Tooltip)
                                .setRange(0, 100, 1)
                                .setValueFormatter(value -> Translatable.WEIGHT_Value(value))
                                .setStorageHandler(this::save)
                                .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                                .setBinding(value -> config.ALPHA_WEIGHT = value, () -> config.ALPHA_WEIGHT)
                                .setDefaultValue(config.ALPHA_WEIGHT)
                        )
                        .addOption(builder.createIntegerOption(Identifier.parse("colorlight:anomaly_weight"))
                                .setName(Translatable.ANOMALY_WEIGHT)
                                .setTooltip(Translatable.WEIGHT_Tooltip)
                                .setRange(0, 100, 1)
                                .setValueFormatter(value -> Translatable.WEIGHT_Value(value))
                                .setStorageHandler(this::save)
                                .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                                .setBinding(value -> config.ANOMALY_WEIGHT = value, () -> config.ANOMALY_WEIGHT)
                                .setDefaultValue(config.ANOMALY_WEIGHT)
                        )
                        .addOption(builder.createIntegerOption(Identifier.parse("colorlight:saturation_weight"))
                                .setName(Translatable.SATURATION_WEIGHT)
                                .setTooltip(Translatable.WEIGHT_Tooltip)
                                .setRange(0, 100, 1)
                                .setValueFormatter(value -> Translatable.WEIGHT_Value(value))
                                .setStorageHandler(this::save)
                                .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                                .setBinding(value -> config.SATURATION_WEIGHT = value, () -> config.SATURATION_WEIGHT)
                                .setDefaultValue(config.SATURATION_WEIGHT)
                        )
                        .addOption(builder.createIntegerOption(Identifier.parse("colorlight:glowcolorscore_weight"))
                                .setName(Translatable.GLOWCOLORSCORE_WEIGHT)
                                .setTooltip(Translatable.WEIGHT_Tooltip)
                                .setRange(0, 100, 1)
                                .setValueFormatter(value -> Translatable.WEIGHT_Value(value))
                                .setStorageHandler(this::save)
                                .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                                .setBinding(value -> config.GLOWCOLORSCORE_WEIGHT = value, () -> config.GLOWCOLORSCORE_WEIGHT)
                                .setDefaultValue(config.GLOWCOLORSCORE_WEIGHT)
                        )
                        .addOption(builder.createIntegerOption(Identifier.parse("colorlight:whitepenalty_weight"))
                                .setName(Translatable.WHITEPENALTY_WEIGHT)
                                .setTooltip(Translatable.WEIGHT_Tooltip)
                                .setRange(0, 100, 1)
                                .setValueFormatter(value -> Translatable.WEIGHT_Value(value))
                                .setStorageHandler(this::save)
                                .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                                .setBinding(value -> config.WHITEPENALTY_WEIGHT = value, () -> config.WHITEPENALTY_WEIGHT)
                                .setDefaultValue(config.WHITEPENALTY_WEIGHT)
                        )
                );
        return page;
    }
    private OptionPageBuilder BlockPage(ConfigBuilder builder) {
        OptionPageBuilder page = builder.createOptionPage().setName(Translatable.BLOCK);
        for (BlockSettings entry : ColorLightClient.config.blocks) {
            Identifier block = entry.getBlock();
            page.addOptionGroup(
                    builder.createOptionGroup()
                            .addOption(builder.createExternalButtonOption(Identifier.parse("colorlight:block_" + block.getPath()))
                                    .setName(Component.translatable("block." + block.toLanguageKey()))
                                    .setTooltip(Translatable.BLOCK_Tooltip)
                                    .setScreenConsumer(parentScreen -> Minecraft.getInstance().setScreenAndShow(new ColorLightBlockConfigScreen(parentScreen, entry, this::save, this::applyImmediately)))
                            )
            );
        }
        return page;
    }
    private OptionPageBuilder CompatibilityPage(ConfigBuilder builder, boolean ldl, boolean voxy) {
        Identifier entityEnabledId = Identifier.parse("colorlight:entity_tracking_enabled");
        Identifier entityFollowId = Identifier.parse("colorlight:entity_follow_render_distance");
        Identifier voxyEnabledId = Identifier.parse("colorlight:voxy_compat_enabled");
        Identifier voxyFollowId = Identifier.parse("colorlight:voxy_follow_lod_distance");
        ColorLightClient.LOGGER.info("[ColorLight] Mods supported by ColorLight: ldl - {}, voxy - {}.", ldl, voxy);
        OptionPageBuilder page = builder.createOptionPage().setName(Translatable.COMPATIBILITY);
        if (ldl) {
            page.addOptionGroup(builder.createOptionGroup()
                    .setName(Translatable.ENTITY_TRACKING)
                    .addOption(builder.createBooleanOption(entityEnabledId)
                            .setName(Translatable.ENTITY_TRACKING_ENABLED)
                            .setTooltip(Translatable.ENTITY_TRACKING_ENABLED_Tooltip)
                            .setStorageHandler(this::save)
                            .setBinding(value -> config.ENTITY_TRACKING_ENABLED = value, () -> config.ENTITY_TRACKING_ENABLED)
                            .setDefaultValue(config.ENTITY_TRACKING_ENABLED)
                    )
                    .addOption(builder.createBooleanOption(entityFollowId)
                            .setName(Translatable.ENTITY_FOLLOW_RENDER_DISTANCE)
                            .setTooltip(Translatable.ENTITY_FOLLOW_RENDER_DISTANCE_Tooltip)
                            .setEnabledProvider(state -> state.readBooleanOption(entityEnabledId), entityEnabledId)
                            .setStorageHandler(this::save)
                            .setBinding(value -> config.ENTITY_CHECK_FOLLOW_RENDER_DISTANCE = value, () -> config.ENTITY_CHECK_FOLLOW_RENDER_DISTANCE)
                            .setDefaultValue(config.ENTITY_CHECK_FOLLOW_RENDER_DISTANCE)
                    )
                    .addOption(builder.createIntegerOption(Identifier.parse("colorlight:entity_check_radius_chunks"))
                            .setName(Translatable.ENTITY_CHECK_RADIUS_CHUNKS)
                            .setTooltip(Translatable.ENTITY_CHECK_RADIUS_CHUNKS_Tooltip)
                            .setEnabledProvider(state -> state.readBooleanOption(entityEnabledId) && !state.readBooleanOption(entityFollowId), entityEnabledId, entityFollowId)
                            .setRange(2, 32, 1)
                            .setValueFormatter(value -> Translatable.CHUNKS_Value(value))
                            .setStorageHandler(this::save)
                            .setBinding(value -> config.ENTITY_CHECK_RADIUS_CHUNKS = value, () -> config.ENTITY_CHECK_RADIUS_CHUNKS)
                            .setDefaultValue(config.ENTITY_CHECK_RADIUS_CHUNKS)
                    )
            );
        }
//        if (voxy) {
//            page.addOptionGroup(builder.createOptionGroup()
//                    .setName(Translatable.VOXY)
//                    .addOption(builder.createBooleanOption(voxyEnabledId)
//                            .setName(Translatable.VOXY_COMPAT_ENABLED)
//                            .setTooltip(Translatable.VOXY_COMPAT_ENABLED_Tooltip)
//                            .setStorageHandler(this::save)
//                            .setBinding(value -> config.VOXY_COMPAT_ENABLED = value, () -> config.VOXY_COMPAT_ENABLED)
//                            .setDefaultValue(config.VOXY_COMPAT_ENABLED)
//                    )
//                    .addOption(builder.createBooleanOption(voxyFollowId)
//                            .setName(Translatable.VOXY_FOLLOW_LOD_DISTANCE)
//                            .setTooltip(Translatable.VOXY_FOLLOW_LOD_DISTANCE_Tooltip)
//                            .setEnabledProvider(state -> state.readBooleanOption(voxyEnabledId), voxyEnabledId)
//                            .setStorageHandler(this::save)
//                            .setBinding(value -> config.VOXY_FOLLOW_LOD_RENDER_DISTANCE = value, () -> config.VOXY_FOLLOW_LOD_RENDER_DISTANCE)
//                            .setDefaultValue(config.VOXY_FOLLOW_LOD_RENDER_DISTANCE)
//                    )
//                    .addOption(builder.createIntegerOption(Identifier.parse("colorlight:voxy_light_range"))
//                            .setName(Translatable.VOXY_LIGHT_RANGE)
//                            .setTooltip(Translatable.VOXY_LIGHT_RANGE_Tooltip)
//                            .setEnabledProvider(state -> state.readBooleanOption(voxyEnabledId) && !state.readBooleanOption(voxyFollowId), voxyEnabledId, voxyFollowId)
//                            .setRange(16, 512, 16)
//                            .setValueFormatter(value -> Translatable.BLOCKS_Value(value))
//                            .setStorageHandler(this::save)
//                            .setBinding(value -> config.VOXY_LIGHT_RANGE_BLOCKS = value, () -> config.VOXY_LIGHT_RANGE_BLOCKS)
//                            .setDefaultValue(config.VOXY_LIGHT_RANGE_BLOCKS)
//                    )
//            );
//        }
        return page;
    }
    private static final long SAVE_DEBOUNCE_MS = 150L;
    private static final java.util.concurrent.ScheduledExecutorService SAVE_DEBOUNCER = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "ColorLight Config Save Debouncer");
                thread.setDaemon(true);
                return thread;
            });
    private java.util.concurrent.ScheduledFuture<?> pendingSave;
    private void save() {
        if (pendingSave != null) {
            pendingSave.cancel(false);
        }
        pendingSave = SAVE_DEBOUNCER.schedule(() -> Minecraft.getInstance().execute(this::applySaveNow), SAVE_DEBOUNCE_MS, java.util.concurrent.TimeUnit.MILLISECONDS
        );
    }
    private void applyImmediately() {
        if (pendingSave != null) {
            pendingSave.cancel(false);
            pendingSave = null;
        }
        this.applySaveNow();
    }
    private void applySaveNow() {
        config.save();
        ColorLightEngineHolder.configure(config.lightRangeBlocks, config.USE_GPU_LIGHTING);
        ColorLightBlockRegistry.load(config);
        var client = net.minecraft.client.Minecraft.getInstance();
        if (client.level != null) {
            var oldEngine = ColorLightEngineHolder.get();
            java.util.List<net.minecraft.core.BlockPos> oldSources = oldEngine != null ? oldEngine.getSourcePositions() : java.util.List.of();
            ColorLightEngineHolder.set(client.level);
            if (config.ENABLE) {
                ColorLightChunkScanner.markPositionsDirty(client.level, oldSources);
                ColorLightChunkScanner.rescanAll(client.level);
            } else {
                markWholeRenderDistanceDirty(client);
            }
        }
    }
    private static void markWholeRenderDistanceDirty(net.minecraft.client.Minecraft client) {
        var player = client.player;
        if (player == null || client.level == null) return;
        int renderDistanceBlocks = client.options.renderDistance().get() << 4;
        var pos = player.blockPosition();
        ColorLightRenderUtil.setBlocksDirtySafe(client.level,
                pos.getX() - renderDistanceBlocks, client.level.getMinY(), pos.getZ() - renderDistanceBlocks,
                pos.getX() + renderDistanceBlocks, client.level.getMaxY(), pos.getZ() + renderDistanceBlocks
        );
    }
}