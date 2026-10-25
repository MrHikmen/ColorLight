package me.mrhikmen.colorlight.client.compat.lod;

import me.mrhikmen.colorlight.client.ColorLightClient;
import me.mrhikmen.colorlight.client.core.light.propagation.PropagationMethodRegistry;
import me.mrhikmen.colorlight.client.core.light.propagation.TablePropagator;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Lets the LOD shaders spread light with ANY propagation method (the ones in resource packs too).
 * <p>
 * The LOD shaders run no flood fill: they measure a distance from a source to a block. For a method that distance is
 * "how far the method carries light in open air": the cheapest path of hops from the source to the block, in units of
 * one hop straight along an axis (so the diamond method gives the block-walking distance, the smooth one about the
 * true distance, and a method of a resource pack whatever shape it draws). That path search is done here, once per
 * method, into a 33x33x33 table of distances (offsets -16..16) the shader looks up.
 * <p>
 * Eight tables are uploaded: table 0 is the method of the settings (what a block that names no method uses), tables
 * 1..7 are the first seven methods of {@link #slots()}. A stored source carries its table in bits 5..7 of its top byte
 * ({@link #slotOf}); a block whose method does not fit in the seven uses table 0. Contains no class of any LOD mod.
 */
public final class LodMethodTables {

    public static final int RADIUS = 16;
    public static final int SIDE = 2 * RADIUS + 1;
    public static final int TABLE_INTS = SIDE * SIDE * SIDE;
    public static final int TABLES = 8;
    public static final int TOTAL_INTS = TABLES * TABLE_INTS;

    private static final float FAR = 1.0e9f;

    private LodMethodTables() {
    }

    /** The methods that get their own table: the two built-in ones first, then the others by id. At most seven. */
    public static List<ResourceLocation> slots() {
        List<ResourceLocation> ids = new ArrayList<>();
        for (var m : PropagationMethodRegistry.all())
            ids.add(m.id());
        ids.sort(Comparator.comparing((ResourceLocation id) -> id.equals(PropagationMethodRegistry.GRID) ? 0 : id.equals(PropagationMethodRegistry.SMOOTH) ? 1 : 2)
                .thenComparing(ResourceLocation::toString));
        return ids.size() > TABLES - 1 ? new ArrayList<>(ids.subList(0, TABLES - 1)) : ids;
    }

    /** 0 = "the method of the settings", else the table (1..7) of this method. */
    public static int slotOf(ResourceLocation id) {
        if (id == null)
            return 0;
        int i = slots().indexOf(id);
        return i < 0 ? 0 : i + 1;
    }

    /** Changes when the tables would come out different (a resource reload, another default method in the settings). */
    public static long key() {
        String raw = ColorLightClient.config.PROPAGATION_MODE;
        return ((long) PropagationMethodRegistry.version() << 32) ^ (raw == null ? 0 : raw.hashCode());
    }

    /** All eight tables, as float bits, ready for the GPU. */
    public static int[] build() {
        int[] out = new int[TOTAL_INTS];
        List<ResourceLocation> slots = slots();
        ResourceLocation def = PropagationMethodRegistry.parse(ColorLightClient.config.PROPAGATION_MODE);
        for (int t = 0; t < TABLES; t++) {
            ResourceLocation id = t == 0 ? def : (t - 1 < slots.size() ? slots.get(t - 1) : def);
            float[] d = distances(id);
            for (int i = 0; i < TABLE_INTS; i++)
                out[t * TABLE_INTS + i] = Float.floatToRawIntBits(d[i]);
        }
        return out;
    }

    private static float[] distances(ResourceLocation id) {
        float[] result = new float[TABLE_INTS];
        Arrays.fill(result, FAR);
        TablePropagator table = PropagationMethodRegistry.tableFor(id, null, 1f);
        if (table == null) { // nothing registered: a plain round light
            for (int x = -RADIUS; x <= RADIUS; x++)
                for (int y = -RADIUS; y <= RADIUS; y++)
                    for (int z = -RADIUS; z <= RADIUS; z++)
                        result[index(x, y, z)] = (float) Math.sqrt(x * x + y * y + z * z);
            return result;
        }

        int n = table.neighbourCount();
        // one hop straight along an axis is the unit of distance
        int axis = Integer.MAX_VALUE;
        for (int i = 0; i < n; i++)
            if (Math.abs(table.offsetX(i)) + Math.abs(table.offsetY(i)) + Math.abs(table.offsetZ(i)) == 1)
                axis = Math.min(axis, table.hopCost(i, 0));
        if (axis == Integer.MAX_VALUE)
            for (int i = 0; i < n; i++)
                axis = Math.min(axis, table.hopCost(i, 0));
        axis = Math.max(1, axis);

        int[] cost = new int[TABLE_INTS];
        Arrays.fill(cost, Integer.MAX_VALUE);
        PriorityQueue<Long> queue = new PriorityQueue<>();
        int start = index(0, 0, 0);
        cost[start] = 0;
        queue.add((long) start);
        while (!queue.isEmpty()) {
            long top = queue.poll();
            int c = (int) (top >>> 32);
            int at = (int) top;
            if (c > cost[at])
                continue;
            int z = at % SIDE - RADIUS, y = (at / SIDE) % SIDE - RADIUS, x = at / (SIDE * SIDE) - RADIUS;
            for (int i = 0; i < n; i++) {
                int nx = x + table.offsetX(i), ny = y + table.offsetY(i), nz = z + table.offsetZ(i);
                if (Math.abs(nx) > RADIUS || Math.abs(ny) > RADIUS || Math.abs(nz) > RADIUS)
                    continue;
                int nc = c + table.hopCost(i, 0);
                int ni = index(nx, ny, nz);
                if (nc < cost[ni]) {
                    cost[ni] = nc;
                    queue.add(((long) nc << 32) | ni);
                }
            }
        }
        for (int i = 0; i < TABLE_INTS; i++)
            if (cost[i] != Integer.MAX_VALUE)
                result[i] = cost[i] / (float) axis;
        return result;
    }

    private static int index(int x, int y, int z) {
        return ((x + RADIUS) * SIDE + (y + RADIUS)) * SIDE + (z + RADIUS);
    }
}
