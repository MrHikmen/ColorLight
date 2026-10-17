package me.mrhikmen.colorlight.client.compat.sable;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import me.mrhikmen.colorlight.client.ColorLightClient;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngine;
import me.mrhikmen.colorlight.client.core.light.util.PosKey;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Light of Sable sub-levels for the GPU pipeline.
 * <p>
 * A sub-level's blocks live in a "plot" far away from the camera (about 20 million blocks out), so the camera-centred
 * {@link me.mrhikmen.colorlight.client.core.render.gpu.GpuLightVolume} never contains them. This class keeps one
 * <i>window</i> per sub-level instead: a 31x31x31 section region around an anchor in plot space, with its own
 * page table (32768 pages, a region of one shared data buffer) and slots in one shared pool. The layout of a window is
 * the one of the main volume with 5 bits per axis; the shader selects it with {@code u_CLPageBase} / {@code u_CLAnchor}
 * (see {@code colorlight_data.glsl}, {@code CL_PLOT}). The header buffer (enabled flag, tint settings) is the main
 * volume's.
 * <p>
 * Everything runs on the client thread, like the main volume.
 */
public final class SableLightVolume {

    public static final int MAX_WINDOWS = 16;
    public static final int WINDOW_PAGES = 1 << 15;
    /** The shader window is 15 sections each way; sections are kept within one less so nothing aliases. */
    public static final int KEEP = 14;
    private static final int SLOTS = 512;
    private static final int SECTION_CELLS = 4096;
    private static final int OPAQUE_BIT = 1 << 24;

    private static final int UNUSED_TICKS = 200;
    private static final int RESCAN_TICKS = 20;
    private static final int MAX_UPLOADS_PER_TICK = 48;
    private static final long UPLOAD_BUDGET_NANOS = 2_000_000L;

    public static final class Window {
        Object key;
        public int index;
        public int ax, ay, az; // anchor section
        final Long2IntOpenHashMap slotOf = new Long2IntOpenHashMap();
        final LongOpenHashSet pending = new LongOpenHashSet();
        long lastUsed;
        int rescan;

        Window() {
            slotOf.defaultReturnValue(-1);
        }

        boolean contains(int sx, int sy, int sz) {
            return Math.abs(sx - ax) <= KEEP && Math.abs(sy - ay) <= KEEP && Math.abs(sz - az) <= KEEP;
        }

        public int pageBase() {
            return index * WINDOW_PAGES;
        }
    }

    private static final Window[] WINDOWS = new Window[MAX_WINDOWS];
    private static int glData, glPool;
    private static int[] freeSlots;
    private static int freeCount;
    private static long tickNo;
    private static ColorLightEngine lastEngine;
    private static boolean fullWarned;

    private static final int[] cells = new int[SECTION_CELLS];
    private static ByteBuffer sectionBytes;
    private static ByteBuffer wordBytes;
    private static final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    // ------------------------------------------------------------------------------------------------------

    /** GL names of {data, pool}, or null before the first window. */
    public static int[] buffers() {
        return glData == 0 ? null : new int[]{glData, glPool};
    }

    private static void ensureBuffers() {
        if (glData != 0)
            return;

        sectionBytes = MemoryUtil.memAlloc(SECTION_CELLS * Integer.BYTES);
        wordBytes = MemoryUtil.memAlloc(Integer.BYTES);

        ByteBuffer pages = MemoryUtil.memAlloc(MAX_WINDOWS * WINDOW_PAGES * Integer.BYTES);
        try {
            MemoryUtil.memSet(pages, 0xFF); // every page -1 = "no light here"
            glData = GL45C.glCreateBuffers();
            GL45C.glNamedBufferStorage(glData, (long) MAX_WINDOWS * WINDOW_PAGES * Integer.BYTES, GL45C.GL_DYNAMIC_STORAGE_BIT);
            GL45C.glNamedBufferSubData(glData, 0L, pages);
            glPool = GL45C.glCreateBuffers();
            GL45C.glNamedBufferStorage(glPool, (long) SLOTS * SECTION_CELLS * Integer.BYTES, GL45C.GL_DYNAMIC_STORAGE_BIT);
        } finally {
            MemoryUtil.memFree(pages);
        }

        freeSlots = new int[SLOTS];
        resetSlots();
    }

    private static void resetSlots() {
        for (int i = 0; i < SLOTS; i++)
            freeSlots[i] = SLOTS - 1 - i;
        freeCount = SLOTS;
        fullWarned = false;
    }

    // ------------------------------------------------------------------------------------------------------
    // Windows
    // ------------------------------------------------------------------------------------------------------

    /**
     * The window of the sub-level {@code key}, created on first use. {@code a*} is the section the window should be
     * centred on (the middle of the sub-level's bounding box). Null if all windows are in use this tick.
     */
    public static Window acquire(Object key, int ax, int ay, int az) {
        ensureBuffers();

        Window free = null;
        for (Window w : WINDOWS) {
            if (w != null && w.key == key) {
                if (Math.abs(ax - w.ax) > 3 || Math.abs(ay - w.ay) > 3 || Math.abs(az - w.az) > 3) {
                    clear(w); // the sub-level grew or moved in plot space: recentre
                    w.ax = ax;
                    w.ay = ay;
                    w.az = az;
                    w.rescan = 0;
                }
                w.lastUsed = tickNo;
                return w;
            }
        }

        int index = -1;
        for (int i = 0; i < MAX_WINDOWS; i++) {
            if (WINDOWS[i] == null) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            long oldest = Long.MAX_VALUE;
            for (int i = 0; i < MAX_WINDOWS; i++) {
                if (WINDOWS[i].lastUsed < oldest) {
                    oldest = WINDOWS[i].lastUsed;
                    index = i;
                }
            }
            if (tickNo - oldest < 2)
                return null; // every window was used a moment ago
            clear(WINDOWS[index]);
        }

        Window w = new Window();
        w.key = key;
        w.index = index;
        w.ax = ax;
        w.ay = ay;
        w.az = az;
        w.lastUsed = tickNo;
        WINDOWS[index] = w;
        return w;
    }

