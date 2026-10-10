package me.mrhikmen.colorlight.client.core.resourcepack.data;

import java.util.List;

/**
 * Where the loader finds ColorLight's JSON files. The Minecraft implementation reads the resource manager; tests
 * can use plain lists.
 * <p>
 * Every list is in application order: lowest-priority pack first, so later files win.
 */
public interface DataSource {

    /** {@code assets/colorlight/settings.json}, from every pack that has one. */
    List<DataFile> settings();

    /** {@code assets/colorlight/propagation/*.json}. */
    List<DataFile> propagation();

    /** {@code assets/colorlight/block/*.json} (text of each file). */
    List<DataFile> blocks();
}
