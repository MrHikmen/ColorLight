package me.mrhikmen.colorlight.client.core.resourcepack.script;

import me.mrhikmen.colorlight.api.propagation.PropagationMethodRegistry;
import me.mrhikmen.colorlight.client.core.resourcepack.data.BlockRules;
import me.mrhikmen.colorlight.client.core.resourcepack.data.DataPackLoader;
import me.mrhikmen.colorlight.client.core.resourcepack.data.JsonPropagationMethod;
import me.mrhikmen.colorlight.client.core.resourcepack.data.LoadedData;
import me.mrhikmen.colorlight.client.core.resourcepack.data.PropagationSpec;
import me.mrhikmen.colorlight.client.core.resourcepack.data.SettingKey;

import me.mrhikmen.colorlight.client.ColorLightClient;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.List;
import java.util.Map;

/**
 * Holds what the resource packs' JSON files defined, and puts their propagation methods into
 * {@link PropagationMethodRegistry}. Reloaded with every resource reload ({@code F3+T}, pack changes).
 * <p>
 * There is no scripting engine here: a resource pack supplies numbers, not code, so a reload can never run
 * arbitrary logic - it only ever parses and validates JSON.
 */
public final class DataRuntime {

    private static volatile LoadedData loaded = LoadedData.empty();

    /**
     * Reads every data file of the active packs. Must run before the config applies pack defaults and before
     * the block registry is rebuilt.
     */
    public static void reload(ResourceManager manager) {
        LoadedData data;
        try {
            data = DataPackLoader.load(new ResourceDataSource(manager));
        } catch (Throwable t) {
            // Never let a malformed resource pack take the game down (a failed resource reload is a black screen).
            ColorLightClient.LOGGER.error("[ColorLight] resource pack data could not be loaded; continuing with built-in defaults", t);
            data = LoadedData.empty();
        }

        PropagationMethodRegistry.clearScripted();
        registerFallbacks();
        for (JsonPropagationMethod method : data.methods())
            PropagationMethodRegistry.register(method);

        loaded = data;
    }

    /**
     * Makes sure {@code colorlight:grid} and {@code colorlight:smooth} exist even if no pack provides them (a pack
     * removed the mod's own files, or data has not loaded yet during early startup). They are replaced by a pack's
     * own files as soon as those load, so in normal operation these never run.
     */
    public static void registerFallbacks() {
        PropagationMethodRegistry.register(new JsonPropagationMethod(PropagationMethodRegistry.GRID, PropagationSpec.fallbackGrid(), "built-in fallback"));
        PropagationMethodRegistry.register(new JsonPropagationMethod(PropagationMethodRegistry.SMOOTH, PropagationSpec.fallbackSmooth(), "built-in fallback"));
    }

    public static Map<SettingKey, Object> settings() {
        return loaded.settings();
    }

    /** Block rules of the active packs; never null, possibly empty. */
    public static BlockRules blockRules() {
        return loaded.blocks();
    }

    /** A catalog of the game's blocks as they are right now (tags included once they are loaded). */
    public static MinecraftBlockCatalog catalog() {
        return new MinecraftBlockCatalog();
    }

    public static List<String> problems() {
        return loaded.problems();
    }

    public static List<JsonPropagationMethod> methods() {
        return loaded.methods();
    }

    private DataRuntime() {
    }
}
