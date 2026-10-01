package me.mrhikmen.colorlight.client.script;

import me.mrhikmen.colorlight.api.propagation.PropagationMethodRegistry;
import me.mrhikmen.colorlight.client.lua.BlockRules;
import me.mrhikmen.colorlight.client.lua.LoadedScripts;
import me.mrhikmen.colorlight.client.lua.LuaPackLoader;
import me.mrhikmen.colorlight.client.lua.PropagationSpec;
import me.mrhikmen.colorlight.client.lua.ScriptedPropagationMethod;
import me.mrhikmen.colorlight.client.lua.SettingKey;

import me.mrhikmen.colorlight.client.ColorLightClient;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.List;
import java.util.Map;

/**
 * Holds what the resource packs' Lua scripts defined, and puts their propagation methods into
 * {@link PropagationMethodRegistry}. Reloaded with every resource reload ({@code F3+T}, pack changes).
 */
public final class ScriptRuntime {

    private static volatile LoadedScripts loaded = LoadedScripts.empty();

    /**
     * Runs every script of the active packs. Must run before the config applies pack defaults and before
     * the block registry is rebuilt.
     */
    public static void reload(ResourceManager manager) {
        LoadedScripts scripts;
        try {
            scripts = LuaPackLoader.load(new ResourceScriptSource(manager));
        } catch (Throwable t) {
            // Never let a scripting failure take the game down (a failed resource reload is a black screen).
            ColorLightClient.LOGGER.error("[ColorLight] Lua scripts could not be loaded; continuing with built-in defaults", t);
            scripts = LoadedScripts.empty();
        }

        PropagationMethodRegistry.clearScripted();
        registerFallbacks();
        for (ScriptedPropagationMethod method : scripts.methods())
            PropagationMethodRegistry.register(method);

        loaded = scripts;
    }

    /**
     * Makes sure {@code colorlight:grid} and {@code colorlight:smooth} exist even if no pack provides them (a pack
     * removed the mod's own files, or scripts have not loaded yet during early startup). They are replaced by the Lua
     * versions as soon as those load, so in normal operation these never run.
     */
    public static void registerFallbacks() {
        PropagationMethodRegistry.register(new ScriptedPropagationMethod(PropagationMethodRegistry.GRID, PropagationSpec.fallbackGrid(), "built-in fallback"));
        PropagationMethodRegistry.register(new ScriptedPropagationMethod(PropagationMethodRegistry.SMOOTH, PropagationSpec.fallbackSmooth(), "built-in fallback"));
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

    public static List<ScriptedPropagationMethod> methods() {
        return loaded.methods();
    }

    private ScriptRuntime() {
    }
}
