package me.mrhikmen.colorlight.client.lua;

import java.util.List;

/**
 * Where the loader finds scripts. The Minecraft implementation reads the resource manager; the tests use lists.
 * <p>
 * Every list is in application order: lowest-priority pack first, so later files win.
 */
public interface ScriptSource {

    /** {@code assets/colorlight/settings.lua}, from every pack that has one. */
    List<ScriptFile> settings();

    /** {@code assets/colorlight/propagation/*.lua}. */
    List<ScriptFile> propagation();

    /** {@code assets/colorlight/block/*.json} (text of each file). */
    List<ScriptFile> blocks();
}
