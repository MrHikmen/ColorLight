package me.mrhikmen.colorlight.client.core.render;

import me.mrhikmen.colorlight.client.ColorLightClient;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;

import net.minecraft.client.resources.model.ModelResourceLocation;

public class ModelPlugin implements ModelLoadingPlugin {

    private static final String INVENTORY_VARIANT = "inventory";

    @Override
    public void onInitializeModelLoader(Context pluginContext) {

        ColorLightClient.LOGGER.info("[ColorLight] ModelLoadingPlugin initialized");

        pluginContext.modifyModelAfterBake().register((model, context) -> {
            if (model == null)
                return null;

            ModelResourceLocation topLevelId = context.topLevelId();
            if (topLevelId == null || INVENTORY_VARIANT.equals(topLevelId.variant()))
                return model;

            return new TintedBakedModel(model);
        });
    }
}
