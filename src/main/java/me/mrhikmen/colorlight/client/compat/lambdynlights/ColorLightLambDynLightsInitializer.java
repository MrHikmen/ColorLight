package me.mrhikmen.colorlight.client.compat.lambdynlights;

import dev.lambdaurora.lambdynlights.api.DynamicLightsContext;
import dev.lambdaurora.lambdynlights.api.DynamicLightsInitializer;

import me.mrhikmen.colorlight.client.config.BlockSettings;
import me.mrhikmen.colorlight.client.core.light.registry.ColorLightBlockRegistry;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;

import net.minecraft.world.level.block.Block;

public class ColorLightLambDynLightsInitializer implements DynamicLightsInitializer {

    @Override
    public void onInitializeDynamicLights(DynamicLightsContext context) {
        context.itemLightSourceManager().onRegisterEvent().register(registerContext -> {
            // Read what resource packs say about item luminance first (this fires whenever LambDynamicLights applies
            // its data), so an explicit "luminance": 0 can be honoured below and by the entity light ticker.
            LdlItemLuminanceOverrides.reload(registerContext.registryLookup());

            for (java.util.Map.Entry<Block, BlockSettings> glowing : ColorLightBlockRegistry.entries().entrySet()) {
                BlockSettings entry = glowing.getValue();
                Block block = glowing.getKey();

                Item item = block.asItem();
                if (item == Items.AIR)
                    continue;

                // LambDynamicLights takes the maximum over every registered source. Rather than skip registering
                // and hope its own file-loading also applied the pack's value (that assumption is what silently
                // broke intermediate overrides before), register the resolved value directly: the pack's own
                // number - 0, 15, or anything in between - when one is given, ColorLight's configured strength
                // otherwise. Because it's the same value the pack asked for, max(pack's value, ours) never changes
                // it, so this works whether or not LambDynamicLights also applies the file itself.
                ItemStack stack = new ItemStack(item);
                int resolvedLight = LdlItemLuminanceOverrides.resolve(stack, entry.light);

                registerContext.register(item, Math.min(15, resolvedLight));
            }
        });
    }
}