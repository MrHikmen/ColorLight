package me.mrhikmen.colorlight.core.scanner.blockstateresolver;

import me.mrhikmen.colorlight.ColorLightClient;
import me.mrhikmen.colorlight.core.scanner.PixelData;
import me.mrhikmen.colorlight.core.scanner.SearchBestPixel;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import static me.mrhikmen.colorlight.core.scanner.PathTextureBlock.stateToVariant;

public class VariantParser {
    public VariantParser(BlockState state, JsonObject json) {

        JsonObject variants = json.getAsJsonObject("variants");
        JsonElement variantElement = variants.get(stateToVariant(state));

        if (variantElement == null && variants.has("")) {
            variantElement = variants.get("");
        }

        if (variantElement == null) {
            return;
        }
        List<ResourceLocation> models = new ArrayList<>();

        if (variantElement.isJsonObject()) {
            JsonObject model = variantElement.getAsJsonObject();
            models.add(ResourceLocation.parse(model.get("model").getAsString()));
            List<PixelData> bestPixels = new ArrayList<>();

            boolean smalworc = SmalWork(models, bestPixels);

        } else if (variantElement.isJsonArray()) {
            for (JsonElement element : variantElement.getAsJsonArray()) {

                JsonObject model = element.getAsJsonObject();
                models.add(ResourceLocation.parse(model.get("model").getAsString()));
                List<PixelData> bestPixels = new ArrayList<>();

                boolean smalworc = SmalWork(models, bestPixels);

            }
        }
    }

    private static boolean SmalWork(List<ResourceLocation> models, List<PixelData> bestPixels) {
        for (ResourceLocation modelId : models) {

            Optional<Resource> resourceId = Minecraft.getInstance().getResourceManager().getResource(ResourceLocation.fromNamespaceAndPath(modelId.getNamespace(), "models/" + modelId.getPath() + ".json"));

            if (!resourceId.isPresent())
                continue;

            try (InputStream streamId = resourceId.get().open()) {

                JsonObject jsonId = JsonParser.parseString(
                                new String(streamId.readAllBytes(), StandardCharsets.UTF_8))
                        .getAsJsonObject();

                if (!jsonId.has("textures"))
                    continue;

                JsonObject textures = jsonId.getAsJsonObject("textures");

                for (Map.Entry<String, JsonElement> entry : textures.entrySet()) {

                    ResourceLocation texture =
                            ResourceLocation.parse(entry.getValue().getAsString());

                    bestPixels.add(SearchBestPixel.search(texture));
                }
                PixelData best = null;

                for (PixelData pixel : bestPixels) {

                    if (best == null || pixel.score > best.score)
                        best = pixel;
                }
                ColorLightClient.LOGGER.info(best.r + " " + best.g + " " + best.b + " score = " + best.score);

            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return true;
    }
}

