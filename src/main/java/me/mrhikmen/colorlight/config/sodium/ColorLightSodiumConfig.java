package me.mrhikmen.colorlight.config.sodium;

import me.mrhikmen.colorlight.ColorLightClient;
import me.mrhikmen.colorlight.config.ColorLightConfig;
import me.mrhikmen.colorlight.config.Translatable;
import me.mrhikmen.colorlight.light.ColorLightBlockRegistry;
import me.mrhikmen.colorlight.light.ColorLightEngineHolder;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.SteppedValidator;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;

import net.minecraft.resources.ResourceLocation;

public class ColorLightSodiumConfig implements ConfigEntryPoint {

    // ВАЖНО: используем ОБЩИЙ конфиг мода (ColorLightClient.config), а не создаём
    // свою копию — иначе правки, сделанные в настройках Sodium, уходят в объект,
    // который остальной мод (движок, реестр блоков, сканер чанков) не видит,
    // и рендер визуально не реагирует на изменения настроек.
    private final ColorLightConfig config = ColorLightClient.config;

    @Override
    public void registerConfigLate(ConfigBuilder builder) {

        config.load(); // безопасно перезагрузить на случай, если это вызывается раньше onInitializeClient

        builder.registerOwnModOptions()
                .setNonTintedIcon(ResourceLocation.parse("colorlight:icon.png"))
                .setColorTheme(builder.createColorTheme().setBaseThemeRGB(0x73efff))
                .addPage(builder.createOptionPage()
                        .setName(Translatable.GENERAL)
                        .addOptionGroup(builder.createOptionGroup()
                                .addOption(builder.createBooleanOption(ResourceLocation.parse("colorlight:enable"))
                                        .setName(Translatable.ENABLE)
                                        .setTooltip(Translatable.ENABLE_Tooltip)

                                        .setStorageHandler(this::save)
                                        .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                                        .setBinding(this::setEnable, this::getEnable)
                                        .setDefaultValue(true)
                                )
                                .addOption(builder.createIntegerOption(ResourceLocation.parse("colorlight:light_range"))
                                        .setName(Translatable.LIGHT_RANGE)
                                        .setTooltip(Translatable.LIGHT_RANGE_Tooltip)

                                        .setValidator(new SteppedValidator() {
                                            @Override
                                            public int min() {
                                                return 1;
                                            }

                                            @Override
                                            public int max() {
                                                return 64;
                                            }

                                            @Override
                                            public int step() {
                                                return 1;
                                            }
                                        })

                                        .setValueFormatter(value -> Translatable.LIGHT_RANGE_Value(value))

                                        .setStorageHandler(this::save)
                                        .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                                        .setBinding(this::setLightRange, this::getLightRange)
                                        .setDefaultValue(15)
                                )
                        )
                )
//
//                .addPage(builder.createExternalPage()
//                        .setName(Translatable.BLOCK)
//                        .setScreenConsumer(screen ->
//                                net.minecraft.client.Minecraft.getInstance().setScreen(new ColorLightConfigScreen(screen, config)))
//                )
                .addPage(builder.createOptionPage()
                        .setName(Translatable.FUN)
                        .addOptionGroup(builder.createOptionGroup()
                                .addOption(builder.createIntegerOption(ResourceLocation.parse("colorlight:brightness_weight"))
                                        .setName(Translatable.ENABLE)
                                        .setTooltip(Translatable.ENABLE_Tooltip)

                                        .setValidator(new SteppedValidator() {
                                            @Override
                                            public int min() {
                                                return 0;
                                            }

                                            @Override
                                            public int max() {
                                                return 100;
                                            }

                                            @Override
                                            public int step() {
                                                return 1;
                                            }
                                        })
                                        .setValueFormatter(value -> Translatable.LIGHT_RANGE_Value(value))

                                        .setStorageHandler(this::save)
                                        .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                                        .setBinding(this::setBrightnessWeight, this::getBrightnessWeight)
                                        .setDefaultValue(30)
                                )
                                .addOption(builder.createIntegerOption(ResourceLocation.parse("colorlight:anomaly_weight"))
                                        .setName(Translatable.ENABLE)
                                        .setTooltip(Translatable.ENABLE_Tooltip)

                                        .setValidator(new SteppedValidator() {
                                            @Override
                                            public int min() {
                                                return 0;
                                            }

                                            @Override
                                            public int max() {
                                                return 100;
                                            }

                                            @Override
                                            public int step() {
                                                return 1;
                                            }
                                        })
                                        .setValueFormatter(value -> Translatable.LIGHT_RANGE_Value(value))

                                        .setStorageHandler(this::save)
                                        .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                                        .setBinding(this::setAnomalyWeight, this::getAnomalyWeight)
                                        .setDefaultValue(12)
                                )
                        )
                );
    }

    private void setEnable(boolean value) {config.ENABLE = value;}

    private boolean getEnable() {return config.ENABLE;}

    private void setLightRange(int value) {config.lightRangeBlocks = Math.max(1, Math.min(64, value));}
    private int getLightRange() {return config.lightRangeBlocks;}

    private void setBrightnessWeight(int value) {config.BRIGHTNESS_WEIGHT = value;}
    private int getBrightnessWeight() {return config.BRIGHTNESS_WEIGHT;}

    private void setAnomalyWeight(int value) {config.ANOMALY_WEIGHT = value;}
    private int getAnomalyWeight() {return config.ANOMALY_WEIGHT;}

    private void save() {
        config.save();
        ColorLightEngineHolder.configure(config.lightRangeBlocks);
        ColorLightBlockRegistry.load(config); // без этого правки блоков не долетают до живого реестра
    }
}