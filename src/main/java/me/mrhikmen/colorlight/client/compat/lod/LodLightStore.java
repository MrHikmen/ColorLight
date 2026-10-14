package me.mrhikmen.colorlight.client.compat.lod;

import me.mrhikmen.colorlight.client.ColorLightClient;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The coloured light sources of one world dimension, shared by every LOD mod (Voxy, Distant Horizons): one list per
 * 32x32-block column ({@code x, y, z, 0xRRBBGGRR} per source, the top byte being the source's own reach in blocks, or
 * 0 for "unknown"), one save file for all of it, and the code that turns the lists into the tables the shaders read.
 * <p>
 * The mods' scanners ({@code VoxyLightCache}, {@code DhLightCache}) only <i>find</i> sources and put them in
 * {@link #columns}; they know nothing about files or GPU tables. Each mod's renderer bridge asks for its own
 * {@link Channel}, which builds the tables for that mod's shader on a background thread.
 * <p>
 * Contains no class of any LOD mod, so it is safe to load whether or not they are installed.
 */
public final class LodLightStore {

    /** 16x16-block cells per side of the toroidal "cell -> sources" table. */
    public static final int GRID_CELLS = 512;
    /** Most sources uploaded at once (nearest columns first). */
    public static final int MAX_SOURCES = 131072;
    /** Ints per side of the coarse table (32x32-block cells). */
    public static final int COARSE_SIDE = 256;
    /** The camera may move this many columns before a table is rebuilt (the shaders keep this margin). */
    public static final int DRIFT_COLS = 8;

    private static final int FILE_MAGIC = 0x434C4C44; // 'CLLD'
    private static final int FILE_FORMAT = 1;
    private static final int MAX_INTS_PER_COLUMN = 512 * 4;
    private static final int[] NO_SOURCES = new int[0];

    /** 32-block column key (see {@link #colKey}) -> flat sources. Present (even empty) = scanned. */
    public final ConcurrentHashMap<Long, int[]> columns = new ConcurrentHashMap<>();
    public final AtomicLong version = new AtomicLong();
    public final AtomicBoolean unsaved = new AtomicBoolean();
    private volatile boolean loaded;

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ColorLight LOD Light IO");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final List<Channel> channels = new ArrayList<>();

    public static long colKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /** False until the save file (if any) has been read: scanners wait for it so they do not redo saved columns. */
    public boolean loaded() {
        return loaded;
    }

    /** Light sources currently known, over every column. */
    public int sourceCount() {
        long n = 0;
        for (int[] a : columns.values())
            n += a.length / 4;
        return (int) Math.min(n, Integer.MAX_VALUE);
    }

    // ------------------------------------------------------------------------------------------------------
    // Persistence (one file per world and dimension, shared by all LOD mods)
    // ------------------------------------------------------------------------------------------------------

    public boolean hasUnsavedChanges() {
        return unsaved.get();
    }

    /** Writes every column's list (gzip, atomic replace). Safe from any thread. */
    public void save(Path file) throws IOException {
        unsaved.set(false); // cleared first: a change made while writing marks it again
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Object[] entries = columns.entrySet().toArray();
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp))))) {
                out.writeInt(FILE_MAGIC);
                out.writeInt(FILE_FORMAT);
                out.writeInt(entries.length);
                for (Object o : entries) {
                    @SuppressWarnings("unchecked")
                    Map.Entry<Long, int[]> e = (Map.Entry<Long, int[]>) o;
                    int[] a = e.getValue();
                    out.writeLong(e.getKey());
                    out.writeInt(a.length);
                    for (int x : a)
                        out.writeInt(x);
                }
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            unsaved.set(true);
            throw e;
        }
    }

    /** Adds the columns of a saved file (columns already read this session win). */
    public void load(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new BufferedInputStream(Files.newInputStream(file))))) {
            if (in.readInt() != FILE_MAGIC || in.readInt() != FILE_FORMAT)
                return;
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                long key = in.readLong();
                int len = in.readInt();
                if (len < 0 || len > MAX_INTS_PER_COLUMN || (len & 3) != 0)
                    throw new IOException("bad column");
                int[] a = len == 0 ? NO_SOURCES : new int[len];
                for (int j = 0; j < len; j++)
                    a[j] = in.readInt();
                columns.putIfAbsent(key, a);
            }
            version.incrementAndGet();
        }
    }

    public void saveAsync(Path file) {
        try {
            io.execute(() -> {
                try {
                    save(file);
                } catch (Throwable t) {
                    ColorLightClient.LOGGER.warn("[ColorLight] Could not save far light file {}: {}", file, t.toString());
                }
            });
        } catch (RuntimeException shutDown) {
        }
    }

    /** Reads the file in the background; {@link #loaded()} turns true afterwards (also when there is no file). */
    public void loadAsync(Path fileOrNull) {
        if (fileOrNull == null || !Files.isRegularFile(fileOrNull)) {
            loaded = true;
            return;
        }
        try {
            io.execute(() -> {
                try {
                    load(fileOrNull);
                    ColorLightClient.LOGGER.info("[ColorLight] Loaded far light from {} ({} columns)", fileOrNull, columns.size());
                } catch (Throwable t) {
                    ColorLightClient.LOGGER.warn("[ColorLight] Could not read far light file {}: {}", fileOrNull, t.toString());
                } finally {
                    loaded = true;
                }
            });
        } catch (RuntimeException shutDown) {
            loaded = true;
        }
    }

    public void shutdown() {
        io.shutdownNow();
        synchronized (channels) {
            for (Channel c : channels)
                c.shutdown();
            channels.clear();
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // GPU tables
    // ------------------------------------------------------------------------------------------------------

    /**
     * @param windowCols radius, in 32-block columns, of what is put in the table
     * @param perCellCap most sources kept per 16x16-block cell (what that mod's shader reads at most)
     */
    public Channel newChannel(int windowCols, int perCellCap) {
        Channel c = new Channel(windowCols, perCellCap);
        synchronized (channels) {
            channels.add(c);
        }
        return c;
    }

    /**
     * One consumer's table: the render thread asks ({@link #requestFill}), a filler thread builds it, the render
     * thread uploads it ({@link #pollFilled}, {@link #cellBuffer}, ...) and releases it. Layout (ints): cell table
     * (first source, count) per 16x16-block cell, the sources of all cells one after the other, and a coarse table
     * of 32-block cells flagged when a source is in or next to them.
     */
    public final class Channel {
        private static final int FILL_IDLE = 0, FILL_REQUESTED = 1, FILL_READY = 2;

        private final int windowCols;
        private final int perCellCap;
        private final int[] ringDx;
        private final int[] ringDz;
        private final AtomicInteger fillState = new AtomicInteger(FILL_IDLE);
        private final long[] fillStamp = new long[4];
        private final ExecutorService filler = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ColorLight LOD Light Table");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        private IntBuffer cellBuf;
        private IntBuffer srcBuf;
        private IntBuffer coarseBuf;
        private int[] cellCount;
        private int[] cellStart;
        private int[] cellFill;

        private Channel(int windowCols, int perCellCap) {
            this.windowCols = windowCols;
            this.perCellCap = perCellCap;
            int r = windowCols;
            List<long[]> all = new ArrayList<>();
            for (int dz = -r; dz <= r; dz++)
                for (int dx = -r; dx <= r; dx++)
                    all.add(new long[]{(long) dx * dx + (long) dz * dz, dx, dz});
            all.sort((a, b) -> Long.compare(a[0], b[0]));
            ringDx = new int[all.size()];
            ringDz = new int[all.size()];
            for (int i = 0; i < all.size(); i++) {
                ringDx[i] = (int) all.get(i)[1];
                ringDz[i] = (int) all.get(i)[2];
            }
        }

        public long version() {
            return version.get();
        }

        /** Asks the filler thread to build the table around the camera. Does nothing while one is pending. Cheap. */
        public void requestFill(int camBlockX, int camBlockZ) {
            if (!fillState.compareAndSet(FILL_IDLE, FILL_REQUESTED))
                return;
            long v = version.get();
            try {
                filler.execute(() -> {
                    try {
                        build(v, camBlockX >> 5, camBlockZ >> 5);
                        fillState.set(FILL_READY);
                    } catch (Throwable t) {
                        fillState.set(FILL_IDLE);
                    }
                });
            } catch (RuntimeException shutDown) {
                fillState.set(FILL_IDLE);
            }
        }

        /** @return true if a finished table is waiting; {@code stamp} gets {version, camCol x, camCol z, source count}. */
        public boolean pollFilled(long[] stamp) {
            if (fillState.get() != FILL_READY)
                return false;
            System.arraycopy(fillStamp, 0, stamp, 0, 4);
            return true;
        }

        public IntBuffer cellBuffer() {
            cellBuf.clear();
            return cellBuf;
        }

        /** Positioned at 0 with its limit at {@code count * 4} ints. */
        public IntBuffer sourceBuffer(int count) {
            srcBuf.clear();
            srcBuf.limit(count * 4);
            return srcBuf;
        }

        public IntBuffer coarseBuffer() {
            coarseBuf.clear();
            return coarseBuf;
        }

        public void releaseFilled() {
            fillState.set(FILL_IDLE);
        }

        void shutdown() {
            filler.shutdownNow();
        }

        private void build(long v, int camCx, int camCz) {
            int cellTotal = GRID_CELLS * GRID_CELLS;
            if (cellBuf == null) {
                coarseBuf = ByteBuffer.allocateDirect(COARSE_SIDE * COARSE_SIDE * Integer.BYTES).order(ByteOrder.nativeOrder()).asIntBuffer();
                cellBuf = ByteBuffer.allocateDirect(cellTotal * 2 * Integer.BYTES).order(ByteOrder.nativeOrder()).asIntBuffer();
                srcBuf = ByteBuffer.allocateDirect(MAX_SOURCES * 4 * Integer.BYTES).order(ByteOrder.nativeOrder()).asIntBuffer();
                cellCount = new int[cellTotal];
                cellStart = new int[cellTotal];
                cellFill = new int[cellTotal];
            }
            cellBuf.clear();
            srcBuf.clear();
            Arrays.fill(cellCount, 0);

            // pass 1: how many sources each cell gets (nearest columns first, per-cell and total caps)
            int total = 0;
            int lastColumn = ringDx.length;
            for (int i = 0; i < ringDx.length && total < MAX_SOURCES; i++) {
                int[] src = columns.get(colKey(camCx + ringDx[i], camCz + ringDz[i]));
                if (src == null)
                    continue;
                for (int j = 0; j < src.length && total < MAX_SOURCES; j += 4) {
                    int cell = cellIndex(src[j], src[j + 2]);
                    if (cellCount[cell] < perCellCap) {
                        cellCount[cell]++;
                        total++;
                    }
                }
                lastColumn = i;
            }

            // prefix sums -> table
            int running = 0;
            for (int c = 0; c < cellTotal; c++) {
                cellStart[c] = running;
                cellFill[c] = 0;
                cellBuf.put(c * 2, running);
                cellBuf.put(c * 2 + 1, cellCount[c]);
                running += cellCount[c];
            }

            // pass 2: the same walk, now writing the sources
            for (int i = 0; i <= lastColumn && i < ringDx.length; i++) {
                int[] src = columns.get(colKey(camCx + ringDx[i], camCz + ringDz[i]));
                if (src == null)
                    continue;
                for (int j = 0; j < src.length; j += 4) {
                    int cell = cellIndex(src[j], src[j + 2]);
                    if (cellFill[cell] >= cellCount[cell])
                        continue;
                    int at = (cellStart[cell] + cellFill[cell]++) * 4;
                    srcBuf.put(at, src[j]);
                    srcBuf.put(at + 1, src[j + 1]);
                    srcBuf.put(at + 2, src[j + 2]);
                    srcBuf.put(at + 3, src[j + 3]);
                }
            }

            // coarse flags: a 32-block cell is flagged if a source lies in it or in one of its 8 neighbours, so a
            // vertex shader needs a single read to know whether the fragment shader has anything to do near it
            for (int i = 0; i < COARSE_SIDE * COARSE_SIDE; i++)
                coarseBuf.put(i, 0);
            for (int i = 0; i < ringDx.length && i <= lastColumn; i++) {
                int cx = camCx + ringDx[i], cz = camCz + ringDz[i];
                int[] src = columns.get(colKey(cx, cz));
                if (src == null || src.length == 0)
                    continue;
                for (int dz = -1; dz <= 1; dz++)
                    for (int dx = -1; dx <= 1; dx++)
                        coarseBuf.put(((cx + dx) & (COARSE_SIDE - 1)) | (((cz + dz) & (COARSE_SIDE - 1)) * COARSE_SIDE), 1);
            }

            fillStamp[0] = v;
            fillStamp[1] = camCx;
            fillStamp[2] = camCz;
            fillStamp[3] = running;
        }

        private int cellIndex(int x, int z) {
            return ((x >> 4) & (GRID_CELLS - 1)) | (((z >> 4) & (GRID_CELLS - 1)) * GRID_CELLS);
        }
    }
}
