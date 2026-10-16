package me.mrhikmen.colorlight.client.core.resourcepack.data;

/**
 * One JSON file found in a resource pack.
 *
 * @param namespace the resource namespace it lives in ({@code assets/<namespace>/...}); unqualified ids it
 *                  registers get this namespace
 * @param name      path below its folder without {@code .json}, e.g. {@code "grid"} for
 *                  {@code propagation/grid.json}; for {@code settings.json} it is {@code "settings"}
 * @param packId    which pack it came from, for log messages
 * @param text      the file's text
 */
public record DataFile(String namespace, String name, String packId, String text) {

    /** Where this file is, for log/error messages. */
    public String origin(String folder) {
        return "[" + packId + "] " + namespace + ":" + folder + name + ".json";
    }
}
