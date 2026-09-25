package me.mrhikmen.colorlight.client.core.light.util;

public final class IntQueue {

    private int[] data;
    private int mask;
    private int head;
    private int size;

    public IntQueue(int initialCapacity) {
        int capacity = QueueCapacity.nextPowerOfTwo(initialCapacity);
        this.data = new int[capacity];
        this.mask = capacity - 1;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public void clear() {
        head = 0;
        size = 0;
    }

    public int size() {
        return size;
    }

    public void add(int value) {
        if (size == data.length)
            grow();
        data[(head + size) & mask] = value;
        size++;
    }

    public int poll() {
        int value = data[head];
        head = (head + 1) & mask;
        size--;
        return value;
    }

    private void grow() {
        int newCapacity = data.length << 1;
        int[] newData = new int[newCapacity];
        for (int i = 0; i < size; i++) {
            newData[i] = data[(head + i) & mask];
        }
        data = newData;
        mask = newCapacity - 1;
        head = 0;
    }
}
