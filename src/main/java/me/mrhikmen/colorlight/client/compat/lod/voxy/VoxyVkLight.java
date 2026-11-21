package me.mrhikmen.colorlight.client.compat.lod.voxy;

import me.mrhikmen.colorlight.client.ColorLightClient;
import me.mrhikmen.colorlight.client.compat.lod.LodDiag;
import me.mrhikmen.colorlight.client.compat.lod.LodLightVolume;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Vulkan side of the Voxy far-light buffer. Everything about Voxy is reached by reflection (ColorLight is not
 * compiled against the Vulkan build of Voxy), and it all happens on the render thread.
 * <p>
 * The buffer is a host-visible Voxy {@code VkBuffer} (persistently mapped), {@link #SLOTS} of them in rotation by Voxy's
 * frame index, so the CPU never overwrites data a frame still in flight is reading. {@link VoxyLightBridge} keeps a
 * CPU mirror (header + cell table + source list, the same layout the OpenGL path uploads) up to date;
 * {@link #beginDraw} copies what changed into the slot of the current frame and remembers its handle for
 * {@code VkBinderMixin}, which adds it to the terrain pass's push descriptors as binding {@link #BINDING}.
 * <p>
 * This class must only be touched when Voxy runs on Vulkan: it is loaded by the Vulkan mixins and by
 * {@link VoxyLightBridge} on that path only ({@link #BINDING} is a compile-time constant, so reading it does not load
 * the class).
 */
public final class VoxyVkLight {

    /** Voxy's terrain pipelines use 0,1,3,4,5,8,9,10. */
    public static final int BINDING = 11;
    private static final int SLOTS = 3;
    private static final String PKG = "me.cortex.voxy.client.core.vk.";

    private static boolean reflected;
    private static boolean failed;

    private static Constructor<?> bufferCtor;
    private static Method bufferMap, bufferFree, ctxCurrentFrame;
    private static Field bufferHandle;
    private static int bufferUsage;
    private static Field rendererCtxField;

    private static Object ctx;
    private static final Object[] buffers = new Object[SLOTS];
    private static final long[] handles = new long[SLOTS];
    private static final long[] mapped = new long[SLOTS];
    /** Stamp of the table (cells + sources) each slot holds; -1 = nothing copied yet. */
    private static final long[] slotStamp = new long[SLOTS];
    /** Stamp of the propagation method tables each slot holds; -1 = none copied yet. */
    private static final long[] slotTablesStamp = new long[SLOTS];

    private static long currentHandle;
    private static long currentSize;

    private VoxyVkLight() {
    }

    public static long currentHandle() {
        return currentHandle;
    }

    public static long currentSize() {
        return currentSize;
    }

    /** A {@code VkShaderPipeline.Binding(BINDING, STORAGE_BUFFER)} to append to the terrain pipelines' binding table. */
    public static Object newSsboBinding() {
        try {
            Class<?> c = Class.forName(PKG + "VkShaderPipeline$Binding");
            Constructor<?> k = c.getDeclaredConstructor(int.class, int.class);
            k.setAccessible(true);
            return k.newInstance(BINDING, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
        } catch (Throwable t) {
            throw new IllegalStateException("cannot build Voxy binding", t);
        }
    }

    /** Called from the head of {@code VkTerrainRenderer.renderTerrain}; {@code renderer} is the VkTerrainRenderer. */
    public static void beginDraw(Object renderer) {
        currentHandle = 0L;
        if (!VoxyLightBridge.isPatchedForVulkan())
            return;
        if (VoxyLightBridge.vkVolumeActive()) {
            beginDrawVolume(renderer);
            return;
        }
        if (failed)
            return;

        try {
            if (!reflected)
                reflect(renderer);

            Object newCtx = rendererCtxField.get(renderer);
            if (newCtx != ctx) {
                release();
                ctx = newCtx;
            }

            int slot = (int) (((Number) ctxCurrentFrame.invoke(ctx)).longValue() % SLOTS);
            if (buffers[slot] == null)
                create(slot);

            sync(slot);
            currentHandle = handles[slot];
            currentSize = (long) VoxyLightBridge.totalInts() * Integer.BYTES;
        } catch (Throwable t) {
            failed = true;
            currentHandle = 0L;
            ColorLightClient.LOGGER.error("[ColorLight] Far light colours for Voxy (Vulkan) disabled: {}", t.toString());
        }
    }

    /** Called when Voxy's terrain renderer is freed (world unload, backend restart). */
    public static void onRendererFree() {
        release();
        releaseVolume();
        ctx = null;
    }

    // ------------------------------------------------------------------------------------------------------

    private static void reflect(Object renderer) throws Exception {
        Class<?> bufferClass = Class.forName(PKG + "VkBuffer");
        Class<?> ctxClass = Class.forName(PKG + "VkFrameCtx");
        bufferCtor = bufferClass.getConstructor(ctxClass, long.class, int.class, boolean.class);
        bufferMap = bufferClass.getMethod("map");
        bufferFree = bufferClass.getMethod("free");
        bufferHandle = bufferClass.getField("buffer");
        bufferUsage = bufferClass.getField("USAGE_DEFAULT").getInt(null);
        ctxCurrentFrame = ctxClass.getMethod("currentFrame");
        rendererCtxField = renderer.getClass().getDeclaredField("ctx");
        rendererCtxField.setAccessible(true);
        reflected = true;
    }

    private static void create(int slot) throws Exception {
        long bytes = (long) VoxyLightBridge.totalInts() * Integer.BYTES;
        Object b = bufferCtor.newInstance(ctx, bytes, bufferUsage, true);
        long ptr = (Long) bufferMap.invoke(b);
        MemoryUtil.memSet(ptr, 0, bytes); // no cells, no sources, header[2] = 0 (disabled) until the first real frame
        buffers[slot] = b;
        handles[slot] = bufferHandle.getLong(b);
        mapped[slot] = ptr;
        slotStamp[slot] = -1L;
        slotTablesStamp[slot] = -1L;
    }

    private static void sync(int slot) {
        long base = mapped[slot];
        int header = VoxyLightBridge.headerInts();

        // the header changes with the camera, so it is always rewritten
        MemoryUtil.memIntBuffer(base, header).put(VoxyLightBridge.vkHeader, 0, header);

        long mirror = VoxyLightBridge.vkMirror;
        long stamp = VoxyLightBridge.vkStamp;
        if (mirror == 0L)
            return;

        // the distance tables of the propagation methods (only when the shader reads them); they change rarely
        long tablesStamp = VoxyLightBridge.vkTablesStamp;
        if (tablesStamp != 0L && slotTablesStamp[slot] != tablesStamp) {
            long toff = (long) VoxyLightBridge.tablesOffsetInts() * Integer.BYTES;
            MemoryUtil.memCopy(mirror + toff, base + toff, (long) VoxyLightBridge.tablesInts() * Integer.BYTES);
            slotTablesStamp[slot] = tablesStamp;
        }

        if (slotStamp[slot] == stamp)
            return;

        // table first, then the sources it points into; only the used part of the source list is copied
        MemoryUtil.memCopy(mirror + (long) header * Integer.BYTES, base + (long) header * Integer.BYTES,
                (long) VoxyLightBridge.cellInts() * Integer.BYTES);
        int count = VoxyLightBridge.vkSourceCount;
        if (count > 0) {
            long off = (long) VoxyLightBridge.sourceOffsetInts() * Integer.BYTES;
            MemoryUtil.memCopy(mirror + off, base + off, (long) count * 4 * Integer.BYTES);
        }
        slotStamp[slot] = stamp;
    }

    private static void release() {
        for (int i = 0; i < SLOTS; i++) {
            if (buffers[i] != null) {
                try {
                    bufferFree.invoke(buffers[i]); // Voxy defers the destroy until the frames using it are done
                } catch (Throwable ignored) {
                }
                buffers[i] = null;
                handles[i] = 0L;
                mapped[i] = 0L;
                slotStamp[i] = -1L;
                slotTablesStamp[i] = -1L;
            }
        }
        currentHandle = 0L;
    }

    // ======================================================================================================
    // Volume mode: one device-local buffer holding LodLightVolume's layout (header, hash table, tile pool). The CPU side
    // (VoxyLightBridge / LodVolumeBuilder) keeps the volume; here, once per frame, what changed is staged through Voxy's
    // own upload stream (copied into the buffer by Voxy's commit, with the barriers it already does) before the terrain
    // is drawn. All of it is outside a render pass: renderTerrain's head runs before its pass begins.
    // ======================================================================================================

    private static final int RUN_TILES = 256;
    private static final int TILE_BYTES = LodLightVolume.TILE_VOXELS * Integer.BYTES;

    private static boolean volReflected;
    private static boolean volFailed;
    private static Field rendererUploadField;
    private static Method streamUpload, streamCommit, ctxFill, ctxBarrier;

    private static Object uploadStream;
    private static Object volBuffer;        // device-local buffer of the whole layout
    private static Object fallbackBuffer;   // 64 zero bytes: "disabled", so the pipeline's binding is never empty
    private static long volBytes;
    private static int volTiles;
    private static boolean volForceReset;
    private static long volFrame = Long.MIN_VALUE;
    private static final int[] volLastHeader = new int[LodLightVolume.HEADER_INTS];
    private static boolean volHeaderWritten;
    private static long runBuf;
    private static final VkSink SINK = new VkSink();

    private static void beginDrawVolume(Object renderer) {
        try {
            if (!volReflected)
                reflectVolume(renderer);

            Object newCtx = rendererCtxField.get(renderer);
            if (newCtx != ctx) {
                release();
                releaseVolume();
                ctx = newCtx;
            }
            uploadStream = rendererUploadField.get(renderer);

            if (volFailed) {
                serveFallback();
                return;
            }
            if (volBuffer != null && volTiles != VoxyLightBridge.vkVolumeTiles())
                releaseVolumeBuffer(); // the memory setting changed: a buffer of the new size
            if (volBuffer == null)
                createVolumeBuffer();

            currentHandle = handleOf(volBuffer);
            currentSize = volBytes;

            long frame = ((Number) ctxCurrentFrame.invoke(ctx)).longValue();
            if (frame == volFrame)
                return; // opaque, temporal and translucent passes of one frame share one update
            volFrame = frame;
            updateVolume();
        } catch (Throwable t) {
            volFailed = true;
            ColorLightClient.LOGGER.error("[ColorLight] Far light for Voxy (Vulkan) turned off: {}", t.toString());
            try {
                serveFallback();
            } catch (Throwable t2) {
                currentHandle = 0L;
            }
        }
    }

    private static void reflectVolume(Object renderer) throws Exception {
        if (!reflected)
            reflect(renderer);
        Class<?> streamClass = Class.forName(PKG + "VkUploadStream");
        Class<?> deviceBuffer = Class.forName("me.cortex.voxy.client.core.rendering.util.IDeviceBuffer");
        Class<?> bufferClass = Class.forName(PKG + "VkBuffer");
        Class<?> ctxClass = Class.forName(PKG + "VkFrameCtx");
        streamUpload = streamClass.getMethod("upload", deviceBuffer, long.class, long.class);
        streamCommit = streamClass.getMethod("commit");
        ctxFill = ctxClass.getMethod("fillBuffer", bufferClass, long.class, long.class, int.class);
        ctxBarrier = ctxClass.getMethod("barrier", int.class, int.class, int.class, int.class);
        rendererUploadField = renderer.getClass().getDeclaredField("uploadStream");
        rendererUploadField.setAccessible(true);
        runBuf = MemoryUtil.nmemAlloc((long) RUN_TILES * TILE_BYTES);
        volReflected = true;
    }

    private static long handleOf(Object buffer) throws Exception {
        return bufferHandle.getLong(buffer);
    }

    /** The device's maxStorageBufferRange, bytes, or 0 when it cannot be read (the caller then assumes the guaranteed 128 MiB). */
    static long maxStorageBytes() {
        try {
            Object c = ctx;
            if (c == null)
                return 0L;
            Object phys = c.getClass().getField("physicalDevice").get(c);
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                org.lwjgl.vulkan.VkPhysicalDeviceProperties props = org.lwjgl.vulkan.VkPhysicalDeviceProperties.malloc(stack);
                org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties((org.lwjgl.vulkan.VkPhysicalDevice) phys, props);
                return props.limits().maxStorageBufferRange() & 0xFFFFFFFFL;
            }
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static void createVolumeBuffer() throws Exception {
        volTiles = VoxyLightBridge.vkVolumeTiles();
        volBytes = (long) LodLightVolume.totalInts(volTiles) * Integer.BYTES;
        // Voxy's default VkBuffer: device-local, every usage flag (the shader reads it as a storage buffer)
        Object b = bufferCtor.newInstance(ctx, volBytes, bufferUsage, false);
        volBuffer = b;
        // header "disabled" and every hash entry empty (-1) until the first frame writes them
        ctxFill.invoke(ctx, b, 0L, (long) LodLightVolume.HEADER_INTS * Integer.BYTES, 0);
        volForceReset = true;
        volHeaderWritten = false;
        volFrame = Long.MIN_VALUE;
        ColorLightClient.LOGGER.info("[ColorLight] Far light volume buffer (Vulkan): {} MB, up to {} tiles", volBytes / (1024 * 1024), volTiles);
    }

    /** The shader finds no tile anywhere: the hash table all -1, and what the CPU holds is sent again. */
    private static void clearGpuHash() throws Exception {
        ctxFill.invoke(ctx, volBuffer, (long) LodLightVolume.HASH_OFFSET_INTS * Integer.BYTES,
                (long) LodLightVolume.HASH_ENTRIES * 4 * Integer.BYTES, -1);
        // the fill must be done before the copies that follow and before any shader reads
        ctxBarrier.invoke(ctx, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT | VK10.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                VK10.VK_ACCESS_TRANSFER_WRITE_BIT | VK10.VK_ACCESS_SHADER_READ_BIT);
    }

    private static void updateVolume() throws Exception {
        LodLightVolume vol = VoxyLightBridge.vkVolume();
        int[] header = VoxyLightBridge.vkVolumeHeader();

        boolean reset = VoxyLightBridge.takeVkVolumeReset() | volForceReset;
        volForceReset = false;
        if (reset) {
            clearGpuHash();
            if (vol != null)
                vol.resendAll();
        }

        // the header: only when it changed (it holds the on/off switch and the tint settings)
        if (!volHeaderWritten || !Arrays.equals(header, volLastHeader)) {
            long ptr = (Long) streamUpload.invoke(uploadStream, volBuffer, 0L, (long) LodLightVolume.HEADER_INTS * Integer.BYTES);
            MemoryUtil.memIntBuffer(ptr, LodLightVolume.HEADER_INTS).put(header, 0, LodLightVolume.HEADER_INTS);
            System.arraycopy(header, 0, volLastHeader, 0, header.length);
            volHeaderWritten = true;
        }

        if (vol != null && header[2] == 1) {
            int budget = VoxyLightBridge.vkVolumeBudget();
            int pending = vol.drain(SINK, budget, VoxyLightBridge.vkVolumeEntryBudget());
            SINK.flushRun();
            VoxyLightBridge.vkVolumeDrained(pending);
        }
        streamCommit.invoke(uploadStream); // records the copies (and their barriers) into this frame's commands
    }

    /** Writes what the volume hands over into the stream's staging memory; tiles with consecutive slots are one copy. */
    private static final class VkSink implements LodLightVolume.GpuSink {
        private int runStart = -1;
        private int runCount;

        @Override
        public void reset() {
            runCount = 0;
            runStart = -1;
            try {
                clearGpuHash();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void tile(int slot, int[] voxels) {
            if (runCount > 0 && (slot != runStart + runCount || runCount == RUN_TILES))
                flushRun();
            if (runCount == 0)
                runStart = slot;
            MemoryUtil.memIntBuffer(runBuf + (long) runCount * TILE_BYTES, LodLightVolume.TILE_VOXELS).put(voxels, 0, LodLightVolume.TILE_VOXELS);
            runCount++;
            LodDiag.volUploadTiles.incrementAndGet();
        }

        @Override
        public void entry(int index, int a, int b, int c, int d) {
            flushRun(); // a tile's data is always staged before the entry that points to it
            try {
                long ptr = (Long) streamUpload.invoke(uploadStream, volBuffer,
                        (long) (LodLightVolume.HASH_OFFSET_INTS + index * 4) * Integer.BYTES, 16L);
                MemoryUtil.memPutInt(ptr, a);
                MemoryUtil.memPutInt(ptr + 4, b);
                MemoryUtil.memPutInt(ptr + 8, c);
                MemoryUtil.memPutInt(ptr + 12, d);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            LodDiag.volUploadEntries.incrementAndGet();
        }

        void flushRun() {
            if (runCount == 0)
                return;
            try {
                long bytes = (long) runCount * TILE_BYTES;
                long ptr = (Long) streamUpload.invoke(uploadStream, volBuffer,
                        (long) (LodLightVolume.POOL_OFFSET_INTS + (long) runStart * LodLightVolume.TILE_VOXELS) * Integer.BYTES, bytes);
                MemoryUtil.memCopy(runBuf, ptr, bytes);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            runCount = 0;
            runStart = -1;
        }
    }

    /** After a failure: a tiny zeroed buffer (header[2] = 0), so the shader shows Voxy's own lighting and the binding is never empty. */
    private static void serveFallback() throws Exception {
        if (fallbackBuffer == null) {
            if (volBuffer != null)
                releaseVolumeBuffer();
            Object b = bufferCtor.newInstance(ctx, 64L, bufferUsage, true);
            long ptr = (Long) bufferMap.invoke(b);
            MemoryUtil.memSet(ptr, 0, 64L);
            fallbackBuffer = b;
        }
        currentHandle = handleOf(fallbackBuffer);
        currentSize = 64L;
    }

    private static void releaseVolumeBuffer() {
        try {
            if (volBuffer != null)
                bufferFree.invoke(volBuffer); // Voxy defers the destroy until the frames using it are done
        } catch (Throwable ignored) {
        }
        volBuffer = null;
    }

    private static void releaseVolume() {
        releaseVolumeBuffer();
        try {
            if (fallbackBuffer != null)
                bufferFree.invoke(fallbackBuffer);
        } catch (Throwable ignored) {
        }
        fallbackBuffer = null;
        uploadStream = null;
        volFrame = Long.MIN_VALUE;
        volHeaderWritten = false;
        volForceReset = true;
        if (VoxyLightBridge.vkVolume() != null)
            VoxyLightBridge.vkVolume().resendAll();
    }
}
