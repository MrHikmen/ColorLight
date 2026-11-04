package me.mrhikmen.colorlight.client.core.light.engine;

import me.mrhikmen.colorlight.client.core.light.propagation.LightField;
import me.mrhikmen.colorlight.client.core.light.util.PassMap;
import me.mrhikmen.colorlight.client.core.light.util.PosKey;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;

import static me.mrhikmen.colorlight.client.core.light.propagation.PropagationTables.MAX_OPACITY;

/**
 * A flood that writes nothing: it reads block opacity from the level like {@link WorldLightField} does, and records
 * the raises instead of applying them. The recorded raises are replayed into the real storage later, in order, so a
 * flood can run on another thread while the engine lock is held. Holds scratch memory: one instance per flood.
 */
final class DeferredLightField implements LightField {

    private final LevelAccessor level;
    private final PassMap opacityMemo = new PassMap(1 << 12);
    private final BlockPos.MutableBlockPos scratchPos = new BlockPos.MutableBlockPos();

    private long[] keys = new long[256];
    private int[] ids = new int[256];
    private int[] levels = new int[256];
    private int size;

    DeferredLightField(LevelAccessor level) {
        this.level = level;
    }

    @Override
    public void beginPass() {
        opacityMemo.clear();
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
        if (size == keys.length) {
            int capacity = size * 2;
            keys = java.util.Arrays.copyOf(keys, capacity);
            ids = java.util.Arrays.copyOf(ids, capacity);
            levels = java.util.Arrays.copyOf(levels, capacity);
        }
        keys[size] = PosKey.pack(x, y, z);
        ids[size] = id;
        levels[size] = level;
        size++;
    }

    /** Applies the recorded raises to {@code target}, in the order they were made. */
    void replayInto(WorldLightField target) {
        for (int i = 0; i < size; i++) {
            long key = keys[i];
            target.raise(PosKey.x(key), PosKey.y(key), PosKey.z(key), ids[i], levels[i]);
        }
    }
}
