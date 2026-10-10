package me.mrhikmen.colorlight.client.core.resourcepack.data;

import java.util.List;
import java.util.Map;

/**
 * The outcome of loading every data file of the active resource packs.
 *
 * @param settings     pack-provided default settings, by {@link SettingKey}
 * @param methods      propagation methods the JSON files defined
 * @param blocks       block rules
 * @param problems     human-readable errors and warnings (also logged); shown by {@code /colorlight scripts}
 */
public record LoadedData(Map<SettingKey, Object> settings, List<JsonPropagationMethod> methods, BlockRules blocks, List<String> problems) {

    /** Nothing loaded (before the first resource reload). */
    public static LoadedData empty() {
        return new LoadedData(Map.of(), List.of(), new BlockRules(), List.of());
    }
}
