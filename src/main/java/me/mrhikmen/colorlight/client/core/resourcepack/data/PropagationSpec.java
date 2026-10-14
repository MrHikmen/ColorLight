package me.mrhikmen.colorlight.client.core.resourcepack.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import me.mrhikmen.colorlight.client.core.light.propagation.LightPropagator;
import me.mrhikmen.colorlight.client.core.light.propagation.TablePropagator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static me.mrhikmen.colorlight.client.core.light.propagation.PropagationTables.*;

/**
 * What a propagation JSON file describes: plain data, no code. ColorLight no longer runs any user-supplied
 * script - every shape is built from {@link #neighbors} (which of the 26 surrounding cells light may hop to)
 * and a small, fixed {@link Loss} formula whose weights come straight from the file:
 * <pre>
 * units = decay * multiplier * distanceFactor(dx,dy,dz) * (1 + opacityFactor * opacity) * axisFactor(dy) + flat * decay
 * </pre>
 * This single formula is expressive enough to reproduce every built-in shape (grid, smooth, a vertical beam, ...)
 * purely through numbers in the resource pack's JSON file; see {@code examples/API.md}.
 * <p>
 * The formula is evaluated once per (neighbour, opacity) pair per light range and cached in a lookup table
 * ({@link #decayTable}), so resolving it costs nothing while light is actually spreading - the same
 * {@link TablePropagator} kernel every spec (built-in or resource pack) now runs through.
 */
public final class PropagationSpec {

    /** Light units (255 = full brightness) a hop of offset {@code (dx,dy,dz)} into a block of {@code opacity} costs. */
    @FunctionalInterface
    public interface Loss {
        double units(int dx, int dy, int dz, int opacity, float decayPerOpacityUnit, int rangeBlocks);
    }

    public final String name;
    public final int scale;
    public final boolean preserveHue;
    public final int maxOpacity;

    final int[] dx, dy, dz;
    final Loss loss;

    private final Map<Float, int[][]> decayCache = new ConcurrentHashMap<>();

    private PropagationSpec(String name, int scale, boolean preserveHue, int maxOpacity, int[] dx, int[] dy, int[] dz, Loss loss) {
        this.name = name;
        this.scale = scale;
        this.preserveHue = preserveHue;
        this.maxOpacity = maxOpacity;
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
        this.loss = loss;
    }

    public int neighborCount() {
        return dx.length;
    }

    /** Builds the propagator for one engine. {@code decay} is {@code 255 / range}. */
    public LightPropagator createPropagator(float decay) {
        return createTable(decay);
    }

    /** The table-driven kernel for this spec. */
    public TablePropagator createTable(float decay) {
        return new TablePropagator(dx, dy, dz, decayTable(decay), scale, preserveHue, maxOpacity);
    }

    /** The raw {@code decay[neighbour][opacity]} table the {@link TablePropagator} kernel runs on. */
    int[][] decayTable(float decay) {
        return decayCache.computeIfAbsent(decay, d -> {
            int range = Math.max(1, Math.round(255f / d));
            int cap = 255 * scale + 1;
            int[][] table = new int[dx.length][OPACITY_LEVELS];
            for (int n = 0; n < dx.length; n++) {
                for (int opacity = 0; opacity < OPACITY_LEVELS; opacity++) {
                    double units = loss.units(dx[n], dy[n], dz[n], opacity, d, range);
                    long scaled = Double.isNaN(units) ? 1 : (units >= cap ? cap : Math.round(units * scale));
                    // never below 1: a hop that costs nothing would let light flood the whole world
                    table[n][opacity] = (int) Math.max(1, Math.min(scaled, cap));
                }
            }
            return table;
        });
    }

    public int[] offsetsX() { return dx.clone(); }
    public int[] offsetsY() { return dy.clone(); }
    public int[] offsetsZ() { return dz.clone(); }

    // =====================================================================================
    // Built-in fallbacks: used only if no pack (not even the mod's own) provides "grid" / "smooth"
    // =====================================================================================

