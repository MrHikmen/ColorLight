package me.mrhikmen.colorlight.client.core.light.engine;

import me.mrhikmen.colorlight.client.core.light.propagation.LightField;
import me.mrhikmen.colorlight.client.core.light.util.PassMap;
import me.mrhikmen.colorlight.client.core.light.util.PosKey;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;

import static me.mrhikmen.colorlight.client.core.light.propagation.PropagationTables.MAX_OPACITY;

/**
 * The {@link LightField} a flood on a worker thread spreads through: block opacity is read from the level, and what the
 * flood lights is only <b>collected</b> (cell and brightness, the highest one per cell) - nothing shared is written. The
 * thread that holds the engine lock stores the collected values afterwards, see {@link ParallelFlood}.
 * <p>
 * Holds scratch memory: one instance per worker thread.
 */
final class CollectingField implements LightField {

    private final LevelAccessor level;
    private final PassMap opacityMemo = new PassMap(1 << 12);
    private final PassMap lit = new PassMap(1 << 12);
    private final BlockPos.MutableBlockPos scratchPos = new BlockPos.MutableBlockPos();

    CollectingField(LevelAccessor level) {
        this.level = level;
    }

    @Override
    public void beginPass() {
        opacityMemo.clear();
        lit.clear();
    }

    @Override
    public void endPass() {
        opacityMemo.trim();
    }

    @Override
    public int opacityAt(int x, int y, int z) {
        long key = PosKey.pack(x, y, z);
        long cached = opacityMemo.get(key, -1L);
        if (cached >= 0)
            return (int) cached;

        scratchPos.set(x, y, z);
        int opacity = level.getBlockState(scratchPos).getLightDampening();
        opacity = Math.max(0, Math.min(MAX_OPACITY, opacity));
        opacityMemo.put(key, opacity);
        return opacity;
    }

    @Override
    public void raise(int x, int y, int z, int id, int level) {
        long key = PosKey.pack(x, y, z);
        if (level > lit.get(key, 0L))
            lit.put(key, level);
    }

    /** Number of cells the last pass lit. */
    int count() {
        return lit.size();
    }

    /** Copies the lit cells (and their brightness) out; returns how many. */
    int drain(long[] keys, int[] levels) {
        int n = lit.collectKeys(keys);
        for (int i = 0; i < n; i++)
            levels[i] = (int) lit.get(keys[i], 0L);
        lit.trim();
        return n;
    }
}
