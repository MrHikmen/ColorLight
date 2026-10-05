package me.mrhikmen.colorlight.client.core.scanner.model.blockstate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Shared bit of blockstate JSON that both {@link VariantParser} (a {@code variants} entry) and
 * {@link MultipartParser} (a multipart case's {@code apply}) need to read the same way: the value is either a
 * single model object or an array of them, and only each one's {@code model} field matters here.
 */
final class ModelListJson {

    private ModelListJson() {
    }

    /** Appends the model id(s) named by {@code applyOrVariant} (a single object or an array of them) to {@code out}. */
    static void collectModels(JsonElement applyOrVariant, List<Identifier> out) {
        if (applyOrVariant.isJsonObject()) {
            addModel(applyOrVariant.getAsJsonObject(), out);

        } else if (applyOrVariant.isJsonArray()) {
            for (JsonElement element : applyOrVariant.getAsJsonArray()) {
                addModel(element.getAsJsonObject(), out);
            }
        }
    }

    private static void addModel(JsonObject model, List<Identifier> out) {
        if (!model.has("model"))
            return;

        out.add(Identifier.parse(model.get("model").getAsString()));
    }
}
