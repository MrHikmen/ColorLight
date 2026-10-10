package me.mrhikmen.colorlight.client.core.resourcepack.data;

/** What the block scripts need to know about the game's blocks. Kept as an interface so the loader has no game dependency. */
public interface BlockCatalog {

    boolean exists(String blockId);

    /** All block ids, e.g. {@code "minecraft:torch"}. */
    Iterable<String> allIds();

    /** Whether the block is in the block tag {@code tagId} (given without the leading '#'). */
    boolean inTag(String blockId, String tagId);

    /** Whether a mod (or the game, id {@code "minecraft"}) is loaded. */
    boolean isModLoaded(String modId);
}
