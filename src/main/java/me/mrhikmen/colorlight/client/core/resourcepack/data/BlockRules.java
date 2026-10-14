package me.mrhikmen.colorlight.client.core.resourcepack.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Everything the block files (assets/colorlight/block/*.json) said, in the order they said it. See {@link BlockJson}
 * for the file format. Rules are plain data and are resolved against the game's blocks by {@link #resolve}.
 */
public final class BlockRules {

    enum Kind { ID, TAG, GLOB }

    record Rule(Kind kind, String selector, BlockOverride override, String origin) {
    }

    private final List<Rule> rules = new ArrayList<>();

    void add(Kind kind, String selector, BlockOverride override, String origin) {
        rules.add(new Rule(kind, selector, override, origin));
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    public int size() {
        return rules.size();
    }

    /** Expands the rules over the game's blocks: block id -&gt; merged override (later rules win, field by field). */
    public Map<String, BlockOverride> resolve(BlockCatalog catalog) {
        Map<String, BlockOverride> result = new LinkedHashMap<>();
        for (Rule rule : rules) {
            switch (rule.kind()) {
                case ID -> {
                    if (catalog.exists(rule.selector()))
                        result.computeIfAbsent(rule.selector(), k -> new BlockOverride()).merge(rule.override());
                }
                case TAG -> {
                    for (String id : catalog.allIds())
                        if (catalog.inTag(id, rule.selector()))
                            result.computeIfAbsent(id, k -> new BlockOverride()).merge(rule.override());
                }
                case GLOB -> {
                    Pattern pattern = globToRegex(rule.selector());
                    for (String id : catalog.allIds())
                        if (pattern.matcher(id).matches())
                            result.computeIfAbsent(id, k -> new BlockOverride()).merge(rule.override());
                }
            }
        }
        return result;
    }

    /** {@code *} matches any run of characters, {@code ?} one character; everything else is literal. */
    static Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                default -> sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(sb.toString());
    }
}
