package me.mrhikmen.colorlight.core.light;

import net.minecraft.core.BlockPos;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerArray;

public final class LightStorage {
    public static final int UNSET = Integer.MIN_VALUE;

    private static final int SECTION_BITS = 4;
    private static final int SECTION_SIZE = 1 << SECTION_BITS;
    private static final int LOCAL_MASK = SECTION_SIZE - 1;
    private static final int SECTION_VOLUME = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;

    private final ConcurrentHashMap<Long, AtomicIntegerArray> sections = new ConcurrentHashMap<>();

    public int get(long blockKey) {
        int raw = getRaw(blockKey);
        return raw == UNSET ? ColorLightUtil.EMPTY : raw;
    }

    public int getRaw(long blockKey) {
        BlockPos pos = BlockPos.of(blockKey);
        AtomicIntegerArray section = sections.get(sectionKey(pos));
        if (section == null)
            return UNSET;
        return section.get(localIndex(pos));
    }

    public void put(long blockKey, int value) {
        BlockPos pos = BlockPos.of(blockKey);
        AtomicIntegerArray section = sections.computeIfAbsent(sectionKey(pos), k -> newEmptySection());
        section.set(localIndex(pos), value);
    }

    public void remove(long blockKey) {
        BlockPos pos = BlockPos.of(blockKey);
        AtomicIntegerArray section = sections.get(sectionKey(pos));
        if (section != null)
            section.set(localIndex(pos), UNSET);
    }

    public void clear() {
        sections.clear();
    }

    private static AtomicIntegerArray newEmptySection() {
        AtomicIntegerArray section = new AtomicIntegerArray(SECTION_VOLUME);
        for (int i = 0; i < SECTION_VOLUME; i++) {
            section.set(i, UNSET);
        }
        return section;
    }

    private static long sectionKey(BlockPos pos) {
        return BlockPos.asLong(pos.getX() >> SECTION_BITS, pos.getY() >> SECTION_BITS, pos.getZ() >> SECTION_BITS);
    }

    private static int localIndex(BlockPos pos) {
        int lx = pos.getX() & LOCAL_MASK;
        int ly = pos.getY() & LOCAL_MASK;
        int lz = pos.getZ() & LOCAL_MASK;
        return (lx << (SECTION_BITS * 2)) | (ly << SECTION_BITS) | lz;
    }
}
