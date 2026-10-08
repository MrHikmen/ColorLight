package me.mrhikmen.colorlight.client.core.resourcepack.lua;

import java.util.List;
import java.util.Map;

/**
 * The outcome of loading every script of the active resource packs.
 *
 * @param settings     pack-provided default settings, by {@link SettingKey}
 * @param methods      propagation methods the scripts defined
 * @param blocks       block rules
 * @param problems     human-readable errors and warnings (also logged); shown by {@code /colorlight scripts}
 */
public record LoadedScripts(Map<SettingKey, Object> settings, List<ScriptedPropagationMethod> methods, BlockRules blocks, List<String> problems) {

    /** Nothing loaded (before the first resource reload). */
    public static LoadedScripts empty() {
        return new LoadedScripts(Map.of(), List.of(), new BlockRules(), List.of());
    }
}
