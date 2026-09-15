package me.mrhikmen.colorlight.core.light.engine;

final class QueueCapacity {

    static int nextPowerOfTwo(int requested) {
        int value = Math.max(4, requested);
        return Integer.highestOneBit(value - 1) << 1;
    }

    private QueueCapacity() {
    }
}
