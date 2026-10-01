package me.mrhikmen.colorlight.client.lua;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

import java.io.StringReader;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads a block file, {@code assets/colorlight/block/<name>.json}: an object whose keys select blocks and whose values
 * describe the light.
 * <pre>
 * {
 *   "minecraft:torch":        { "color": [255, 150, 60], "light": 14 },
 *   "minecraft:soul_torch":   { "color": "#3fd0ff" },
 *   "minecraft:lantern":      { "rgb": { "r": 255, "g": 200, "b": 120 } },
 *   "minecraft:candle":       { "hex": "ffcc66", "propagation": "colorlight:smooth" },
 *   "#minecraft:candles":     { "light": 12 },
 *   "minecraft:*_lantern":    { "r": 255, "g": 190, "b": 110 },
 *   "minecraft:lava":         { "enabled": false }
 * }
 * </pre>
 * <b>Keys:</b> a block id ({@code minecraft:} may be left out), a block tag with {@code #}, or a pattern where
 * {@code *} / {@code ?} are wildcards. Keys starting with {@code _} are ignored (use them for comments).
 * <p>
 * <b>Colour</b>, any one of: {@code "color"} - {@code "#rrggbb"} / {@code "#rgb"} text, {@code [r,g,b]} or
 * {@code {"r":..,"g":..,"b":..}}; {@code "hex"} - text; {@code "rgb"} - array or object; or plain {@code "r"}, {@code "g"},
 * {@code "b"} fields. Channels are 0..255.
 * <p>
 * <b>Other fields</b> (all optional - what is left out stays as it was): {@code "light"} 1..15 (cap on the emitted level),
 * {@code "propagation"} (method id), {@code "enabled"} (false switches coloured light off for the block).
 * <p>
 * Comments ({@code //}, {@code /* *}{@code /}) are tolerated. A bad entry is reported and skipped; the rest of the file still applies.
 */
public final class BlockJson {

    /** @return number of rules read */
    static int parse(String text, String origin, BlockRules rules, List<String> problems) {
        JsonElement root;
        try {
            JsonReader reader = new JsonReader(new StringReader(text));
            reader.setLenient(true);
            root = JsonParser.parseReader(reader);
        } catch (RuntimeException e) {
            problems.add(origin + ": invalid JSON: " + e.getMessage());
            return 0;
        }
        if (!root.isJsonObject()) {
            problems.add(origin + ": the file must be a JSON object { \"minecraft:torch\": { ... } }");
            return 0;
        }

        int count = 0;
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
            String key = entry.getKey().trim();
            if (key.isEmpty() || key.startsWith("_"))
                continue;
            try {
                BlockOverride override = parseOverride(entry.getValue());
                if (key.startsWith("#"))
                    rules.add(BlockRules.Kind.TAG, normalize(key.substring(1)), override, origin);
                else if (key.indexOf('*') >= 0 || key.indexOf('?') >= 0)
                    rules.add(BlockRules.Kind.GLOB, key.contains(":") ? key.toLowerCase(Locale.ROOT) : "minecraft:" + key.toLowerCase(Locale.ROOT), override, origin);
                else
                    rules.add(BlockRules.Kind.ID, normalize(key), override, origin);
                count++;
            } catch (IllegalArgumentException e) {
                problems.add(origin + ": \"" + key + "\": " + e.getMessage());
            }
        }
        return count;
    }

    static BlockOverride parseOverride(JsonElement value) {
        if (!value.isJsonObject())
            throw new IllegalArgumentException("expected an object like { \"color\": \"#ff8800\", \"light\": 14 }");
        JsonObject def = value.getAsJsonObject();
        BlockOverride o = new BlockOverride();

        int[] rgb = null;
        for (String field : new String[]{"color", "hex", "rgb"}) {
            if (def.has(field)) {
                rgb = readColor(def.get(field), field);
                break;
            }
        }
        if (rgb == null && (def.has("r") || def.has("g") || def.has("b"))) {
            rgb = new int[]{channel(def.get("r"), "r"), channel(def.get("g"), "g"), channel(def.get("b"), "b")};
        }
        if (rgb != null) {
            o.r = rgb[0];
            o.g = rgb[1];
            o.b = rgb[2];
        }

        if (def.has("light")) {
            JsonElement l = def.get("light");
            if (!l.isJsonPrimitive() || !l.getAsJsonPrimitive().isNumber())
                throw new IllegalArgumentException("\"light\" must be a number 1..15");
            o.light = Math.max(0, Math.min(15, l.getAsInt()));
        }
        if (def.has("propagation")) {
            JsonElement p = def.get("propagation");
            if (!p.isJsonPrimitive() || !p.getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("\"propagation\" must be a method id like \"colorlight:smooth\"");
            o.propagation = p.getAsString();
        }
        if (def.has("enabled")) {
            JsonElement e = def.get("enabled");
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean())
                throw new IllegalArgumentException("\"enabled\" must be true or false");
            o.enabled = e.getAsBoolean();
        }
        return o;
    }

    /** Text, array or object -&gt; {r,g,b}. */
    static int[] readColor(JsonElement e, String field) {
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            int[] rgb = parseHex(e.getAsString());
            if (rgb == null)
                throw new IllegalArgumentException("\"" + field + "\" must look like \"#ff8800\" or \"#f80\" (got \"" + e.getAsString() + "\")");
            return rgb;
        }
        if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            if (a.size() != 3)
                throw new IllegalArgumentException("\"" + field + "\" array must be [r, g, b]");
            return new int[]{channel(a.get(0), field + "[0]"), channel(a.get(1), field + "[1]"), channel(a.get(2), field + "[2]")};
        }
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            return new int[]{channel(o.get("r"), field + ".r"), channel(o.get("g"), field + ".g"), channel(o.get("b"), field + ".b")};
        }
        throw new IllegalArgumentException("\"" + field + "\" must be \"#rrggbb\", [r, g, b] or { \"r\": .., \"g\": .., \"b\": .. }");
    }

    private static int channel(JsonElement e, String what) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("\"" + what + "\" must be a number 0..255");
        return Math.max(0, Math.min(255, (int) Math.round(e.getAsDouble())));
    }

    static int[] parseHex(String text) {
        String h = text.trim();
        if (h.startsWith("#"))
            h = h.substring(1);
        try {
            if (h.length() == 3)
                h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
            if (h.length() != 6)
                return null;
            int v = Integer.parseInt(h, 16);
            return new int[]{(v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** "torch" -&gt; "minecraft:torch". */
    private static String normalize(String id) {
        String lower = id.trim().toLowerCase(Locale.ROOT);
        return lower.contains(":") ? lower : "minecraft:" + lower;
    }

    private BlockJson() {
    }
}
