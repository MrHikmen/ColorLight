package me.mrhikmen.colorlight.core.light.engine;

import me.mrhikmen.colorlight.core.light.color.ColorLightUtil;

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

    private record CachedSection(long sectionKey, AtomicIntegerArray section) {
    }

    private volatile CachedSection hotSection;

    public int get(long blockKey) {
        int raw = getRaw(blockKey);
        return raw == UNSET ? ColorLightUtil.EMPTY : raw;
    }

    public int getRaw(long blockKey) {
        BlockPos pos = BlockPos.of(blockKey);
        AtomicIntegerArray section = sectionFor(pos, false);
        if (section == null)
            return UNSET;
        return section.get(localIndex(pos));
    }

    public void put(long blockKey, int value) {
        BlockPos pos = BlockPos.of(blockKey);
        AtomicIntegerArray section = sectionFor(pos, true);
        section.set(localIndex(pos), value);
    }

    public void remove(long blockKey) {
        BlockPos pos = BlockPos.of(blockKey);
        AtomicIntegerArray section = sectionFor(pos, false);
        if (section != null)
            section.set(localIndex(pos), UNSET);
    }

    public void clear() {
        sections.clear();
        hotSection = null;
    }

    private AtomicIntegerArray sectionFor(BlockPos pos, boolean createIfAbsent) {
        long key = sectionKey(pos);

        CachedSection cached = hotSection;
        if (cached != null && cached.sectionKey() == key)
            return cached.section();

        AtomicIntegerArray section;
        if (createIfAbsent) {
            section = sections.computeIfAbsent(key, k -> newEmptySection());
        } else {
            section = sections.get(key);
            if (section == null)
                return null;
        }

        hotSection = new CachedSection(key, section);
        return section;
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
