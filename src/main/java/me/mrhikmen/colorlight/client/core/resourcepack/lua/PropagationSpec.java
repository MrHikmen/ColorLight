package me.mrhikmen.colorlight.client.core.resourcepack.lua;

import me.mrhikmen.colorlight.client.core.light.propagation.LightPropagator;
import me.mrhikmen.colorlight.client.core.light.propagation.ScriptPropagator;
import me.mrhikmen.colorlight.client.core.light.propagation.TablePropagator;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static me.mrhikmen.colorlight.client.core.light.propagation.PropagationTables.*;

/**
 * What a propagation script describes. Two shapes:
 * <ul>
 *     <li><b>table-driven</b> - {@link #neighbors}, a {@link Loss} rule, {@link #scale} and {@link #preserveHue};
 *     built into a {@link TablePropagator};</li>
 *     <li><b>scripted</b> - the script supplies {@code propagate(field, queue)} itself
 *     ({@link ScriptPropagator}).</li>
 * </ul>
 * The {@link Loss} rule is evaluated for every (neighbour, opacity) pair <i>once</i> per light range and cached, so a
 * Lua {@code loss} function costs nothing while light is actually spreading.
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

    // scripted mode only
    private final LuaSandbox sandbox;
    private final LuaValue propagateFunction;

    private final Map<Float, int[][]> decayCache = new ConcurrentHashMap<>();

    private PropagationSpec(String name, int scale, boolean preserveHue, int maxOpacity, int[] dx, int[] dy, int[] dz, Loss loss,
                            LuaSandbox sandbox, LuaValue propagateFunction) {
        this.name = name;
        this.scale = scale;
        this.preserveHue = preserveHue;
        this.maxOpacity = maxOpacity;
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
        this.loss = loss;
        this.sandbox = sandbox;
        this.propagateFunction = propagateFunction;
    }

    public boolean isScripted() {
        return propagateFunction != null;
    }

    public int neighborCount() {
        return dx.length;
    }

    /** Builds the propagator for one engine. {@code decay} is {@code 255 / range}. */
    public LightPropagator createPropagator(float decay) {
        if (isScripted())
            return new ScriptPropagator(sandbox, propagateFunction, name, decay, maxOpacity);
        return createTable(decay);
    }

    /** The table-driven kernel for this spec, or {@code null} for a scripted spec. */
    public TablePropagator createTable(float decay) {
        if (isScripted())
            return null;
        return new TablePropagator(dx, dy, dz, decayTable(decay), scale, preserveHue, maxOpacity);
    }

    private int[][] decayTable(float decay) {
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

    // =====================================================================================
    // Java-defined specs: the fallback used if no pack (not even the mod's own) provides "grid" / "smooth"
    // =====================================================================================

    /** Diamond: the 6 face neighbours, whole units, cost {@code (1+opacity)*decay}. Same as the bundled grid.lua. */
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

    /** Circle: all 26 neighbours, 1/8 units, cost proportional to the real distance. Same as the bundled smooth.lua. */
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
        return new PropagationSpec(name, scale, hue, maxOpacity, x, y, z, loss, null, null);
    }

    // =====================================================================================
    // Lua -> spec
    // =====================================================================================

    /**
     * Reads a spec from the table a propagation script returned.
     *
     * @throws IllegalArgumentException with a message a pack author can act on
     */
    public static PropagationSpec fromLua(LuaSandbox sandbox, LuaValue table, String fallbackName) {
        if (!table.istable())
            throw new IllegalArgumentException("a propagation script must return a table (got " + table.typename() + ")");

        String name = table.get("name").isstring() ? table.get("name").tojstring() : fallbackName;
        int maxOpacity = table.get("max_opacity").isnumber() ? clampInt(table.get("max_opacity").toint(), 1, MAX_OPACITY) : MAX_OPACITY;

        LuaValue propagate = table.get("propagate");
        if (propagate.isfunction()) {
            return new PropagationSpec(name, 1, true, maxOpacity, new int[0], new int[0], new int[0], null, sandbox, propagate);
        }

        int scale = table.get("scale").isnumber() ? table.get("scale").toint() : 1;
        if (scale < 1 || scale > 64)
            throw new IllegalArgumentException("'scale' must be between 1 and 64 (got " + scale + ")");
        boolean hue = table.get("preserve_hue").isnil() || table.get("preserve_hue").toboolean();

        LuaValue neighbors = table.get("neighbors");
        if (neighbors.isfunction()) {
            neighbors = sandbox.call(LuaSandbox.LOAD_BUDGET, neighbors).arg1();
        }
        if (!neighbors.istable())
            throw new IllegalArgumentException("'neighbors' is required: a list of {dx, dy, dz} (see colorlight.faces / colorlight.full)");

        List<int[]> offsets = readOffsets((LuaTable) neighbors);
        if (offsets.isEmpty())
            throw new IllegalArgumentException("'neighbors' is empty - light would never leave its source");

        LuaValue lossValue = table.get("loss");
        Loss loss;
        if (lossValue.isfunction()) {
            loss = (x, y, z, opacity, decay, range) -> {
                Varargs result = sandbox.call(LuaSandbox.LOAD_BUDGET, lossValue,
                        LuaValue.valueOf(x), LuaValue.valueOf(y), LuaValue.valueOf(z),
                        LuaValue.valueOf(opacity), LuaValue.valueOf(decay), LuaValue.valueOf(range));
                LuaValue units = result.arg1();
                if (!units.isnumber())
                    throw new LuaError("'loss' must return a number (got " + units.typename() + ")");
                return units.todouble();
            };
        } else if (lossValue.isnil()) {
            loss = (x, y, z, opacity, decay, range) -> Math.sqrt(x * x + y * y + z * z) * (1 + opacity) * (double) decay;
        } else {
            throw new IllegalArgumentException("'loss' must be a function(dx, dy, dz, opacity, decay, range) returning light units");
        }

        int n = offsets.size();
        int[] x = new int[n], y = new int[n], z = new int[n];
        for (int i = 0; i < n; i++) {
            x[i] = offsets.get(i)[0];
            y[i] = offsets.get(i)[1];
            z[i] = offsets.get(i)[2];
        }
        PropagationSpec spec = new PropagationSpec(name, scale, hue, maxOpacity, x, y, z, loss, null, null);
        // evaluate the loss rule now, at a typical range, so a broken function is reported when the pack loads
        // (the resulting table is cached and reused for that range)
        spec.decayTable(255f / 15f);
        return spec;
    }

    private static List<int[]> readOffsets(LuaTable list) {
        List<int[]> result = new ArrayList<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        int length = list.length();
        for (int i = 1; i <= length; i++) {
            LuaValue entry = list.get(i);
            if (!entry.istable())
                throw new IllegalArgumentException("neighbors[" + i + "] must be a table {dx, dy, dz}");
            int x = entry.get(1).checkint(), y = entry.get(2).checkint(), z = entry.get(3).checkint();
            if (x < -1 || x > 1 || y < -1 || y > 1 || z < -1 || z > 1)
                throw new IllegalArgumentException("neighbors[" + i + "] = {" + x + "," + y + "," + z
                        + "}: light can only hop to the 26 surrounding blocks (each offset -1, 0 or 1)");
            if (x == 0 && y == 0 && z == 0)
                throw new IllegalArgumentException("neighbors[" + i + "] is {0,0,0}, the block itself");
            if (seen.add((x + 1) * 9 + (y + 1) * 3 + (z + 1)))
                result.add(new int[]{x, y, z});
        }
        return result;
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
