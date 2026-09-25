package me.mrhikmen.colorlight.client.core.light.engine;

import me.mrhikmen.colorlight.client.core.light.util.PosKey;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable view of every static light source at one moment. The engine hands out the same
 * instance until its source set changes, so per-frame/per-second consumers (LOD overlay, daylight
 * refresh) no longer rebuild and copy a list of every source in the world each time they run.
 */
public final class SourceSnapshot {

    private final long[] keys;
    private final int[] colors;
    private final int version;
    private volatile List<BlockPos> positions;

    SourceSnapshot(long[] keys, int[] colors, int version) {
        this.keys = keys;
        this.colors = colors;
        this.version = version;
    }

    public int version() {
        return version;
    }

    public int size() {
        return keys.length;
    }

    public int x(int i) {
        return PosKey.x(keys[i]);
    }

    public int y(int i) {
        return PosKey.y(keys[i]);
    }

    public int z(int i) {
        return PosKey.z(keys[i]);
    }

    /** The source's own packed colour (before any spreading), see {@code ColorLightUtil}. */
    public int color(int i) {
        return colors[i];
    }

    /** Lazily built, cached, unmodifiable. */
    public List<BlockPos> positions() {
        List<BlockPos> result = positions;
        if (result == null) {
            List<BlockPos> list = new ArrayList<>(keys.length);
            for (long key : keys) {
                list.add(new BlockPos(PosKey.x(key), PosKey.y(key), PosKey.z(key)));
            }
            result = Collections.unmodifiableList(list);
            positions = result;
        }
        return result;
    }
}