    /** Diamond: the 6 face neighbours, whole units, cost {@code (1+opacity)*decay}. Same shape as the bundled grid.json. */
    public static PropagationSpec fallbackGrid() {
        List<int[]> faces = new ArrayList<>();
        faces.add(new int[]{0, -1, 0});
        faces.add(new int[]{0, 1, 0});
        faces.add(new int[]{0, 0, -1});
        faces.add(new int[]{0, 0, 1});
        faces.add(new int[]{-1, 0, 0});
        faces.add(new int[]{1, 0, 0});
        return fromOffsets("Grid (diamond)", 1, true, MAX_OPACITY, faces,
                (x, y, z, opacity, decay, range) -> (1 + opacity) * (double) decay);
    }

    /** Circle: all 26 neighbours, 1/8 units, cost proportional to the real distance. Same shape as the bundled smooth.json. */
    public static PropagationSpec fallbackSmooth() {
        List<int[]> all = new ArrayList<>();
        for (int i = 0; i < NEIGHBOR_COUNT; i++)
            all.add(new int[]{NEIGHBOR_DX[i], NEIGHBOR_DY[i], NEIGHBOR_DZ[i]});
        return fromOffsets("Smooth (circle)", 8, true, MAX_OPACITY, all,
                (x, y, z, opacity, decay, range) -> Math.sqrt(x * x + y * y + z * z) * (1 + opacity) * (double) decay);
    }

    private static PropagationSpec fromOffsets(String name, int scale, boolean hue, int maxOpacity, List<int[]> offsets, Loss loss) {
        int[] x = new int[offsets.size()], y = new int[offsets.size()], z = new int[offsets.size()];
        for (int i = 0; i < x.length; i++) {
            x[i] = offsets.get(i)[0];
            y[i] = offsets.get(i)[1];
            z[i] = offsets.get(i)[2];
        }
        return new PropagationSpec(name, scale, hue, maxOpacity, x, y, z, loss);
    }

    // =====================================================================================
    // JSON -> spec
    // =====================================================================================

    /**
     * Reads a spec from a propagation JSON file, e.g.:
     * <pre>{@code
     * {
     *   "name": "Smooth (circle)",
     *   "scale": 8,
     *   "preserve_hue": true,
     *   "max_opacity": 15,
     *   "neighbors": "full",
     *   "loss": { "distance": true, "opacity_factor": 1.0 }
     * }
     * }</pre>
     *
     * @throws IllegalArgumentException with a message a pack author can act on
     */
    public static PropagationSpec fromJson(JsonElement root, String fallbackName) {
        if (!root.isJsonObject())
            throw new IllegalArgumentException("a propagation file must be a JSON object (got " + describe(root) + ")");
        JsonObject table = root.getAsJsonObject();

        String name = string(table, "name", fallbackName);
        int maxOpacity = clampInt(intField(table, "max_opacity", MAX_OPACITY), 1, MAX_OPACITY);
        int scale = intField(table, "scale", 1);
        if (scale < 1 || scale > 64)
            throw new IllegalArgumentException("'scale' must be between 1 and 64 (got " + scale + ")");
        boolean hue = !table.has("preserve_hue") || table.get("preserve_hue").getAsBoolean();

        List<int[]> offsets = readNeighbors(table.get("neighbors"));
        if (offsets.isEmpty())
            throw new IllegalArgumentException("'neighbors' is required and must not be empty: \"faces\", \"full\", or a list of [dx,dy,dz]");

        Loss loss = readLoss(table.get("loss"));

        int n = offsets.size();
        int[] x = new int[n], y = new int[n], z = new int[n];
        for (int i = 0; i < n; i++) {
            x[i] = offsets.get(i)[0];
            y[i] = offsets.get(i)[1];
            z[i] = offsets.get(i)[2];
        }
        PropagationSpec spec = new PropagationSpec(name, scale, hue, maxOpacity, x, y, z, loss);
        // evaluate the decay table now, at a typical range, so a broken formula is reported when the pack loads
        spec.decayTable(255f / 15f);
        return spec;
    }

