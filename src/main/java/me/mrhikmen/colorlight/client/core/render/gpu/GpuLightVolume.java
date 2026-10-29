package me.mrhikmen.colorlight.client.core.render.gpu;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import me.mrhikmen.colorlight.client.ColorLightClient;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngine;
import me.mrhikmen.colorlight.client.core.light.util.PosKey;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * ColorLight's light, as the terrain shader sees it: a sparse, camera-centred voxel volume in two GPU texel buffers.
 * <p>
 * <b>Layout</b> (mirrored by {@code shaders/include/colorlight_data.glsl} - change both together):
 * <ul>
 *     <li><b>header buffer</b>: {@link #HEADER_INTS} ints (camera, flags, tint settings), rewritten by mapping it,
 *     which is allowed while a render pass is open (a command-encoder write is not).</li>
 *     <li><b>data buffer</b>: one int per
 *     section page: the {@link #pool} slot holding that section, or -1. Pages are addressed toroidally, so the
 *     window follows the camera without anything being moved.</li>
 *     <li><b>pool buffer</b>: slots of 4096 cells (16 KiB). A slot exists only for a section that holds light.
 *     Cell = {@code 0x00BBGGRR} light colour, bit 24 set for solid blocks (the shader leaves those out of its
 *     averages).</li>
 * </ul>
 * Compared to baking colours into the chunk meshes this means a light change only rewrites a few KiB of GPU memory;
 * no chunk is rebuilt.
 * <p>
 * <b>Threading:</b> everything here runs on the client thread (which is the render thread). The engine's storage is
 * read lock-free; a stale read is fine because every later change marks the section dirty again.
 */
public final class GpuLightVolume {

    // ---- layout constants, mirrored in colorlight_data.glsl --------------------------------------------------
    public static final int HEADER_INTS = 16;
    public static final int XZ_BITS = 6;
    public static final int Y_BITS = 5;
    public static final int PAGE_COUNT = 1 << (XZ_BITS * 2 + Y_BITS);
    public static final int SECTION_CELLS = 16 * 16 * 16;
    public static final int OPAQUE_BIT = 1 << 24;

    /** Shader window radii are 31 / 15 sections; sections are only kept within one less, so the window can't alias. */
    public static final int KEEP_RADIUS_XZ = 30;
    public static final int KEEP_RADIUS_Y = 14;

    private static final int MAX_SLOTS = 4096;

    private static final int RESCAN_TICKS = 20;
    private static final int MAX_UPLOADS_PER_TICK = 48;
    private static final long UPLOAD_BUDGET_NANOS = 2_000_000L;

    /** Brightest tint the light colour can reach; 1 = fully the light's hue at full strength. */
    private static final float TINT_STRENGTH = 1.0f;

    private static final int USAGE = GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_WRITE;

    private final int slotCount;

    /**
     * Raw GL mode: the three buffers are plain OpenGL buffer objects instead of renderpearl {@link GpuBuffer}s. Used when
     * another renderer (Nvidium) draws the terrain with its own GL program: it needs the buffer names to attach them
     * as texel buffers, and renderpearl's draw-time bind groups do not exist there. The layout is identical.
     */
    private final boolean rawGl;
    private int glHeader, glData, glPool;

    private GpuBuffer header_;
    private GpuBuffer data;
    private GpuBuffer pool;

    private final Long2IntOpenHashMap slotOf = new Long2IntOpenHashMap();
    private final int[] freeSlots;
    private int freeCount;

    private final LongOpenHashSet pending = new LongOpenHashSet();

    private final int[] cells = new int[SECTION_CELLS];
    private final ByteBuffer sectionBytes = MemoryUtil.memAlloc(SECTION_CELLS * Integer.BYTES);
    private final ByteBuffer wordBytes = MemoryUtil.memAlloc(Integer.BYTES);
    private final int[] header = new int[HEADER_INTS];
    private final int[] lastHeader = new int[HEADER_INTS];
    private boolean headerWritten;

    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    private ColorLightEngine lastEngine;
    private int camSx, camSy, camSz;
    private int evictedForSx = Integer.MIN_VALUE, evictedForSy, evictedForSz;
    private int rescanTimer;
    private boolean fullWarned;

    /** The slot count a volume really gets for a configured value. */
    public static int clampSlots(int requested) {
        return Math.max(64, Math.min(MAX_SLOTS, requested));
    }

    public GpuLightVolume(int requestedSlots) {
        this(requestedSlots, false);
    }

    public GpuLightVolume(int requestedSlots, boolean rawGl) {
        this.rawGl = rawGl;
        this.slotCount = clampSlots(requestedSlots);
        this.freeSlots = new int[slotCount];
        this.slotOf.defaultReturnValue(-1);
        resetBookkeeping();
    }

    public int slotCount() {
        return slotCount;
    }

    // ------------------------------------------------------------------------------------------------------
    // GPU resources
    // ------------------------------------------------------------------------------------------------------

    public boolean isRawGl() {
        return rawGl;
    }

    public boolean hasBuffers() {
        if (rawGl)
            return glHeader != 0 && glData != 0 && glPool != 0;
        return header_ != null && data != null && pool != null;
    }

    /** Raw GL mode only: OpenGL names of {header, data, pool}, or null while the buffers don't exist. */
    public int[] glBufferNames() {
        if (!rawGl || !hasBuffers())
            return null;
        return new int[]{glHeader, glData, glPool};
    }

    private static java.lang.reflect.Field[] handleFields;
    private static Class<?> handleFieldsClass;
    private static boolean handleLookupReported;

    /**
     * OpenGL name behind a blaze3d buffer of the OpenGL backend, 0 for anything else (Vulkan, closed buffer). The
     * field is not part of any API, so every int field of the buffer class is looked at: "handle" / "id" first, then the
     * others, and the first value that OpenGL itself calls a buffer name wins.
     */
    private static int glNameOf(GpuBuffer frontendBuffer) {
        if (frontendBuffer == null)
            return 0;
        Object buffer = frontendBuffer;
        try {
            // Renderpearl (26.3+) hands out frontend wrappers; the backend's own buffer is in the public field "backend"
            for (Class<?> c = buffer.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    var bf = c.getDeclaredField("backend");
                    bf.setAccessible(true);
                    Object inner = bf.get(buffer);
                    if (inner != null)
                        buffer = inner;
                    break;
                } catch (NoSuchFieldException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        if (!buffer.getClass().getName().toLowerCase(java.util.Locale.ROOT).contains("opengl"))
            return 0; // never touch GL on another backend: there may be no GL context at all
        try {
            if (handleFields == null || handleFieldsClass != buffer.getClass()) {
                handleFieldsClass = buffer.getClass();
                java.util.List<java.lang.reflect.Field> found = new java.util.ArrayList<>();
                for (Class<?> c = buffer.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    for (var f : c.getDeclaredFields()) {
                        if (f.getType() == int.class && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                            f.setAccessible(true);
                            found.add(f);
                        }
                    }
                }
                found.sort(java.util.Comparator.comparingInt(f -> f.getName().equals("handle") ? 0 : f.getName().equals("id") ? 1 : 2));
                handleFields = found.toArray(new java.lang.reflect.Field[0]);
            }
            for (var f : handleFields) {
                int v = f.getInt(buffer);
                if (v > 0 && GL15C.glIsBuffer(v))
                    return v;
            }
            if (!handleLookupReported) {
                handleLookupReported = true;
                StringBuilder sb = new StringBuilder();
                for (var f : handleFields)
                    sb.append(f.getName()).append('=').append(f.getInt(buffer)).append(' ');
                me.mrhikmen.colorlight.client.ColorLightClient.LOGGER.warn("[ColorLight] Flywheel: no OpenGL buffer name found in {} (int fields: {})", buffer.getClass().getName(), sb);
            }
        } catch (Throwable t) {
            if (!handleLookupReported) {
                handleLookupReported = true;
                me.mrhikmen.colorlight.client.ColorLightClient.LOGGER.warn("[ColorLight] Flywheel: OpenGL buffer lookup failed", t);
            }
        }
        return 0;
    }

    /** OpenGL names of {header, data, pool} in either mode, or null if any is unknown (e.g. a Vulkan device). */
    public int[] anyGlNames() {
        if (rawGl)
            return glBufferNames();
        if (!hasBuffers())
            return null;
        int h = glNameOf(header_), d = glNameOf(data), p = glNameOf(pool);
        return (h == 0 || d == 0 || p == 0) ? null : new int[]{h, d, p};
    }

    public GpuBuffer headerBuffer() {
        return header_;
    }

    public GpuBuffer dataBuffer() {
        return data;
    }

    public GpuBuffer poolBuffer() {
        return pool;
    }


    public void ensureBuffers() {
        if (hasBuffers())
            return;

        if (rawGl) {
            createRawBuffers();
            return;
        }

        var device = RenderSystem.getDevice();
        header_ = device.createBuffer(() -> "ColorLight light header", USAGE, (long) HEADER_INTS * Integer.BYTES);
        data = device.createBuffer(() -> "ColorLight light pages", USAGE, (long) PAGE_COUNT * Integer.BYTES);
        pool = device.createBuffer(() -> "ColorLight light pool", USAGE, (long) slotCount * SECTION_CELLS * Integer.BYTES);

        try (var mapping = data.map(false, true)) {
            MemoryUtil.memSet(mapping.data(), 0xFF); // every page -1 = "no light here"
        }
        try (var mapping = header_.map(false, true)) {
            MemoryUtil.memSet(mapping.data(), 0); // disabled until the first writeHeader
        }
        headerWritten = false;
    }

    private static int createRawBuffer(long sizeBytes, ByteBuffer initial) {
        int id = GL45C.glCreateBuffers();
        // immutable storage; DYNAMIC_STORAGE lets us update it with glNamedBufferSubData
        GL45C.glNamedBufferStorage(id, sizeBytes, GL45C.GL_DYNAMIC_STORAGE_BIT);
        if (initial != null)
            GL45C.glNamedBufferSubData(id, 0L, initial);
        return id;
    }

    private void createRawBuffers() {
        ByteBuffer headerInit = MemoryUtil.memCalloc(HEADER_INTS * Integer.BYTES); // disabled until the first writeHeader
        ByteBuffer pagesInit = MemoryUtil.memAlloc(PAGE_COUNT * Integer.BYTES);
        try {
            MemoryUtil.memSet(pagesInit, 0xFF); // every page -1 = "no light here"
            glHeader = createRawBuffer((long) HEADER_INTS * Integer.BYTES, headerInit);
            glData = createRawBuffer((long) PAGE_COUNT * Integer.BYTES, pagesInit);
            glPool = createRawBuffer((long) slotCount * SECTION_CELLS * Integer.BYTES, null);
        } finally {
            MemoryUtil.memFree(headerInit);
            MemoryUtil.memFree(pagesInit);
        }
        headerWritten = false;
    }

    /** Frees the GPU buffers and the scratch memory. The volume must not be used afterwards. */
    public void close() {
        if (rawGl) {
            if (glHeader != 0)
                GL15C.glDeleteBuffers(glHeader);
            if (glData != 0)
                GL15C.glDeleteBuffers(glData);
            if (glPool != 0)
                GL15C.glDeleteBuffers(glPool);
            glHeader = glData = glPool = 0;
        }
        if (header_ != null) {
            header_.close();
            header_ = null;
        }
        if (data != null) {
            data.close();
            data = null;
        }
        if (pool != null) {
            pool.close();
            pool = null;
        }
        MemoryUtil.memFree(sectionBytes);
        MemoryUtil.memFree(wordBytes);
    }

    private void write(GpuBuffer buffer, long offsetBytes, ByteBuffer source, int lengthBytes) {
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(offsetBytes, lengthBytes), source);
    }

    /** Raw GL: same as {@link #write} for the buffer with the given GL name ({@code source} is position..limit). */
    private void writeRaw(int glBuffer, long offsetBytes, ByteBuffer source) {
        GL45C.glNamedBufferSubData(glBuffer, offsetBytes, source);
    }

    private void writePage(int pageIndex, int value) {
        wordBytes.clear();
        wordBytes.putInt(value);
        wordBytes.flip();
        if (rawGl)
            writeRaw(glData, (long) pageIndex * Integer.BYTES, wordBytes);
        else
            write(data, (long) pageIndex * Integer.BYTES, wordBytes, Integer.BYTES);
    }

    // ------------------------------------------------------------------------------------------------------
    // Per-frame header (camera + settings). Must be called outside of a render pass.
    // ------------------------------------------------------------------------------------------------------

    public void writeHeader(int camBlockX, int camBlockY, int camBlockZ, float fracX, float fracY, float fracZ, boolean enabled, float tintGamma, boolean smooth) {
        if (!hasBuffers())
            return;

        camSx = camBlockX >> 4;
        camSy = camBlockY >> 4;
        camSz = camBlockZ >> 4;

        Arrays.fill(header, 0);
        header[0] = camBlockX;
        header[1] = camBlockY;
        header[2] = camBlockZ;
        header[3] = enabled ? 1 : 0;
        header[4] = Float.floatToRawIntBits(fracX);
        header[5] = Float.floatToRawIntBits(fracY);
        header[6] = Float.floatToRawIntBits(fracZ);
        header[7] = Float.floatToRawIntBits(TINT_STRENGTH);
        header[8] = Float.floatToRawIntBits(Math.max(0f, tintGamma));
        header[9] = smooth ? 1 : 0;

        if (headerWritten && Arrays.equals(header, lastHeader))
            return;

        if (rawGl) {
            GL45C.glNamedBufferSubData(glHeader, 0L, header);
        } else {
            // mapped, not written through the command encoder: this runs while Sodium's render pass is open
            try (var mapping = header_.map(false, true)) {
                ByteBuffer bytes = mapping.data().order(ByteOrder.nativeOrder());
                for (int i = 0; i < HEADER_INTS; i++)
                    bytes.putInt(i * Integer.BYTES, header[i]);
            }
        }

        System.arraycopy(header, 0, lastHeader, 0, HEADER_INTS);
        headerWritten = true;
    }

    // ------------------------------------------------------------------------------------------------------
    // Keeping the volume in step with the engine
    // ------------------------------------------------------------------------------------------------------

    /** Sections whose light (or neighbouring light) changed; they are re-read and uploaded over the next ticks. */
    public void markDirty(long[] sectionKeys) {
        for (long key : sectionKeys)
            pending.add(key);
    }

    /** Forgets everything that was uploaded (new world, new engine). */
    public void reset() {
        if (hasBuffers()) {
            for (int page : pagesInUse())
                writePage(page, -1);
        }
        resetBookkeeping();
        pending.clear();
        lastEngine = null;
    }

    private int[] pagesInUse() {
        int[] pages = new int[slotOf.size()];
        int i = 0;
        for (long key : slotOf.keySet())
            pages[i++] = pageIndex(PosKey.x(key), PosKey.y(key), PosKey.z(key));
        return pages;
    }

    private void resetBookkeeping() {
        slotOf.clear();
        for (int i = 0; i < slotCount; i++)
            freeSlots[i] = slotCount - 1 - i; // pop() hands out slot 0 first
        freeCount = slotCount;
        evictedForSx = Integer.MIN_VALUE;
        rescanTimer = 0;
        fullWarned = false;
    }

    /**
     * Once per client tick: drops what left the window, finds light that the volume doesn't have yet and uploads a
     * bounded amount of changed sections (nearest to the camera first).
     */
    public void tick(ColorLightEngine engine, ClientLevel level, int camSectionX, int camSectionY, int camSectionZ) {
        if (engine != lastEngine) {
            reset();
            lastEngine = engine;
        }
        ensureBuffers();

        boolean moved = camSectionX != evictedForSx || camSectionY != evictedForSy || camSectionZ != evictedForSz;
        camSx = camSectionX;
        camSy = camSectionY;
        camSz = camSectionZ;

        if (moved) {
            evictOutsideWindow();
            evictedForSx = camSectionX;
            evictedForSy = camSectionY;
            evictedForSz = camSectionZ;
            rescanTimer = 0;
        }

        if (--rescanTimer <= 0) {
            rescanTimer = RESCAN_TICKS;
            // light that was never reported as dirty to us: it existed before the volume did, or just entered the window
            engine.forEachLitSectionNear(camSx, camSy, camSz, KEEP_RADIUS_XZ, KEEP_RADIUS_Y, key -> {
                if (!slotOf.containsKey(key))
                    pending.add(key);
            });
        }

        uploadPending(engine, level);

    }

    private boolean inWindow(int sx, int sy, int sz) {
        return Math.abs(sx - camSx) <= KEEP_RADIUS_XZ && Math.abs(sz - camSz) <= KEEP_RADIUS_XZ && Math.abs(sy - camSy) <= KEEP_RADIUS_Y;
    }

    private void evictOutsideWindow() {
        long[] keys = slotOf.keySet().toLongArray();
        for (long key : keys) {
            if (!inWindow(PosKey.x(key), PosKey.y(key), PosKey.z(key)))
                release(key);
        }
    }

    private void uploadPending(ColorLightEngine engine, ClientLevel level) {
        if (pending.isEmpty())
            return;

        long[] keys = pending.toLongArray();
        if (keys.length > 1)
            keys = nearestFirst(keys);

        long start = System.nanoTime();
        int uploaded = 0;
        for (long key : keys) {
            if (uploaded >= MAX_UPLOADS_PER_TICK || System.nanoTime() - start > UPLOAD_BUDGET_NANOS)
                break; // the rest stays pending for the next tick
            pending.remove(key);
            upload(engine, level, key);
            uploaded++;
        }
    }

    private long[] nearestFirst(long[] keys) {
        long[] order = new long[keys.length];
        for (int i = 0; i < keys.length; i++)
            order[i] = (distanceSq(keys[i]) << 32) | i;
        Arrays.sort(order);

        long[] sorted = new long[keys.length];
        for (int i = 0; i < order.length; i++)
            sorted[i] = keys[(int) (order[i] & 0xFFFFFFFFL)];
        return sorted;
    }

    private long distanceSq(long key) {
        long dx = PosKey.x(key) - camSx;
        long dy = PosKey.y(key) - camSy;
        long dz = PosKey.z(key) - camSz;
        return dx * dx + dy * dy + dz * dz;
    }

    private void upload(ColorLightEngine engine, ClientLevel level, long key) {
        int sx = PosKey.x(key), sy = PosKey.y(key), sz = PosKey.z(key);

        if (!inWindow(sx, sy, sz)) {
            release(key);
            return;
        }

        boolean lit = engine.copySectionColors(sx, sy, sz, cells);
        int slot = slotOf.get(key);

        if (!lit) {
            if (slot >= 0)
                release(key);
            return;
        }

        boolean fresh = slot < 0;
        if (fresh) {
            slot = allocate(key);
            if (slot < 0)
                return;
        }

        markSolidCells(level, sx, sy, sz);

        sectionBytes.clear();
        sectionBytes.asIntBuffer().put(cells);
        sectionBytes.limit(SECTION_CELLS * Integer.BYTES);
        if (rawGl)
            writeRaw(glPool, (long) slot * SECTION_CELLS * Integer.BYTES, sectionBytes);
        else
            write(pool, (long) slot * SECTION_CELLS * Integer.BYTES, sectionBytes, SECTION_CELLS * Integer.BYTES);

        if (fresh)
            writePage(pageIndex(sx, sy, sz), slot); // after the data, so the shader never sees a half-written section
    }

    /** Sets {@link #OPAQUE_BIT} on every cell of the section that holds a light-blocking block. */
    private void markSolidCells(ClientLevel level, int sx, int sy, int sz) {
        markSolid(level, sx, sy, sz, cells, cursor);
    }

    /**
     * Reads the blocks straight from the chunk section (one chunk lookup instead of 4096) and skips sections that hold
     * only air; an unloaded chunk or a section outside the build height reads as air, exactly like
     * {@code Level.getBlockState} does.
     */
    public static void markSolid(ClientLevel level, int sx, int sy, int sz, int[] cells, BlockPos.MutableBlockPos cursor) {
        int index = level.getSectionIndexFromSectionY(sy);
        if (index < 0 || index >= level.getSectionsCount())
            return;
        ChunkAccess chunk = level.getChunk(sx, sz, ChunkStatus.FULL, false);
        if (chunk == null)
            return;
        LevelChunkSection section = chunk.getSection(index);
        if (section.hasOnlyAir())
            return;

        int x0 = sx << 4, y0 = sy << 4, z0 = sz << 4;
        int i = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++, i++) {
                    BlockState state = section.getBlockState(x, y, z);
                    if (state.isAir())
                        continue;
                    cursor.set(x0 + x, y0 + y, z0 + z);
                    if (state.getLightDampening() >= 15)
                        cells[i] |= OPAQUE_BIT;
                }
            }
        }
    }

    private int allocate(long key) {
        if (freeCount > 0) {
            int slot = freeSlots[--freeCount];
            slotOf.put(key, slot);
            return slot;
        }

        // full: make room by dropping the section farthest from the camera, if it is farther than this one
        long farthestKey = 0;
        long farthest = -1;
        for (long other : slotOf.keySet()) {
            long d = distanceSq(other);
            if (d > farthest) {
                farthest = d;
                farthestKey = other;
            }
        }

        if (farthest > distanceSq(key)) {
            release(farthestKey);
            int slot = freeSlots[--freeCount];
            slotOf.put(key, slot);
            return slot;
        }

        if (!fullWarned) {
            fullWarned = true;
            ColorLightClient.LOGGER.warn("[ColorLight] GPU light pool is full ({} sections); distant light will be missing. Raise GPU_LIGHT_SECTIONS in the config.", slotCount);
        }
        return -1;
    }

    private void release(long key) {
        int slot = slotOf.remove(key);
        if (slot < 0)
            return;

        if (hasBuffers())
            writePage(pageIndex(PosKey.x(key), PosKey.y(key), PosKey.z(key)), -1);
        freeSlots[freeCount++] = slot;
    }

    /** Same formula as {@code cl_cellRaw} in the shader. */
    public static int pageIndex(int sx, int sy, int sz) {
        return (sx & 63) | ((sz & 63) << 6) | ((sy & 31) << 12);
    }
}
