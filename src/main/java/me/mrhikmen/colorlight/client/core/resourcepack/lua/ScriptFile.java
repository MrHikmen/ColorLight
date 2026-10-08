package me.mrhikmen.colorlight.client.core.resourcepack.lua;

/**
 * One Lua file found in a resource pack.
 *
 * @param namespace the resource namespace it lives in ({@code assets/<namespace>/...}); unqualified ids the script
 *                  registers get this namespace
 * @param name      path below its folder without {@code .lua}, e.g. {@code "grid"} for
 *                  {@code propagation/grid.lua}; for {@code settings.lua} it is {@code "settings"}
 * @param packId    which pack it came from, for log messages
 * @param text      the script source
 */
public record ScriptFile(String namespace, String name, String packId, String text) {

    /** Chunk name used in Lua error messages. */
    public String chunkName(String folder) {
        return "@" + namespace + ":" + folder + name + ".lua";
    }
}
