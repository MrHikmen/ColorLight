package me.mrhikmen.colorlight.client.compat.lambdynlights;

import dev.lambdaurora.lambdynlights.api.DynamicLightsContext;
import dev.lambdaurora.lambdynlights.api.DynamicLightsInitializer;
import dev.lambdaurora.lambdynlights.api.item.ItemLightSourceManager;
import me.mrhikmen.colorlight.client.ColorLightClient;

public class ColorLightLambDynLightsInitializer implements DynamicLightsInitializer {

    @Override
    public void onInitializeDynamicLights(DynamicLightsContext context) {
    context.itemLightSourceManager().onRegisterEvent().register(registerContext -> {
            // Read what resource packs say about item luminance first (this fires whenever LambDynamicLights applies
            // its data), so an explicit "luminance": 0 can be honoured below and by the entity light ticker.
            LdlItemLuminanceOverrides.reload(registerContext.registryLookup());

            for (me.mrhikmen.colorlight.client.config.BlockSettings entry : ColorLightClient.config.blocks) {
                if (!entry.enable)
                    continue;

                net.minecraft.world.level.block.Block block =
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(entry.getBlock());
                if (block == null)
                    continue;

                net.minecraft.world.item.Item item = block.asItem();
                if (item == net.minecraft.world.item.Items.AIR)
                    continue;

                // LambDynamicLights takes the maximum over every registered source. Rather than skip registering
                // and hope its own file-loading also applied the pack's value (that assumption is what silently
                // broke intermediate overrides before), register the resolved value directly: the pack's own
                // number - 0, 15, or anything in between - when one is given, ColorLight's configured strength
                // otherwise. Because it's the same value the pack asked for, max(pack's value, ours) never changes
                // it, so this works whether or not LambDynamicLights also applies the file itself.
                net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(item);
                int resolvedLight = LdlItemLuminanceOverrides.resolve(stack, entry.light);

                registerContext.register(item, Math.min(15, resolvedLight));
            }
        });
    }

    @Override
    public void onInitializeDynamicLights(ItemLightSourceManager itemLightSourceManager) {
    }
}