    private static List<int[]> readNeighbors(JsonElement neighbors) {
        if (neighbors == null || neighbors.isJsonNull())
            throw new IllegalArgumentException("'neighbors' is required: \"faces\", \"full\", or a list of [dx,dy,dz]");

        if (neighbors.isJsonPrimitive() && neighbors.getAsJsonPrimitive().isString()) {
            String kind = neighbors.getAsString().trim().toLowerCase(java.util.Locale.ROOT);
            return switch (kind) {
                case "faces", "face", "diamond" -> List.of(
                        new int[]{0, -1, 0}, new int[]{0, 1, 0},
                        new int[]{0, 0, -1}, new int[]{0, 0, 1},
                        new int[]{-1, 0, 0}, new int[]{1, 0, 0});
                case "full", "all", "circle" -> {
                    List<int[]> all = new ArrayList<>();
                    for (int i = 0; i < NEIGHBOR_COUNT; i++)
                        all.add(new int[]{NEIGHBOR_DX[i], NEIGHBOR_DY[i], NEIGHBOR_DZ[i]});
                    yield all;
                }
                default -> throw new IllegalArgumentException("'neighbors' must be \"faces\", \"full\", or a list of [dx,dy,dz] (got \"" + kind + "\")");
            };
        }

        if (!neighbors.isJsonArray())
            throw new IllegalArgumentException("'neighbors' must be \"faces\", \"full\", or a list of [dx,dy,dz]");

        JsonArray list = neighbors.getAsJsonArray();
        List<int[]> result = new ArrayList<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            JsonElement entry = list.get(i);
            if (!entry.isJsonArray() || entry.getAsJsonArray().size() != 3)
                throw new IllegalArgumentException("neighbors[" + i + "] must be [dx, dy, dz]");
            JsonArray t = entry.getAsJsonArray();
            int x = t.get(0).getAsInt(), y = t.get(1).getAsInt(), z = t.get(2).getAsInt();
            if (x < -1 || x > 1 || y < -1 || y > 1 || z < -1 || z > 1)
                throw new IllegalArgumentException("neighbors[" + i + "] = [" + x + "," + y + "," + z
                        + "]: light can only hop to the 26 surrounding blocks (each offset -1, 0 or 1)");
            if (x == 0 && y == 0 && z == 0)
                throw new IllegalArgumentException("neighbors[" + i + "] is [0,0,0], the block itself");
            if (seen.add((x + 1) * 9 + (y + 1) * 3 + (z + 1)))
                result.add(new int[]{x, y, z});
        }
        return result;
    }

    /**
     * {@code loss}: all fields optional.
     * <pre>
     * "distance"           bool   multiply by sqrt(dx^2+dy^2+dz^2) instead of a flat 1 (default false)
     * "opacity_factor"     num    weight of opacity in (1 + opacity_factor * opacity) (default 1.0)
     * "vertical_factor"    num    extra multiplier for hops with dy != 0 (default 1.0)
     * "horizontal_factor"  num    extra multiplier for hops with dy == 0 (default 1.0)
     * "multiplier"         num    overall scale of the whole formula (default 1.0)
     * "flat"                num    extra constant units added on top, in decay units (default 0.0)
     * </pre>
     */
    private static Loss readLoss(JsonElement lossElement) {
        if (lossElement == null || lossElement.isJsonNull()) {
            return (x, y, z, opacity, decay, range) -> Math.sqrt(x * x + y * y + z * z) * (1 + opacity) * (double) decay;
        }
        if (!lossElement.isJsonObject())
            throw new IllegalArgumentException("'loss' must be an object, e.g. { \"distance\": true, \"opacity_factor\": 1.0 }");

        JsonObject o = lossElement.getAsJsonObject();
        boolean distance = o.has("distance") && o.get("distance").getAsBoolean();
        double opacityFactor = doubleField(o, "opacity_factor", 1.0);
        double verticalFactor = doubleField(o, "vertical_factor", 1.0);
        double horizontalFactor = doubleField(o, "horizontal_factor", 1.0);
        double multiplier = doubleField(o, "multiplier", 1.0);
        double flat = doubleField(o, "flat", 0.0);

        return (dx, dy, dz, opacity, decay, range) -> {
            double distanceFactor = distance ? Math.sqrt(dx * dx + dy * dy + dz * dz) : 1.0;
            double axisFactor = dy != 0 ? verticalFactor : horizontalFactor;
            return decay * multiplier * distanceFactor * (1 + opacityFactor * opacity) * axisFactor + flat * decay;
        };
    }

    // ---- small JSON helpers -------------------------------------------------------------

    private static String string(JsonObject o, String key, String fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
    }

    private static int intField(JsonObject o, String key, int fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : fallback;
    }

    private static double doubleField(JsonObject o, String key, double fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : fallback;
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String describe(JsonElement e) {
        return e == null || e.isJsonNull() ? "nothing" : e.toString();
    }
}
