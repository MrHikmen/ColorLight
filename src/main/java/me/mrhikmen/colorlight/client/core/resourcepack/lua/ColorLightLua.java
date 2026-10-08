package me.mrhikmen.colorlight.client.core.resourcepack.lua;

import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaValue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lets another mod add functions or whole tables to the {@code colorlight} table that resource-pack scripts see,
 * e.g. to expose a mod's own block families to block scripts.
 * <pre>{@code
 * // in your ClientModInitializer:
 * ColorLightLua.register("mymod_is_crystal", new OneArgFunction() {
 *     public LuaValue call(LuaValue id) { return valueOf(id.tojstring().endsWith("_crystal")); }
 * });
 * // in a pack's block script:   if colorlight.mymod_is_crystal("mymod:red_crystal") then ... end
 * }</pre>
 * Names may not overwrite ColorLight's own functions. Register before the first resource reload (mod init).
 * It is your code running for a downloaded resource pack: don't expose the file system, the network or anything
 * else a pack must not reach.
 */
public final class ColorLightLua {

    private static final Map<String, LuaValue> EXTENSIONS = new LinkedHashMap<>();

    public static synchronized void register(String name, LuaFunction function) {
        put(name, function);
    }

    /** Registers a value (a table of constants, a string, ...). */
    public static synchronized void registerValue(String name, LuaValue value) {
        put(name, value);
    }

    private static void put(String name, LuaValue value) {
        if (name == null || name.isBlank() || value == null)
            throw new IllegalArgumentException("name and value must be set");
        EXTENSIONS.put(name, value);
    }

    /** Snapshot read by the script loader. */
    public static synchronized Map<String, LuaValue> extensions() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(EXTENSIONS));
    }

    private ColorLightLua() {
    }
}
