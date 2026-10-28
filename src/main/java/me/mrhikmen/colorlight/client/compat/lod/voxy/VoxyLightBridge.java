package me.mrhikmen.colorlight.client.compat.lod.voxy;

import me.mrhikmen.colorlight.client.ColorLightClient;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngineHolder;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL44C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

import java.io.InputStream;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Coloured light for Voxy's distant LODs.
 * <p>
 * Voxy draws with its own OpenGL pipeline, so none of the Sodium hooks apply. Two small pieces connect to it:
 * <ol>
 *     <li>{@link #patch}: Voxy builds its shader text with {@code ShaderLoader.parse}; a mixin hands the result of
 *     that to us and we add the {@code colorlight_lod.glsl} block and redirect one lighting call and one line of
 *     {@code setupQuad} to it. If any of those anchors is not found (Voxy changed), nothing is touched.</li>
 *     <li>{@link #onFrame}: once per frame a shader storage buffer with the {@link VoxyLightCache} grids is
 *     updated and bound. The buffer is created and bound through plain GL calls and does not touch any state
 *     that Minecraft's renderer tracks, except for the one indexed SSBO binding that we pick for ourselves.</li>
 * </ol>
 * <p>
 * The buffer holds a header, a table "32-block column -> (first source, source count)" and the flat source list
 * ({@code x, y, z, rgb}) that {@link VoxyLightCache} builds. {@code colorlight_lod.glsl} adds up the sources around
 * every LOD block itself. Keep the numbers below in sync with {@link VoxyLightCache} and that shader (they are
 * plain literals, not references, on purpose: this class is loaded whether or not Voxy is installed, and reading a
 * static field of {@code VoxyLightCache} would force that class - and the Voxy classes it mentions - to load).
 */
public final class VoxyLightBridge {

    private static final int HEADER_INTS = 16;
    private static final int GRID_CELLS = 512; // 16x16-block cells per side of the toroidal table
    private static final int MAX_SOURCES = 131072;
    private static final int CELL_INTS = GRID_CELLS * GRID_CELLS * 2;
    private static final int SOURCE_OFFSET_INTS = HEADER_INTS + CELL_INTS;
    /** Where the eight 33x33x33 distance tables of the propagation methods start (see LodMethodTables; the shader has the same literal). */
    private static final int TABLES_OFFSET_INTS = SOURCE_OFFSET_INTS + MAX_SOURCES * 4;
    private static final int TOTAL_INTS = TABLES_OFFSET_INTS + 8 * 35937;
    private static long uploadedTablesKey = Long.MIN_VALUE;
    /** Rebuild the table once the camera has moved this many columns (the shader keeps the same margin). */
    private static final int DRIFT_COLS = 8;

    private static volatile boolean patched;
    private static volatile boolean vk;
    private static int binding = -1;

    // Vulkan backend: CPU-side copy of the buffer (off-heap, same layout as the GL buffer), uploaded into the GPU buffer of
    // the current frame by VoxyVkLight when Voxy records its terrain draws. Render thread only.
    static final int[] vkHeader = new int[HEADER_INTS];
    static long vkMirror;          // address of TOTAL_INTS ints, 0 until the first table arrives
    static long vkStamp;           // bumped whenever cells/sources in the mirror change
    static int vkSourceCount;      // sources currently in the mirror
    static long vkTablesStamp;     // bumped whenever the method tables in the mirror change
    private static long vkTablesKey = Long.MIN_VALUE;

    private static int buffer;
    private static final int[] header = new int[HEADER_INTS];
    private static final int[] lastHeader = new int[HEADER_INTS];
    private static boolean headerWritten;
    private static IntBuffer headerBytes;

    private static VoxyLightCache lastCache;
    private static long uploadedVersion = -1L;
    private static int uploadedColX, uploadedColZ;
    private static long lastUploadNanos;
    /** At most ~6 uploads per second (up to ~2.5 MB each): while sources are being read, the version keeps changing. */
    private static final long MIN_UPLOAD_GAP_NANOS = 160_000_000L;

    // ------------------------------------------------------------------------------------------------------
    // Shader text
    // ------------------------------------------------------------------------------------------------------

    private static final String ANCHOR_FUNCTION = "uvec3 makeRemainingAttributes(";
    private static final String ANCHOR_LIGHTING = "vec4 tinting = getLighting(lighting);";
    private static final String ANCHOR_SETUP = "    if (generateAttributes) {";

    /** A shader piece: what comes before the shared code, the shared code, and what comes after it. */
    private static String assemble(String file, int binding) throws Exception {
        String piece = readResource("/assets/colorlight/shaders/voxy/" + file);
        String common = readResource("/assets/colorlight/shaders/voxy/colorlight_lod_common.glsl");
        int i = piece.indexOf("//@COMMON@");
        String before = i < 0 ? piece : piece.substring(0, i);
        String after = i < 0 ? "" : piece.substring(i + "//@COMMON@".length());
        return (before + common + after).replace("__CL_BINDING__", Integer.toString(binding));
    }

    /** True while the LOD shader is the per-quad one ; {@code LodSplit} only splits quads then. */
    public static boolean usesQuadShader() {
        return patched;
    }

    /**
     * @param shaderSource what Voxy's {@code ShaderLoader.parse} produced
     * @return the changed source, or null to leave it as it is
     */
    public static String patch(String shaderSource) {
        if (shaderSource == null || !shaderSource.contains(ANCHOR_FUNCTION))
            return null; // not the LOD vertex shader

        try {
            if (!shaderSource.contains(ANCHOR_LIGHTING) || !shaderSource.contains(ANCHOR_SETUP)
                    || !shaderSource.contains("LIGHTING_SAMPLER_BINDING") || !shaderSource.contains("baseSectionPos")
                    || !shaderSource.contains("ivec2 quadSize = extractSize(rawQuad);") || !shaderSource.contains("uint face = extractFace(rawQuad);")) {
                ColorLightClient.LOGGER.warn("[ColorLight] Voxy's LOD shader looks different from the one this was written for; far light colours are off");
                return null;
            }

            boolean useVk = isVulkanBackend();
            // Vulkan: Voxy's terrain pipelines use bindings 0,1,3,4,5,8,9,10; ours is added next to them by VkTerrainRendererMixin.
            // OpenGL: ask the driver. There is NO GL context at all while Minecraft runs on Vulkan, so never touch GL there.
            int chosen = useVk ? VoxyVkLight.BINDING : chooseBinding();
            if (chosen < 0) {
                ColorLightClient.LOGGER.warn("[ColorLight] Not enough shader storage bindings for far light colours");
                return null;
            }

            String block = assemble("colorlight_lod.glsl", chosen);

            String out = shaderSource;
            out = replaceFirst(out, ANCHOR_FUNCTION, block + "\n" + ANCHOR_FUNCTION);
            out = replaceFirst(out, ANCHOR_LIGHTING, "vec4 tinting = cl_getLighting(lighting);");
            out = replaceFirst(out, ANCHOR_SETUP, "    cl_setQuad(extractPos(rawQuad), lodScale, baseSection, lodLevel, face, quadSize);\n" + ANCHOR_SETUP);

            binding = chosen;
            vk = useVk;
            patched = true;
            if (!useVk) {
                ensureBuffer(); // so the very first draw already finds a bound (disabled) buffer
                GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, buffer);
            }
            ColorLightClient.LOGGER.info("[ColorLight] Far light colours added to Voxy's LOD shader ({} backend, SSBO binding {})",
                    useVk ? "Vulkan" : "OpenGL", chosen);
            return out;
        } catch (Throwable t) {
            ColorLightClient.LOGGER.error("[ColorLight] Could not add far light colours to Voxy: {}", t.toString());
            return null;
        }
    }

    private static String replaceFirst(String text, String target, String replacement) {
        int i = text.indexOf(target);
        if (i < 0)
            throw new IllegalStateException("anchor not found: " + target);
        return text.substring(0, i) + replacement + text.substring(i + target.length());
    }

    private static String readResource(String path) throws Exception {
        try (InputStream in = VoxyLightBridge.class.getResourceAsStream(path)) {
            if (in == null)
                throw new IllegalStateException("missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** True when Voxy renders through Vulkan (it follows Minecraft's backend). Reflection: older Voxy has no such class. */
    private static boolean isVulkanBackend() {
        try {
            Class<?> c = Class.forName("me.cortex.voxy.client.core.vk.VulkanBackend");
            return (Boolean) c.getMethod("shouldUseVulkan").invoke(null);
        } catch (Throwable t) {
            return false;
        }
    }

    /** True once the Vulkan variant of the shader patch is in (the Vulkan mixins ask this). */
    public static boolean isPatchedForVulkan() {
        return patched && vk;
    }

    /** A binding index high enough to stay clear of Voxy's own (it uses the low ones). */
    private static int chooseBinding() {
        int max = GL11C.glGetInteger(GL43C.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS);
        int chosen = Math.min(max - 1, 30);
        return chosen >= 12 ? chosen : -1;
    }

    // ------------------------------------------------------------------------------------------------------
    // Per frame
    // ------------------------------------------------------------------------------------------------------

    /** Called every frame from the render thread (before the world is drawn) with the camera's block position. */
    public static void onFrame(int camBlockX, int camBlockZ) {
        if (!patched)
            return;

        if (vk) {
            try {
                onFrameVulkan(camBlockX, camBlockZ);
            } catch (Throwable t) {
                ColorLightClient.LOGGER.error("[ColorLight] Far light colours for Voxy (Vulkan) turned off: {}", t.toString());
                patched = false;
                vkHeader[2] = 0;
            }
            return;
        }

        try {
            ensureBuffer();
            // bind every frame: an indexed binding is global GL state, and this is cheap
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, buffer);

            VoxyLightCache cache = LodLight.cache();
            boolean enabled = cache != null && ColorLightClient.config.VOXY_LIGHT && ColorLightClient.config.ENABLE
                    && ColorLightEngineHolder.get() != null;

            if (enabled)
                uploadMethodTables();

            writeHeader(camBlockX, camBlockZ, enabled);

            if (enabled)
                uploadOneLevel(cache, camBlockX, camBlockZ);
        } catch (Throwable t) {
            ColorLightClient.LOGGER.error("[ColorLight] Far light colours for Voxy turned off: {}", t.toString());
            patched = false; // the shader then just sees "disabled" (header stays as last written)
            disableInShader();
        }
    }

    /** The distance tables of the propagation methods, uploaded once and again whenever the methods or the default change. */
    private static void uploadMethodTables() {
        long key = me.mrhikmen.colorlight.client.compat.lod.LodMethodTables.key();
        if (key == uploadedTablesKey)
            return;
        int[] tables = me.mrhikmen.colorlight.client.compat.lod.LodMethodTables.build();
        GL45C.glNamedBufferSubData(buffer, (long) TABLES_OFFSET_INTS * Integer.BYTES, tables);
        uploadedTablesKey = key;
    }

    /**
     * Vulkan: only updates the CPU mirror; VoxyVkLight copies it into this frame's GPU buffer at draw time. The table
     * is built by the cache's filler thread exactly as for OpenGL; a finished one is copied into the mirror here.
     */
    private static void onFrameVulkan(int camX, int camZ) {
        VoxyLightCache cache = LodLight.cache();
        boolean enabled = cache != null && ColorLightClient.config.VOXY_LIGHT && ColorLightClient.config.ENABLE
                && ColorLightEngineHolder.get() != null;

        fillHeader(vkHeader, camX, camZ, enabled);
        if (!enabled)
            return;

        if (cache != lastCache) {
            lastCache = cache;
            uploadedVersion = -1L;
        }

        long now = System.nanoTime();
        long[] stamp = new long[4];
        if (now - lastUploadNanos >= MIN_UPLOAD_GAP_NANOS && cache.pollFilled(stamp)) {
            if (vkMirror == 0L) {
                vkMirror = MemoryUtil.nmemCalloc(TOTAL_INTS, Integer.BYTES);
                if (vkMirror == 0L)
                    throw new OutOfMemoryError("cannot allocate far light mirror");
            }
            MemoryUtil.memCopy(MemoryUtil.memAddress(cache.cellBuffer()), vkMirror + (long) HEADER_INTS * Integer.BYTES,
                    (long) CELL_INTS * Integer.BYTES);
            int count = (int) stamp[3];
            if (count > 0)
                MemoryUtil.memCopy(MemoryUtil.memAddress(cache.sourceBuffer(count)), vkMirror + (long) SOURCE_OFFSET_INTS * Integer.BYTES,
                        (long) count * 4 * Integer.BYTES);
            vkSourceCount = count;
            vkStamp++;
            uploadedVersion = stamp[0];
            uploadedColX = (int) stamp[1];
            uploadedColZ = (int) stamp[2];
            cache.releaseFilled();
            lastUploadNanos = now;
        }

        // the method tables go into the mirror once the mirror exists (and again when the methods change); a slot of the
        // GPU buffer takes them over together with the cells, see VoxyVkLight
        if (vkMirror != 0L) {
            long key = me.mrhikmen.colorlight.client.compat.lod.LodMethodTables.key();
            if (key != vkTablesKey) {
                int[] tables = me.mrhikmen.colorlight.client.compat.lod.LodMethodTables.build();
                MemoryUtil.memIntBuffer(vkMirror + (long) TABLES_OFFSET_INTS * Integer.BYTES, tables.length).put(tables);
                vkTablesKey = key;
                vkTablesStamp++;
            }
        }

        boolean stale = cache.version() != uploadedVersion
                || Math.abs((camX >> 5) - uploadedColX) > DRIFT_COLS
                || Math.abs((camZ >> 5) - uploadedColZ) > DRIFT_COLS;
        if (stale)
            cache.requestFill(camX, camZ);
    }

    static int headerInts() { return HEADER_INTS; }
    static int cellInts() { return CELL_INTS; }
    static int sourceOffsetInts() { return SOURCE_OFFSET_INTS; }
    static int totalInts() { return TOTAL_INTS; }
    static int tablesOffsetInts() { return TABLES_OFFSET_INTS; }
    static int tablesInts() { return TOTAL_INTS - TABLES_OFFSET_INTS; }

    private static void ensureBuffer() {
        if (buffer != 0)
            return;
        uploadedTablesKey = Long.MIN_VALUE;
        buffer = GL45C.glCreateBuffers();
        GL45C.glNamedBufferStorage(buffer, (long) TOTAL_INTS * Integer.BYTES, GL44C.GL_DYNAMIC_STORAGE_BIT);
        // a new buffer's contents are undefined: start from all zeros (no cells, no sources)
        GL45C.glClearNamedBufferData(buffer, GL30C.GL_R32UI, GL30C.GL_RED_INTEGER, GL11C.GL_UNSIGNED_INT, (java.nio.ByteBuffer) null);
        headerBytes = MemoryUtil.memAllocInt(HEADER_INTS);
        headerWritten = false;
        headerBytes.clear();
        for (int i = 0; i < HEADER_INTS; i++)
            headerBytes.put(0);
        headerBytes.flip();
        GL45C.glNamedBufferSubData(buffer, 0L, headerBytes); // header[2] = 0: disabled until the first real frame
        uploadedVersion = -1L;
        lastCache = null;
    }

    private static void fillHeader(int[] h, int camX, int camZ, boolean enabled) {
        Arrays.fill(h, 0);
        h[0] = camX;
        h[1] = camZ;
        h[2] = enabled ? 1 : 0;
        h[3] = Float.floatToRawIntBits(Math.max(0f, ColorLightClient.config.TINT_GAMMA));
        h[4] = Float.floatToRawIntBits(1.0f);
        h[7] = me.mrhikmen.colorlight.client.core.light.color.ColorLightUtil.additiveMix() ? 1 : 0;
        h[5] = Math.max(1, Math.min(16, ColorLightEngineHolder.getMaxRangeBlocks())); // light reach in blocks (the shader reads the 3x3 cells of 16 blocks around a block)
    }

    private static void writeHeader(int camX, int camZ, boolean enabled) {
        fillHeader(header, camX, camZ, enabled);

        if (headerWritten && Arrays.equals(header, lastHeader))
            return;

        headerBytes.clear();
        headerBytes.put(header).flip();
        GL45C.glNamedBufferSubData(buffer, 0L, headerBytes);
        System.arraycopy(header, 0, lastHeader, 0, HEADER_INTS);
        headerWritten = true;
    }

    /**
     * The table is built on the cache's filler thread ({@link VoxyLightCache#requestFill}); here a finished one is
     * just copied to the GPU, at most one per {@link #MIN_UPLOAD_GAP_NANOS}.
     */
    private static void uploadOneLevel(VoxyLightCache cache, int camX, int camZ) {
        if (cache != lastCache) {
            lastCache = cache;
            uploadedVersion = -1L;
        }

        long now = System.nanoTime();
        long[] stamp = new long[4];
        if (now - lastUploadNanos >= MIN_UPLOAD_GAP_NANOS && cache.pollFilled(stamp)) {
            GL45C.glNamedBufferSubData(buffer, (long) HEADER_INTS * Integer.BYTES, cache.cellBuffer());
            int count = (int) stamp[3];
            if (count > 0)
                GL45C.glNamedBufferSubData(buffer, (long) SOURCE_OFFSET_INTS * Integer.BYTES, cache.sourceBuffer(count));
            uploadedVersion = stamp[0];
            uploadedColX = (int) stamp[1];
            uploadedColZ = (int) stamp[2];
            cache.releaseFilled();
            lastUploadNanos = now;
        }

        boolean stale = cache.version() != uploadedVersion
                || Math.abs((camX >> 5) - uploadedColX) > DRIFT_COLS
                || Math.abs((camZ >> 5) - uploadedColZ) > DRIFT_COLS;
        if (stale)
            cache.requestFill(camX, camZ);
    }

    private static void disableInShader() {
        try {
            if (buffer != 0) {
                headerBytes.clear();
                for (int i = 0; i < HEADER_INTS; i++)
                    headerBytes.put(0);
                headerBytes.flip();
                GL45C.glNamedBufferSubData(buffer, 0L, headerBytes); // header[2] = 0: shader falls back to Voxy's lighting
            }
        } catch (Throwable ignored) {
        }
    }

    private VoxyLightBridge() {
    }
}
