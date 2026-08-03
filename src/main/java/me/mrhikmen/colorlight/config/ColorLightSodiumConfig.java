package me.mrhikmen.colorlight.config;

import me.mrhikmen.colorlight.ColorLightClient;
import me.mrhikmen.colorlight.core.light.ColorLightBlockRegistry;
import me.mrhikmen.colorlight.core.light.ColorLightChunkScanner;
import me.mrhikmen.colorlight.core.light.ColorLightEngineHolder;
import me.mrhikmen.colorlight.core.util.ColorLightRenderUtil;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPointForge;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.SteppedValidator;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;

import net.minecraft.resources.Identifier;

@ConfigEntryPointForge("colorlight")
public class ColorLightSodiumConfig implements ConfigEntryPoint {

    private final ColorLightConfig config = ColorLightClient.config;

    @Override
    public void registerConfigLate(ConfigBuilder builder) {

        config.load();

        builder.registerOwnModOptions()
                .setNonTintedIcon(Identifier.parse("colorlight:icon.png"))
                .setColorTheme(builder.createColorTheme().setBaseThemeRGB(0x73efff))
                .addPage(builder.createOptionPage()
                        .setName(Translatable.GENERAL)
                        .addOptionGroup(builder.createOptionGroup()
                                .addOption(builder.createBooleanOption(Identifier.parse("colorlight:enable"))
                                        .setName(Translatable.ENABLE)
                                        .setTooltip(Translatable.ENABLE_Tooltip)

                                        .setStorageHandler(this::save)
                                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                                        .setBinding(this::setEnable, this::getEnable)
                                        .setDefaultValue(true)
                                )
                                .addOption(builder.createIntegerOption(Identifier.parse("colorlight:light_range"))
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
                                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
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
                                .setName(Translatable.WEIGHT)
                                .addOption(builder.createIntegerOption(Identifier.parse("colorlight:brightness_weight"))
                                        .setName(Translatable.BRIGHTNESS_WEIGHT)
                                        .setTooltip(Translatable.WEIGHT_Tooltip)

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
                                        .setValueFormatter(value -> Translatable.WEIGHT_Value(value))

                                        .setStorageHandler(this::save)
                                        .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                                        .setBinding(this::setBrightnessWeight, this::getBrightnessWeight)
                                        .setDefaultValue(100)
                                )
                                .addOption(builder.createIntegerOption(Identifier.parse("colorlight:local_weight"))
                                        .setName(Translatable.LOCAL_WEIGHT)
                                        .setTooltip(Translatable.WEIGHT_Tooltip)

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
                                        .setValueFormatter(value -> Translatable.WEIGHT_Value(value))

                                        .setStorageHandler(this::save)
                                        .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                                        .setBinding(this::setLocalWeight, this::getLocalWeight)
                                        .setDefaultValue(100)
                                )
                                .addOption(builder.createIntegerOption(Identifier.parse("colorlight:region_weight"))
                                        .setName(Translatable.REGION_WEIGHT)
                                        .setTooltip(Translatable.WEIGHT_Tooltip)

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
                                        .setValueFormatter(value -> Translatable.WEIGHT_Value(value))

                                        .setStorageHandler(this::save)
                                        .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                                        .setBinding(this::setRegionWeight, this::getRegionWeight)
                                        .setDefaultValue(0)
                                )
                                .addOption(builder.createIntegerOption(Identifier.parse("colorlight:alpha_weight"))
                                        .setName(Translatable.ALPHA_WEIGHT)
                                        .setTooltip(Translatable.WEIGHT_Tooltip)

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
                                        .setValueFormatter(value -> Translatable.WEIGHT_Value(value))

                                        .setStorageHandler(this::save)
                                        .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                                        .setBinding(this::setAlphaWeight, this::getAlphaWeight)
                                        .setDefaultValue(0)
                                )
                                .addOption(builder.createIntegerOption(Identifier.parse("colorlight:anomaly_weight"))
                                        .setName(Translatable.ANOMALY_WEIGHT)
                                        .setTooltip(Translatable.WEIGHT_Tooltip)

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
                                        .setValueFormatter(value -> Translatable.WEIGHT_Value(value))

                                        .setStorageHandler(this::save)
                                        .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                                        .setBinding(this::setAnomalyWeight, this::getAnomalyWeight)
                                        .setDefaultValue(100)
                                )
                                .addOption(builder.createIntegerOption(Identifier.parse("colorlight:saturation_weight"))
                                        .setName(Translatable.SATURATION_WEIGHT)
                                        .setTooltip(Translatable.WEIGHT_Tooltip)

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
                                        .setValueFormatter(value -> Translatable.WEIGHT_Value(value))

                                        .setStorageHandler(this::save)
                                        .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                                        .setBinding(this::setSaturationWeight, this::getSaturationWeight)
                                        .setDefaultValue(100)
                                )
                                .addOption(builder.createIntegerOption(Identifier.parse("colorlight:glowcolorscore_weight"))
                                        .setName(Translatable.GLOWCOLORSCORE_WEIGHT)
                                        .setTooltip(Translatable.WEIGHT_Tooltip)

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
                                        .setValueFormatter(value -> Translatable.WEIGHT_Value(value))

                                        .setStorageHandler(this::save)
                                        .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                                        .setBinding(this::setGlowcolorscoreWeight, this::getGlowcolorscoreWeight)
                                        .setDefaultValue(100)
                                )
                                .addOption(builder.createIntegerOption(Identifier.parse("colorlight:whitepenalty_weight"))
                                        .setName(Translatable.WHITEPENALTY_WEIGHT)
                                        .setTooltip(Translatable.WEIGHT_Tooltip)

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
                                        .setValueFormatter(value -> Translatable.WEIGHT_Value(value))

                                        .setStorageHandler(this::save)
                                        .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                                        .setBinding(this::setWhitepenaltyWeight, this::getWhitepenaltyWeight)
                                        .setDefaultValue(100)
                                )
                        )
                );
    }

    private void save() {
        config.save();
        ColorLightEngineHolder.configure(config.lightRangeBlocks);
        ColorLightBlockRegistry.load(config);

        var client = net.minecraft.client.Minecraft.getInstance();
        if (client.level != null) {
            ColorLightEngineHolder.set(client.level);
            if (config.ENABLE) {
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

        // Было: client.levelRenderer.setBlocksDirty(...)
        ColorLightRenderUtil.setBlocksDirty(client.level,
                pos.getX() - renderDistanceBlocks, client.level.getMinY(), pos.getZ() - renderDistanceBlocks,
                pos.getX() + renderDistanceBlocks, client.level.getMaxY(), pos.getZ() + renderDistanceBlocks
        );
    }

    private void setEnable(boolean value) {
        config.ENABLE = value;
    }
    private boolean getEnable() {
        return config.ENABLE;
    }

    private void setLightRange(int value) {
        config.lightRangeBlocks = Math.max(1, Math.min(64, value));
    }
    private int getLightRange() {
        return config.lightRangeBlocks;
    }

    private void setBrightnessWeight(int value) {
        config.BRIGHTNESS_WEIGHT = value;
    }
    private int getBrightnessWeight() {
        return config.BRIGHTNESS_WEIGHT;
    }

    private void setLocalWeight(int value) {
        config.LOCAL_WEIGHT = value;
    }
    private int getLocalWeight() {
        return config.LOCAL_WEIGHT;
    }

    private void setRegionWeight(int value) {
        config.REGION_WEIGHT = value;
    }
    private int getRegionWeight() {
        return config.REGION_WEIGHT;
    }

    private void setAlphaWeight(int value) {
        config.ALPHA_WEIGHT = value;
    }
    private int getAlphaWeight() {
        return config.ALPHA_WEIGHT;
    }

    private void setAnomalyWeight(int value) {
        config.ANOMALY_WEIGHT = value;
    }
    private int getAnomalyWeight() {
        return config.ANOMALY_WEIGHT;
    }

    private void setSaturationWeight(int value) {
        config.SATURATION_WEIGHT = value;
    }
    private int getSaturationWeight() {
        return config.SATURATION_WEIGHT;
    }

    private void setGlowcolorscoreWeight(int value) {
        config.GLOWCOLORSCORE_WEIGHT = value;
    }
    private int getGlowcolorscoreWeight() {
        return config.GLOWCOLORSCORE_WEIGHT;
    }

    private void setWhitepenaltyWeight(int value) {
        config.WHITEPENALTY_WEIGHT = value;
    }
    private int getWhitepenaltyWeight() {
        return config.WHITEPENALTY_WEIGHT;
    }
}