package me.mrhikmen.colorlight.client.core.light.engine;

import me.mrhikmen.colorlight.client.core.light.propagation.LightField;
import me.mrhikmen.colorlight.client.core.light.util.PassMap;
import me.mrhikmen.colorlight.client.core.light.util.PosKey;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;

import static me.mrhikmen.colorlight.client.core.light.propagation.PropagationTables.MAX_OPACITY;

/**
 * The {@link LightField} both propagation models spread through: block opacity comes from the level (looked up once
 * per cell per pass), values live in a {@link LightStorage}.
 * <ul>
 *     <li>For the world's static light it wraps the engine's storage and reports every stored change to a listener
 *     (that is how the engine learns which render sections became dirty).</li>
 *     <li>For a moving light it has <b>no storage</b>: nothing pre-exists and nothing is kept, the caller reads
 *     the result from the propagator's working values.</li>
 * </ul>
 * Both uses are deliberately the same class rather than two implementations of the interface: the propagators'
 * inner loops then only ever see one receiver type, which keeps the JIT able to inline them (with two
 * implementations live at once, SMOOTH ran about 10% slower).
 * <p>
 * Holds scratch memory: single-threaded, one instance per user.
 */
final class WorldLightField implements LightField {

    private final LevelAccessor level;
    /** Null for a light that keeps no stored values. */
    private final LightStorage storage;
    /** Null if nobody wants to know about stored changes. */
    private final DynamicLightLayer.CellListener onStored;

    private final PassMap opacityMemo = new PassMap(1 << 12);
    private final BlockPos.MutableBlockPos scratchPos = new BlockPos.MutableBlockPos();

    WorldLightField(LevelAccessor level, LightStorage storage, DynamicLightLayer.CellListener onStored) {
        this.level = level;
        this.storage = storage;
        this.onStored = onStored;
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
    public int get(int x, int y, int z) {
        return storage == null ? 0 : storage.get(x, y, z);
    }

    @Override
    public void set(int x, int y, int z, int value) {
        if (storage == null)
            return;

        storage.put(x, y, z, value);
        if (onStored != null)
            onStored.cellChanged(x, y, z);
    }
}
