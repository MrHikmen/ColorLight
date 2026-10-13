package me.mrhikmen.colorlight.client.compat.voxy;

import me.mrhikmen.colorlight.client.ColorLightClient;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

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
        if (failed || !VoxyLightBridge.isPatchedForVulkan())
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
    }

    private static void sync(int slot) {
        long base = mapped[slot];
        int header = VoxyLightBridge.headerInts();

        // the header changes with the camera, so it is always rewritten
        MemoryUtil.memIntBuffer(base, header).put(VoxyLightBridge.vkHeader, 0, header);

        long mirror = VoxyLightBridge.vkMirror;
        long stamp = VoxyLightBridge.vkStamp;
        if (mirror == 0L || slotStamp[slot] == stamp)
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
            }
        }
        currentHandle = 0L;
    }
}
