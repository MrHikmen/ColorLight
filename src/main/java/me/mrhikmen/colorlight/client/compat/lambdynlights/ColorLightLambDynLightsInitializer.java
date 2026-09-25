package me.mrhikmen.colorlight.client.compat.lambdynlights;

import dev.lambdaurora.lambdynlights.api.DynamicLightsContext;
import dev.lambdaurora.lambdynlights.api.DynamicLightsInitializer;
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
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(entry.getBlock());
                if (block == null)
                    continue;

                net.minecraft.world.item.Item item = block.asItem();
                if (item == net.minecraft.world.item.Items.AIR)
                    continue;

                // LambDynamicLights takes the maximum over all sources, so registering a light here would override
                // a resource pack that deliberately set this item to luminance 0. Leave such items alone.
                if (LdlItemLuminanceOverrides.isDisabled(new net.minecraft.world.item.ItemStack(item)))
                    continue;

                registerContext.register(item, Math.min(15, entry.light));
            }
        });
    }
}
