package me.mrhikmen.colorlight.client.core.light.util;

/**
 * Primitive long ring buffer used as a BFS work queue for packed block-position
 * keys. Same power-of-two/bitmask trick as {@link IntQueue}, since this is the
 * queue every propagation and darkening pass drives.
 */
public final class LongQueue {

    private long[] data;
    private int mask;
    private int head;
    private int size;

    public LongQueue(int initialCapacity) {
        int capacity = QueueCapacity.nextPowerOfTwo(initialCapacity);
        this.data = new long[capacity];
        this.mask = capacity - 1;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** Empties the queue but keeps its backing array, so it can be reused between passes. */
    public void clear() {
        head = 0;
        size = 0;
    }

    public int size() {
        return size;
    }

    public void add(long value) {
        if (size == data.length)
            grow();
        data[(head + size) & mask] = value;
        size++;
    }

    public long poll() {
        long value = data[head];
        head = (head + 1) & mask;
        size--;
        return value;
    }

    private void grow() {
        int newCapacity = data.length << 1;
        long[] newData = new long[newCapacity];
        for (int i = 0; i < size; i++) {
            newData[i] = data[(head + i) & mask];
        }
        data = newData;
        mask = newCapacity - 1;
        head = 0;
    }
}
