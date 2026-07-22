package me.mrhikmen.colorlight.config;

//import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
//import net.minecraft.network.chat.Component;
//import net.minecraft.resources.ResourceLocation;
//
//public class ColorLightSodiumConfig {
//    private final OptionStorage storage = new OptionStorage();
//    private final Runnable handler = this.storage::flush; // typically gets referenced many times
//
//    @Override
//    public void registerConfigLate(ConfigBuilder builder) {
//        builder.registerOwnModOptions()
//                .setIcon(ResourceLocation.parse("colorlight:icon.png"))
//                .addPage(builder.createOptionPage()
//                        .setName(Component.literal("Example Page"))
//                        .addOptionGroup(builder.createOptionGroup()
//                                .setName(Component.literal("Example Group")) // only if necessary for clarity
//                                .addOption(builder.createBooleanOption(ResourceLocation.parse("examplemod:example_option"))
//                                        .setName(Component.literal("Example Option")) // use translation keys here
//                                        .setTooltip(Component.literal("Example tooltip"))
//                                        .setStorageHandler(this.handler)
//                                        .setBinding(this.storage::setExampleOption, this.storage::getExampleOption)
//                                        .setDefaultValue(true)
//                                )
//                        )
//                );
//    }
//}
//class OptionStorage {
//    private boolean exampleOption = true;
//
//    public boolean getExampleOption() {
//        return this.exampleOption;
//    }
//
//    public void setExampleOption(boolean value) {
//        this.exampleOption = value;
//    }
//
//    public void flush() {
//        // flush options to config file
//    }
//}
