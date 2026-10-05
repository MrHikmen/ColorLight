package me.mrhikmen.colorlight.client.config.gui.sodium;

import me.mrhikmen.colorlight.client.ColorLightClient;
import me.mrhikmen.colorlight.client.compat.lambdynlights.ColorLightLambDynLightsCompat;
import me.mrhikmen.colorlight.client.compat.lod.ColorLightVoxyCompat;
import me.mrhikmen.colorlight.client.config.BlockSettings;
import me.mrhikmen.colorlight.client.config.ColorLightConfig;
import me.mrhikmen.colorlight.client.config.Translatable;
import me.mrhikmen.colorlight.client.gpu.ColorLightGpu;
import me.mrhikmen.colorlight.client.config.gui.screen.ColorLightBlockConfigScreen;
import me.mrhikmen.colorlight.client.config.gui.screen.PropagationPickerScreen;
import me.mrhikmen.colorlight.client.core.light.registry.ColorLightBlockRegistry;
import me.mrhikmen.colorlight.client.core.light.scan.ColorLightChunkScanner;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngineHolder;
import me.mrhikmen.colorlight.api.propagation.PropagationMethodRegistry;
import me.mrhikmen.colorlight.client.ColorLightApply;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPointForge;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
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
                                .setBinding(value -> config.ENABLE = value, () -> config.ENABLE)
                                .setDefaultValue(config.ENABLE)
                        )
                )
                .addOptionGroup(builder.createOptionGroup()
                        .addOption(builder.createIntegerOption(Identifier.parse("colorlight:light_range"))
                                .setName(Translatable.LIGHT_RANGE)
                                .setTooltip(Translatable.LIGHT_RANGE_Tooltip)
                                .setRange(1, 32, 1)
                                .setValueFormatter(value -> Translatable.BLOCKS_Value(value))
                                .setStorageHandler(this::save)
                                .setImpact(OptionImpact.HIGH)
                                .setBinding(value -> config.lightRangeBlocks = value, () -> config.lightRangeBlocks)
                                .setDefaultValue(config.lightRangeBlocks)
                        )
                        .addOption(builder.createBooleanOption(Identifier.parse("colorlight:smooth_lighting"))
                                .setName(Translatable.SMOOTH_LIGHTING)
                                .setTooltip(Translatable.SMOOTH_LIGHTING_Tooltip)
                                .setStorageHandler(this::save)
                                .setImpact(OptionImpact.MEDIUM)
                                .setBinding(value -> config.SMOOTH_LIGHTING = value, () -> config.SMOOTH_LIGHTING)
                                .setDefaultValue(config.SMOOTH_LIGHTING)
                        )
                        .addOption(builder.createIntegerOption(Identifier.parse("colorlight:tint_gamma"))
                                .setName(Translatable.TINT_GAMMA)
                                .setTooltip(Translatable.TINT_GAMMA_Tooltip)
                                .setRange(0, 200, 5)
                                .setValueFormatter(value -> Translatable.WEIGHT_Value(value))
                                .setStorageHandler(this::save)
                                .setBinding(
                                        value -> config.TINT_GAMMA = value / 100f,
                                        () -> Math.round(config.TINT_GAMMA * 100f)
                                )
                                .setDefaultValue(Math.round(config.TINT_GAMMA * 100f))
                        )
                        .addOption(builder.createExternalButtonOption(Identifier.parse("colorlight:propagation_mode"))
                                .setName(Translatable.PROPAGATION_MODE)
                                .setTooltip(Translatable.PROPAGATION_MODE_Tooltip)
                                .setScreenConsumer(parentScreen -> Minecraft.getInstance().setScreenAndShow(new PropagationPickerScreen(
                                        parentScreen, Translatable.PROPAGATION_MODE,
                                        () -> config.PROPAGATION_MODE, value -> config.PROPAGATION_MODE = value,
                                        this::applyImmediately, method -> true, PropagationMethodRegistry.GRID)))
                        )
                )
                .addOptionGroup(builder.createOptionGroup()
                        .setName(Translatable.GPU_EXPERIMENTS)
                        .addOption(builder.createBooleanOption(Identifier.parse("colorlight:gpu_pipeline"))
                                .setName(Translatable.GPU_PIPELINE)
                                .setTooltip(Translatable.GPU_PIPELINE_Tooltip)
                                .setStorageHandler(this::save)
                                .setBinding(value -> config.GPU_PIPELINE = value, () -> config.GPU_PIPELINE)
                                .setDefaultValue(config.GPU_PIPELINE)
                        )
                        .addOption(builder.createIntegerOption(Identifier.parse("colorlight:gpu_light_sections"))
                                .setName(Translatable.GPU_LIGHT_SECTIONS)
                                .setTooltip(Translatable.GPU_LIGHT_SECTIONS_Tooltip)
                                .setRange(64, 4096, 64)
                                .setValueFormatter(value -> Translatable.SECTIONS_Value(value))
                                .setStorageHandler(this::save)
                                .setBinding(value -> config.GPU_LIGHT_SECTIONS = value, () -> config.GPU_LIGHT_SECTIONS)
                                .setDefaultValue(config.GPU_LIGHT_SECTIONS)
                        )
                )
                .addOptionGroup(builder.createOptionGroup()
                        .setName(Translatable.WEIGHT)
                        .addOption(builder.createIntegerOption(Identifier.parse("colorlight:brightness_weight"))
                                .setName(Translatable.BRIGHTNESS_WEIGHT)
                                .setTooltip(Translatable.BRIGHTNESS_WEIGHT_Tooltip)
                                .setRange(0, 100, 1)
                                .setValueFormatter(value -> Translatable.WEIGHT_Value(value))
                                .setStorageHandler(this::save)
                                .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                                .setBinding(value -> config.BRIGHTNESS_WEIGHT = value, () -> config.BRIGHTNESS_WEIGHT)
                                .setDefaultValue(config.BRIGHTNESS_WEIGHT)
                        )
                        .addOption(builder.createIntegerOption(Identifier.parse("colorlight:local_weight"))
                                .setName(Translatable.LOCAL_WEIGHT)
                                .setTooltip(Translatable.LOCAL_WEIGHT_Tooltip)
                                .setRange(0, 100, 1)
                                .setValueFormatter(value -> Translatable.WEIGHT_Value(value))
                                .setStorageHandler(this::save)
                                .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                                .setBinding(value -> config.LOCAL_WEIGHT = value, () -> config.LOCAL_WEIGHT)
                                .setDefaultValue(config.LOCAL_WEIGHT)
                        )
                        .addOption(builder.createIntegerOption(Identifier.parse("colorlight:region_weight"))
                                .setName(Translatable.REGION_WEIGHT)
                                .setTooltip(Translatable.REGION_WEIGHT_Tooltip)
                                .setRange(0, 100, 1)
                                .setValueFormatter(value -> Translatable.WEIGHT_Value(value))
                                .setStorageHandler(this::save)
                                .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                                .setBinding(value -> config.REGION_WEIGHT = value, () -> config.REGION_WEIGHT)
                                .setDefaultValue(config.REGION_WEIGHT)
                        )
                        .addOption(builder.createIntegerOption(Identifier.parse("colorlight:alpha_weight"))
                                .setName(Translatable.ALPHA_WEIGHT)
                                .setTooltip(Translatable.ALPHA_WEIGHT_Tooltip)
                                .setRange(0, 100, 1)
                                .setValueFormatter(value -> Translatable.WEIGHT_Value(value))
                                .setStorageHandler(this::save)
                                .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                                .setBinding(value -> config.ALPHA_WEIGHT = value, () -> config.ALPHA_WEIGHT)
                                .setDefaultValue(config.ALPHA_WEIGHT)
                        )
                        .addOption(builder.createIntegerOption(Identifier.parse("colorlight:anomaly_weight"))
                                .setName(Translatable.ANOMALY_WEIGHT)
                                .setTooltip(Translatable.ANOMALY_WEIGHT_Tooltip)
                                .setRange(0, 100, 1)
                                .setValueFormatter(value -> Translatable.WEIGHT_Value(value))
                                .setStorageHandler(this::save)
                                .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                                .setBinding(value -> config.ANOMALY_WEIGHT = value, () -> config.ANOMALY_WEIGHT)
                                .setDefaultValue(config.ANOMALY_WEIGHT)
                        )
                        .addOption(builder.createIntegerOption(Identifier.parse("colorlight:saturation_weight"))
                                .setName(Translatable.SATURATION_WEIGHT)
                                .setTooltip(Translatable.SATURATION_WEIGHT_Tooltip)
                                .setRange(0, 100, 1)
                                .setValueFormatter(value -> Translatable.WEIGHT_Value(value))
                                .setStorageHandler(this::save)
                                .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                                .setBinding(value -> config.SATURATION_WEIGHT = value, () -> config.SATURATION_WEIGHT)
                                .setDefaultValue(config.SATURATION_WEIGHT)
                        )
                        .addOption(builder.createIntegerOption(Identifier.parse("colorlight:glowcolorscore_weight"))
                                .setName(Translatable.GLOWCOLORSCORE_WEIGHT)
                                .setTooltip(Translatable.GLOWCOLORSCORE_WEIGHT_Tooltip)
                                .setRange(0, 100, 1)
                                .setValueFormatter(value -> Translatable.WEIGHT_Value(value))
                                .setStorageHandler(this::save)
                                .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                                .setBinding(value -> config.GLOWCOLORSCORE_WEIGHT = value, () -> config.GLOWCOLORSCORE_WEIGHT)
                                .setDefaultValue(config.GLOWCOLORSCORE_WEIGHT)
                        )
                        .addOption(builder.createIntegerOption(Identifier.parse("colorlight:whitepenalty_weight"))
                                .setName(Translatable.WHITEPENALTY_WEIGHT)
                                .setTooltip(Translatable.WHITEPENALTY_WEIGHT_Tooltip)
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
                                    .setScreenConsumer(parentScreen -> Minecraft.getInstance().setScreenAndShow(new ColorLightBlockConfigScreen(parentScreen, liveEntry(entry), this::save, this::applyImmediately)))
                            )
            );
        }
        return page;
    }
    /** The entry the config holds for this block right now (a reload may have replaced the one this page was built with). */
    private static BlockSettings liveEntry(BlockSettings built) {
        for (BlockSettings e : ColorLightClient.config.blocks) {
            if (e.block.equals(built.block))
                return e;
        }
        return built;
    }
    private OptionPageBuilder CompatibilityPage(ConfigBuilder builder, boolean ldl, boolean voxy) {
        Identifier entityEnabledId = Identifier.parse("colorlight:entity_tracking_enabled");
        Identifier entityFollowId = Identifier.parse("colorlight:entity_follow_render_distance");
        ColorLightClient.LOGGER.info("[ColorLight] Mods supported by ColorLight: ldl - {}, voxy - {}.", ldl, voxy);
        OptionPageBuilder page = builder.createOptionPage().setName(Translatable.COMPATIBILITY);
        if (ldl) {
            page.addOptionGroup(builder.createOptionGroup()
                    .setName(Translatable.ENTITY_TRACKING)
                    .addOption(builder.createBooleanOption(entityEnabledId)
                            .setName(Translatable.ENTITY_TRACKING_ENABLED)
                            .setTooltip(Translatable.ENTITY_TRACKING_ENABLED_Tooltip)
                            .setImpact(OptionImpact.MEDIUM)
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
                    .addOption(builder.createExternalButtonOption(Identifier.parse("colorlight:dynamic_propagation"))
                                .setName(Translatable.DYNAMIC_PROPAGATION)
                                .setTooltip(Translatable.DYNAMIC_PROPAGATION_Tooltip)
                                .setScreenConsumer(parentScreen -> Minecraft.getInstance().setScreenAndShow(new PropagationPickerScreen(
                                        parentScreen, Translatable.DYNAMIC_PROPAGATION,
                                        () -> config.DYNAMIC_PROPAGATION, value -> config.DYNAMIC_PROPAGATION = value,
                                        this::applyImmediately, method -> method.createTable(255f / 15f) != null, PropagationMethodRegistry.SMOOTH)))
                        )
                    .addOption(builder.createIntegerOption(Identifier.parse("colorlight:entity_check_radius_chunks"))
                            .setName(Translatable.ENTITY_CHECK_RADIUS_CHUNKS)
                            .setTooltip(Translatable.ENTITY_CHECK_RADIUS_CHUNKS_Tooltip)
                            .setImpact(OptionImpact.MEDIUM)
                            .setEnabledProvider(state -> state.readBooleanOption(entityEnabledId) && !state.readBooleanOption(entityFollowId), entityEnabledId, entityFollowId)
                            .setRange(2, 32, 1)
                            .setValueFormatter(value -> Translatable.CHUNKS_Value(value))
                            .setStorageHandler(this::save)
                            .setBinding(value -> config.ENTITY_CHECK_RADIUS_CHUNKS = value, () -> config.ENTITY_CHECK_RADIUS_CHUNKS)
                            .setDefaultValue(config.ENTITY_CHECK_RADIUS_CHUNKS)
                    )
            );
        }
        if (voxy) {
            page.addOptionGroup(builder.createOptionGroup()
                    .setName(Translatable.VOXY)
                        .addOption(builder.createBooleanOption(Identifier.parse("colorlight:voxy_light"))
                                .setName(Translatable.VOXY_LIGHT)
                                .setTooltip(Translatable.VOXY_LIGHT_Tooltip)
                                .setStorageHandler(this::save)
                                .setBinding(value -> config.VOXY_LIGHT = value, () -> config.VOXY_LIGHT)
                                .setDefaultValue(config.VOXY_LIGHT)
                        )
            );
        }
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
        ColorLightApply.everything(true);
    }
}
