package me.mrhikmen.colorlight.client.compat.lod;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngine;
import me.mrhikmen.colorlight.client.core.light.util.PosKey;

import net.minecraft.client.multiplayer.ClientLevel;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Coarse, long-lived coloured light for terrain that is too far away for the engine to know: the distant LODs
 * (Voxy). The engine only holds light for the chunks the client has loaded, so what it knows is written down here
 * while a chunk is near, and kept when the chunk is gone.
 * <p>
 * <b>Levels</b>, like Voxy's own LOD levels: level 0 divides the world into 8x8 block columns, level 1 into 16x16,
 * level 2 into 32x32 and so on ({@link #LEVELS} levels). A cell holds the colour of the light at the <i>top</i> of
 * its column: the visible side of a LOD is its top, so the highest lit spot wins (then the brighter one). A coarser
 * cell takes the winner out of its four children, so the priority is the same on every level.
 * <p>
 * <b>Full tier</b>: in addition to the 8x8-and-up chain above, a separate, finer 4x4 grid ({@link #fullLevel}) is
 * kept for the band right around the camera, so two light sources a few blocks apart inside the same chunk still
 * show their own colour instead of being merged into one "winner" cell. It is not part of the coarsening chain
 * (nothing coarser is built from it); it is a leaf tier used on its own, the same way level 0 is.
 * <p>
 * <b>Cell value</b> (a long, so comparing two values compares height first, then brightness):
 * {@code (blockY + 2048) << 32 | brightness << 24 | 0xBBGGRR}. 0 means "no light".
 * <p>
 * Only the client thread touches this.
 */
public final class LodLightMap {

    public static final int LEVELS = 5;
    /** Level 0 cells are {@code 1 << BASE_SHIFT} = 8 blocks wide; level k cells are {@code 8 << k}. */
    public static final int BASE_SHIFT = 3;
    /** The full tier's cells are {@code 1 << FULL_SHIFT} = 4 blocks wide. */
    public static final int FULL_SHIFT = 2;

    private static final int BANDS = 64;          // 16-block high slices of a column, sectionY + BAND_OFFSET
    private static final int BAND_OFFSET = 16;
    private static final int MIN_BRIGHTNESS = 8;  // out of 255; fainter light is not worth a colour
    private static final long Y_BIAS = 2048;

    private static final int FILE_MAGIC = 0xC01A11D0;
    private static final int FILE_VERSION = 2;

    private final Long2ObjectOpenHashMap<long[]> columns = new Long2ObjectOpenHashMap<>();
    private final Long2LongOpenHashMap[] levels = new Long2LongOpenHashMap[LEVELS];
    private final long[] levelVersion = new long[LEVELS];

    private final Long2ObjectOpenHashMap<long[]> fullColumns = new Long2ObjectOpenHashMap<>();
    private final Long2LongOpenHashMap fullLevel = new Long2LongOpenHashMap();
    private long fullVersion;

    private final LongOpenHashSet pending = new LongOpenHashSet();
    private final int[] cells = new int[16 * 16 * 16];
    private boolean unsaved;

    public LodLightMap() {
        for (int i = 0; i < LEVELS; i++) {
            levels[i] = new Long2LongOpenHashMap();
            levels[i].defaultReturnValue(0L);
        }
        fullLevel.defaultReturnValue(0L);
    }

    // ------------------------------------------------------------------------------------------------------
    // Keeping it up to date
    // ------------------------------------------------------------------------------------------------------

    public void markDirty(long sectionKey) {
        pending.add(sectionKey);
    }

    public void markDirty(long[] sectionKeys) {
        for (long key : sectionKeys)
            pending.add(key);
    }

    /** Reads changed sections from the engine until the time budget is used up. */
    public void tick(ColorLightEngine engine, ClientLevel level, long budgetNanos) {
        if (pending.isEmpty())
            return;

        long start = System.nanoTime();
        int done = 0;
        LongIterator it = pending.iterator();
        while (it.hasNext() && done < 64 && System.nanoTime() - start < budgetNanos) {
            long key = it.nextLong();
            it.remove();
            capture(engine, level, PosKey.x(key), PosKey.y(key), PosKey.z(key));
            done++;
        }
    }

    private void capture(ColorLightEngine engine, ClientLevel level, int sx, int sy, int sz) {
        // A chunk that is no longer loaded has no light in the engine: that is not "the light went out".
        if (!level.hasChunk(sx, sz))
            return;

        // static light only: a torch in somebody's hand must not stay behind in the far map
        boolean lit = engine.copyStaticSection(sx, sy, sz, cells);
        int band = Math.max(0, Math.min(BANDS - 1, sy + BAND_OFFSET));

        // Coarse (8x8) cells: feed the level0..4 coarsening chain used for the mid/far bands.
        for (int sub = 0; sub < 4; sub++) {
            int x0 = (sub & 1) * 8;
            int z0 = (sub >> 1) * 8;
            long value = lit ? bestOfRegion(sy, x0, z0, 8) : 0L;
            setBand((sx << 1) + (sub & 1), (sz << 1) + (sub >> 1), band, value);
        }

        // Fine (4x4) cells: the standalone "full" tier used right around the camera, so nearby sources in the
        // same chunk keep their own colour instead of being merged into one 8x8 winner.
        for (int sub = 0; sub < 16; sub++) {
            int x0 = (sub & 3) * 4;
            int z0 = (sub >> 2) * 4;
            long value = lit ? bestOfRegion(sy, x0, z0, 4) : 0L;
            setFullBand((sx << 2) + (sub & 3), (sz << 2) + (sub >> 2), band, value);
        }
    }

    /** The winner of a {@code size}x{@code size} (x, z) by 16 (y) block of a section: highest of the clearly lit
     *  cells, then brightest. {@code size} must divide 16 (4 or 8). */
    private long bestOfRegion(int sy, int x0, int z0, int size) {
        int maxBrightness = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < size; z++) {
                int row = (y << 8) | ((z0 + z) << 4) + x0;
                for (int x = 0; x < size; x++) {
                    int v = cells[row + x];
                    if (v != 0)
                        maxBrightness = Math.max(maxBrightness, brightness(v));
                }
            }
        }
        if (maxBrightness < MIN_BRIGHTNESS)
            return 0L;

        // "clearly lit": the faint rim of a flood has a noisy hue and must not outrank the real light below it
        int threshold = Math.max(MIN_BRIGHTNESS, maxBrightness / 4);
        for (int y = 15; y >= 0; y--) {
            int bestBrightness = 0;
            int bestValue = 0;
            for (int z = 0; z < size; z++) {
                int row = (y << 8) | ((z0 + z) << 4) + x0;
                for (int x = 0; x < size; x++) {
                    int v = cells[row + x];
                    int b = brightness(v);
                    if (b >= threshold && b > bestBrightness) {
                        bestBrightness = b;
                        bestValue = v;
                    }
                }
            }
            if (bestBrightness > 0)
                return (((long) (sy * 16 + y) + Y_BIAS) << 32) | ((long) bestBrightness << 24) | (bestValue & 0xFFFFFFL);
        }
        return 0L;
    }

    private static int brightness(int rgb) {
        return Math.max(rgb & 0xFF, Math.max((rgb >> 8) & 0xFF, (rgb >> 16) & 0xFF));
    }

    private void setBand(int cx, int cz, int band, long value) {
        long key = cellKey(cx, cz);
        long[] column = columns.get(key);
        if (column == null) {
            if (value == 0L)
                return;
            column = new long[BANDS];
            columns.put(key, column);
        }
        if (column[band] == value)
            return;

        column[band] = value;
        unsaved = true;

        long top = 0L;
        for (long v : column)
            top = Math.max(top, v);
        if (top == 0L)
            columns.remove(key);

        setCell(cx, cz, top);
    }

    /** Writes a level-0 cell and recomputes the cells above it for as long as something changes. */
    private void setCell(int cx, int cz, long value) {
        for (int k = 0; k < LEVELS; k++) {
            long key = cellKey(cx, cz);
            if (levels[k].get(key) == value)
                return;

            if (value == 0L)
                levels[k].remove(key);
            else
                levels[k].put(key, value);
            levelVersion[k]++;

            if (k == LEVELS - 1)
                return;

            cx >>= 1;
            cz >>= 1;
            long best = 0L;
            for (int dz = 0; dz < 2; dz++) {
                for (int dx = 0; dx < 2; dx++)
                    best = Math.max(best, levels[k].get(cellKey((cx << 1) + dx, (cz << 1) + dz)));
            }
            value = best;
        }
    }

    /** Same idea as {@link #setBand}, but for the standalone 4x4 full tier: a leaf, so no cascading upward. */
    private void setFullBand(int cx, int cz, int band, long value) {
        long key = cellKey(cx, cz);
        long[] column = fullColumns.get(key);
        if (column == null) {
            if (value == 0L)
                return;
            column = new long[BANDS];
            fullColumns.put(key, column);
        }
        if (column[band] == value)
            return;

        column[band] = value;
        unsaved = true;

        long top = 0L;
        for (long v : column)
            top = Math.max(top, v);
        if (top == 0L)
            fullColumns.remove(key);

        setFullCell(cx, cz, top);
    }

    private void setFullCell(int cx, int cz, long value) {
        long key = cellKey(cx, cz);
        if (fullLevel.get(key) == value)
            return;
        if (value == 0L)
            fullLevel.remove(key);
        else
            fullLevel.put(key, value);
        fullVersion++;
    }

    // ------------------------------------------------------------------------------------------------------
    // Reading it (for the GPU copy)
    // ------------------------------------------------------------------------------------------------------

    /** Changes whenever the given level changed. */
    public long version(int level) {
        return levelVersion[level];
    }

    /** Changes whenever the full tier changed. */
    public long fullVersion() {
        return fullVersion;
    }

    /**
     * Writes the window of {@code size x size} cells around the camera cell into {@code out} as toroidal grid
     * ({@code (x & (size-1)) | (z & (size-1)) * size}); every cell is 0x00BBGGRR, or 0 for "no light". Cells farther
     * than {@code size / 2 - 1} from the camera are left out (the shader skips them too).
     */
    public void fillGrid(int level, int camCellX, int camCellZ, int size, int[] out) {
        fillGridFrom(levels[level], camCellX, camCellZ, size, out);
    }

    /** Same as {@link #fillGrid}, but for the standalone 4x4 full tier. */
    public void fillFullGrid(int camCellX, int camCellZ, int size, int[] out) {
        fillGridFrom(fullLevel, camCellX, camCellZ, size, out);
    }

    private void fillGridFrom(Long2LongOpenHashMap map, int camCellX, int camCellZ, int size, int[] out) {
        Arrays.fill(out, 0, size * size, 0);
        int half = size / 2 - 1;
        int mask = size - 1;
        int shift = Integer.numberOfTrailingZeros(size);

        if (map.size() <= size * size) {
            for (Long2LongMap.Entry e : map.long2LongEntrySet()) {
                long key = e.getLongKey();
                int cx = cellX(key);
                int cz = cellZ(key);
                if (Math.abs(cx - camCellX) <= half && Math.abs(cz - camCellZ) <= half)
                    out[(cx & mask) | ((cz & mask) << shift)] = (int) (e.getLongValue() & 0xFFFFFFL);
            }
        } else {
            for (int dz = -half; dz <= half; dz++) {
                for (int dx = -half; dx <= half; dx++) {
                    int cx = camCellX + dx;
                    int cz = camCellZ + dz;
                    long v = map.get(cellKey(cx, cz));
                    if (v != 0L)
                        out[(cx & mask) | ((cz & mask) << shift)] = (int) (v & 0xFFFFFFL);
                }
            }
        }
    }

    public int columnCount() {
        return columns.size();
    }

    // ------------------------------------------------------------------------------------------------------
    // Persistence: only the columns are stored, the levels are rebuilt from them
    // ------------------------------------------------------------------------------------------------------

    public boolean hasUnsavedChanges() {
        return unsaved;
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp))))) {
            out.writeInt(FILE_MAGIC);
            out.writeInt(FILE_VERSION);
            writeColumns(out, columns);
            writeColumns(out, fullColumns);
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        unsaved = false;
    }

    private void writeColumns(DataOutputStream out, Long2ObjectOpenHashMap<long[]> map) throws IOException {
        out.writeInt(map.size());
        for (Long2ObjectOpenHashMap.Entry<long[]> e : map.long2ObjectEntrySet()) {
            long[] column = e.getValue();
            int count = 0;
            for (long v : column)
                if (v != 0L)
                    count++;
            out.writeLong(e.getLongKey());
            out.writeByte(count);
            for (int band = 0; band < BANDS; band++) {
                if (column[band] != 0L) {
                    out.writeByte(band);
                    out.writeLong(column[band]);
                }
            }
        }
    }

    public void load(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new BufferedInputStream(Files.newInputStream(file))))) {
            if (in.readInt() != FILE_MAGIC)
                return;
            int version = in.readInt();
            if (version != 1 && version != 2)
                return;
            readColumns(in, this::setBand);
            if (version >= 2)
                readColumns(in, this::setFullBand);
        }
        unsaved = false;
    }

    private void readColumns(DataInputStream in, BandSetter setter) throws IOException {
        int count = in.readInt();
        for (int i = 0; i < count; i++) {
            long key = in.readLong();
            int bands = in.readUnsignedByte();
            for (int b = 0; b < bands; b++) {
                int band = in.readUnsignedByte();
                long value = in.readLong();
                if (band < BANDS)
                    setter.set(cellX(key), cellZ(key), band, value);
            }
        }
    }

    @FunctionalInterface
    private interface BandSetter {
        void set(int cx, int cz, int band, long value);
    }

    // ------------------------------------------------------------------------------------------------------

    private static long cellKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static int cellX(long key) {
        return (int) (key >> 32);
    }

    private static int cellZ(long key) {
        return (int) key;
    }
}