    private static void clear(Window w) {
        for (long key : w.slotOf.keySet().toLongArray())
            release(w, key);
        w.pending.clear();
    }

    // ------------------------------------------------------------------------------------------------------
    // Keeping the windows in step with the engine
    // ------------------------------------------------------------------------------------------------------

    /** Sections whose light changed (plot space too). */
    public static void onSectionsChanged(long[] keys) {
        if (glData == 0)
            return;
        for (Window w : WINDOWS) {
            if (w == null)
                continue;
            for (long key : keys) {
                if (w.contains(PosKey.x(key), PosKey.y(key), PosKey.z(key)))
                    w.pending.add(key);
            }
        }
    }

    public static void tick(ColorLightEngine engine, ClientLevel level) {
        if (glData == 0)
            return;
        tickNo++;

        if (engine != lastEngine) {
            reset();
            lastEngine = engine;
            return;
        }

        long start = System.nanoTime();
        int uploaded = 0;

        for (int i = 0; i < MAX_WINDOWS; i++) {
            Window w = WINDOWS[i];
            if (w == null)
                continue;

            if (tickNo - w.lastUsed > UNUSED_TICKS) { // sub-level gone or out of sight
                clear(w);
                WINDOWS[i] = null;
                continue;
            }

            if (--w.rescan <= 0) {
                w.rescan = RESCAN_TICKS;
                final Window win = w;
                // light that was never reported to us: it existed before the window did
                engine.forEachLitSection(key -> {
                    if (win.contains(PosKey.x(key), PosKey.y(key), PosKey.z(key)) && !win.slotOf.containsKey(key))
                        win.pending.add(key);
                });
            }

            if (w.pending.isEmpty())
                continue;

            for (long key : w.pending.toLongArray()) {
                if (uploaded >= MAX_UPLOADS_PER_TICK || System.nanoTime() - start > UPLOAD_BUDGET_NANOS)
                    return;
                w.pending.remove(key);
                upload(w, engine, level, key);
                uploaded++;
            }
        }
    }

    public static void reset() {
        for (int i = 0; i < MAX_WINDOWS; i++) {
            if (WINDOWS[i] != null) {
                clear(WINDOWS[i]);
                WINDOWS[i] = null;
            }
        }
        if (glData != 0)
            resetSlots();
    }

    public static void shutdown() {
        if (glData != 0) {
            GL15C.glDeleteBuffers(glData);
            GL15C.glDeleteBuffers(glPool);
            glData = glPool = 0;
            MemoryUtil.memFree(sectionBytes);
            MemoryUtil.memFree(wordBytes);
            sectionBytes = wordBytes = null;
        }
        for (int i = 0; i < MAX_WINDOWS; i++)
            WINDOWS[i] = null;
    }

    // ------------------------------------------------------------------------------------------------------

    private static void upload(Window w, ColorLightEngine engine, ClientLevel level, long key) {
        int sx = PosKey.x(key), sy = PosKey.y(key), sz = PosKey.z(key);
        if (!w.contains(sx, sy, sz)) {
            release(w, key);
            return;
        }

        boolean lit = engine.copySectionColors(sx, sy, sz, cells);
        int slot = w.slotOf.get(key);

        if (!lit) {
            if (slot >= 0)
                release(w, key);
            return;
        }

        boolean fresh = slot < 0;
        if (fresh) {
            if (freeCount == 0) {
                if (!fullWarned) {
                    fullWarned = true;
                    ColorLightClient.LOGGER.warn("[ColorLight] Sable light pool is full ({} sections); light on sub-levels will be missing.", SLOTS);
                }
                return;
            }
            slot = freeSlots[--freeCount];
            w.slotOf.put(key, slot);
        }

        markSolidCells(level, sx, sy, sz);

        sectionBytes.clear();
        sectionBytes.asIntBuffer().put(cells);
        sectionBytes.limit(SECTION_CELLS * Integer.BYTES);
        GL45C.glNamedBufferSubData(glPool, (long) slot * SECTION_CELLS * Integer.BYTES, sectionBytes);

        if (fresh)
            writePage(w, sx, sy, sz, slot); // after the data, so the shader never sees a half-written section
    }

    private static void markSolidCells(ClientLevel level, int sx, int sy, int sz) {
        int x0 = sx << 4, y0 = sy << 4, z0 = sz << 4;
        int i = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++, i++) {
                    cursor.set(x0 + x, y0 + y, z0 + z);
                    if (level.getBlockState(cursor).getLightBlock(level, cursor) >= 15)
                        cells[i] |= OPAQUE_BIT;
                }
            }
        }
    }

    private static void release(Window w, long key) {
        int slot = w.slotOf.remove(key);
        if (slot < 0)
            return;
        writePage(w, PosKey.x(key), PosKey.y(key), PosKey.z(key), -1);
        freeSlots[freeCount++] = slot;
    }

    private static void writePage(Window w, int sx, int sy, int sz, int value) {
        int page = w.pageBase() + ((sx & 31) | ((sz & 31) << 5) | ((sy & 31) << 10));
        wordBytes.clear();
        wordBytes.putInt(value);
        wordBytes.flip();
        GL45C.glNamedBufferSubData(glData, (long) page * Integer.BYTES, wordBytes);
    }

    private SableLightVolume() {
    }
}
