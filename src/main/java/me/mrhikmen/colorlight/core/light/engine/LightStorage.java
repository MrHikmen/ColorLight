package me.mrhikmen.colorlight.core.light.engine;

import me.mrhikmen.colorlight.core.light.util.PosKey;

import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.LongConsumer;

/**
 * Sparse 3D int grid holding packed light colours, organised as chunk columns of 16x16x16 sections.
 * <p>
 * Compared to the previous {@code ConcurrentHashMap<Long, AtomicIntegerArray>} layout this:
 * <ul>
 *     <li>never boxes or allocates on {@link #get}/{@link #put} (columns are found by direct array
 *     indexing in a 128x128 ring, exactly like the vanilla client chunk cache);</li>
 *     <li>can drop a whole chunk column at once ({@link #removeColumn}) when a chunk unloads;</li>
 *     <li>keeps a per-section count of non-empty cells so the mesher can skip empty regions
 *     ({@link #isSectionEmpty}) without touching a single cell.</li>
 * </ul>
 * <b>Threading:</b> reads are lock-free and may run on any thread (mesh workers); a stale read is
 * harmless because every change also marks the affected sections dirty. All writes must be
 * serialised externally (the engine lock).
 */
public final class LightStorage {

    /** Only the low 24 bits carry colour; the top byte is free for engine flags. */
    public static final int RGB_MASK = 0x00FFFFFF;

    private static final int COLUMN_BITS = 7;
    private static final int COLUMN_WINDOW = 1 << COLUMN_BITS;
    private static final int COLUMN_MASK = COLUMN_WINDOW - 1;

    private static final int SECTION_Y_OFFSET = 128;
    private static final int SECTION_Y_COUNT = 256;

    private static final int SECTION_VOLUME = 16 * 16 * 16;

    private static final class Section {
        final int[] cells = new int[SECTION_VOLUME];
        volatile int nonEmpty;
    }

    private static final class Column {
        final int cx;
        final int cz;
        final AtomicReferenceArray<Section> sections = new AtomicReferenceArray<>(SECTION_Y_COUNT);

        Column(int cx, int cz) {
            this.cx = cx;
            this.cz = cz;
        }
    }

    private final AtomicReferenceArray<Column> columns = new AtomicReferenceArray<>(COLUMN_WINDOW * COLUMN_WINDOW);

    private static int columnIndex(int cx, int cz) {
        return (cx & COLUMN_MASK) | ((cz & COLUMN_MASK) << COLUMN_BITS);
    }

    private static int localIndex(int x, int y, int z) {
        return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
    }

    private Section sectionOrNull(int sx, int sy, int sz) {
        Column col = columns.get(columnIndex(sx, sz));
        if (col == null || col.cx != sx || col.cz != sz)
            return null;
        int idx = sy + SECTION_Y_OFFSET;
        if ((idx & ~(SECTION_Y_COUNT - 1)) != 0)
            return null;
        return col.sections.get(idx);
    }

    /** Raw stored value (colour + any engine flags); 0 when nothing has ever been written there. */
    public int get(int x, int y, int z) {
        Section s = sectionOrNull(x >> 4, y >> 4, z >> 4);
        return s == null ? 0 : s.cells[localIndex(x, y, z)];
    }

    public void put(int x, int y, int z, int value) {
        int sx = x >> 4;
        int sy = y >> 4;
        int sz = z >> 4;

        int yIdx = sy + SECTION_Y_OFFSET;
        if ((yIdx & ~(SECTION_Y_COUNT - 1)) != 0)
            return;

        int ci = columnIndex(sx, sz);
        Column col = columns.get(ci);
        if (col == null || col.cx != sx || col.cz != sz) {
            if (value == 0)
                return;
            col = new Column(sx, sz);
            columns.set(ci, col);
        }

        Section s = col.sections.get(yIdx);
        if (s == null) {
            if (value == 0)
                return;
            s = new Section();
            col.sections.set(yIdx, s);
        }

        int li = localIndex(x, y, z);
        int old = s.cells[li];
        if (old == value)
            return;
        s.cells[li] = value;

        boolean wasLit = (old & RGB_MASK) != 0;
        boolean isLit = (value & RGB_MASK) != 0;
        if (wasLit != isLit)
            s.nonEmpty += isLit ? 1 : -1;
    }

    /** True if the section holds no lit cell at all (also true if it was never allocated). */
    public boolean isSectionEmpty(int sx, int sy, int sz) {
        Section s = sectionOrNull(sx, sy, sz);
        return s == null || s.nonEmpty <= 0;
    }

    public void removeColumn(int cx, int cz) {
        int ci = columnIndex(cx, cz);
        Column col = columns.get(ci);
        if (col != null && col.cx == cx && col.cz == cz)
            columns.set(ci, null);
    }

    public void clear() {
        for (int i = 0; i < COLUMN_WINDOW * COLUMN_WINDOW; i++) {
            if (columns.get(i) != null)
                columns.set(i, null);
        }
    }

    /** Calls {@code consumer} with a packed {@link PosKey} of (sectionX, sectionY, sectionZ) for every section holding light. */
    public void forEachLitSection(LongConsumer consumer) {
        for (int i = 0; i < COLUMN_WINDOW * COLUMN_WINDOW; i++) {
            Column col = columns.get(i);
            if (col == null)
                continue;
            for (int yIdx = 0; yIdx < SECTION_Y_COUNT; yIdx++) {
                Section s = col.sections.get(yIdx);
                if (s != null && s.nonEmpty > 0)
                    consumer.accept(PosKey.pack(col.cx, yIdx - SECTION_Y_OFFSET, col.cz));
            }
        }
    }
}
