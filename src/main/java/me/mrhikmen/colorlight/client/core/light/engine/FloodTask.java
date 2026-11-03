package me.mrhikmen.colorlight.client.core.light.engine;

import java.util.List;
import net.minecraft.resources.Identifier;

/** One flood: the seeds {@code [from, to)} of one colour spread with one method. */
record FloodTask(Identifier method, int id, long[] keys, int[] levels, int from, int to) {

    int size() {
        return to - from;
    }

    /** Adds the seeds {@code [from, to)} as floods of at most {@code maxSeeds} seeds each. */
    static void addSlices(List<FloodTask> out, Identifier method, int id, long[] keys, int[] levels, int from, int to, int maxSeeds) {
        for (int start = from; start < to; start += maxSeeds)
            out.add(new FloodTask(method, id, keys, levels, start, Math.min(to, start + maxSeeds)));
    }
}
