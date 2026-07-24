package me.mrhikmen.colorlight.render;

import me.mrhikmen.colorlight.ColorLightClient;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;

public class ColorLightTestModelPlugin implements ModelLoadingPlugin {

    @Override
    public void onInitializeModelLoader(Context pluginContext) {

        ColorLightClient.LOGGER.info("[ColorLight] ModelLoadingPlugin initialized, wrapping models");

        pluginContext.modifyModelAfterBake().register((model, context) -> new TintedBakedModel(model));
    }
}
