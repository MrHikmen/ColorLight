package me.mrhikmen.colorlight.client.compat.lod.voxy;

import me.mrhikmen.colorlight.client.ColorLightClient;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngineHolder;
import me.mrhikmen.colorlight.client.compat.lod.LodDiag;
import me.mrhikmen.colorlight.client.compat.lod.LodLightStore;
import me.mrhikmen.colorlight.client.compat.lod.LodLightVolume;
import me.mrhikmen.colorlight.client.compat.lod.LodMethodTables;
import me.mrhikmen.colorlight.client.compat.lod.LodVolumeBuilder;

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

    /** DIAGNOSTIC: -Dcolorlight.diag.noShader=true makes the LOD shader skip the light (header enabled = 0). */
    static final boolean DIAG_NO_SHADER = Boolean.getBoolean("colorlight.diag.noShader");
    /** DIAGNOSTIC: -Dcolorlight.diag.noUpload=true never copies the cell/source tables to the GPU. */
    static final boolean DIAG_NO_UPLOAD = Boolean.getBoolean("colorlight.diag.noUpload");

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
        return patched && !volumeMode;
    }

    // Volume mode (OpenGL): the light is read per pixel from a volume the CPU keeps, instead of being added up per quad
    // in the vertex shader. Three more anchors in the vertex shader, three in the fragment shader.
    private static volatile boolean volumeMode;
    private static volatile boolean volumeFailed;
    private static volatile Boolean volumeDecision;
    private static int glBinding = -2;

    /**
     * True when the volume mode is used: OpenGL, not switched off with -Dcolorlight.voxyLegacy=true, and Voxy's own
     * shader files contain every place that has to be changed (so the vertex and the fragment shader always agree).
     */
    private static boolean volumeWanted() {
        if (volumeFailed)
            return false;
        Boolean d = volumeDecision;
        if (d == null) {
            boolean want = !Boolean.getBoolean("colorlight.voxyLegacy") && !(isVulkanBackend() && Boolean.getBoolean("colorlight.voxyVkLegacy")) && volumeAnchorsInVoxyFiles();
            volumeDecision = d = want;
            ColorLightClient.LOGGER.info("[ColorLight] Far light for Voxy: {} mode", want ? "volume" : "legacy (per-quad)");
        }
        return d;
    }

    /** Looks at Voxy's raw shader files (not the parsed text), so the answer does not depend on which shader is parsed first. */
    private static boolean volumeAnchorsInVoxyFiles() {
        try {
            Class<?> loader = Class.forName("me.cortex.voxy.client.core.gl.shader.ShaderLoader");
            String vert = readVoxyAsset(loader, "/assets/voxy/shaders/lod/gl46/quads3.vert");
            String util = readVoxyAsset(loader, "/assets/voxy/shaders/lod/quad_util.glsl");
            String frag = readVoxyAsset(loader, "/assets/voxy/shaders/lod/gl46/quads.frag");
            boolean ok = VoxyVolumePatch.filesHaveAnchors(vert, util, frag);
            if (!ok)
                ColorLightClient.LOGGER.warn("[ColorLight] Voxy's LOD shaders look different from the ones the volume mode was written for; using the legacy mode");
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String readVoxyAsset(Class<?> loader, String path) throws Exception {
        try (InputStream in = loader.getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static boolean isLodFragment(String src) {
        return VoxyVolumePatch.isLodFragment(src);
    }

    /** The SSBO binding both shaders use (OpenGL), chosen once. */
    private static int glBinding() {
        if (glBinding == -2)
            glBinding = chooseBinding();
        return glBinding;
    }

    /** The fragment shader of the LODs (volume mode only; null leaves it as it is). */
    private static String patchFragment(String src) {
        if (!volumeWanted())
            return null;
        try {
            // Vulkan: the binding is fixed (VoxyVkLight.BINDING), and there is no GL context to ask
            int b = isVulkanBackend() ? VoxyVkLight.BINDING : glBinding();
            if (b < 0)
                return null;
            String out = VoxyVolumePatch.fragment(src, readResource("/assets/colorlight/shaders/voxy/colorlight_vol_frag.glsl"), b);
            ColorLightClient.LOGGER.info("[ColorLight] Far light volume added to Voxy's LOD fragment shader");
            return out;
        } catch (Throwable t) {
            volumeFailed = true;
            ColorLightClient.LOGGER.error("[ColorLight] Could not add the far light volume to Voxy's fragment shader: {}", t.toString());
            return null;
        }
    }

    private static String patchVertexVolume(String src, int chosen) throws Exception {
        return VoxyVolumePatch.vertex(src, readResource("/assets/colorlight/shaders/voxy/colorlight_vol_vert.glsl").replace("__CL_BINDING__", Integer.toString(chosen)));
    }

    /**
     * @param shaderSource what Voxy's {@code ShaderLoader.parse} produced
     * @return the changed source, or null to leave it as it is
     */
    public static String patch(String shaderSource) {
        if (shaderSource == null)
            return null;
        if (isLodFragment(shaderSource))
            return patchFragment(shaderSource);
        if (!shaderSource.contains(ANCHOR_FUNCTION))
            return null; // not the LOD vertex shader

        try {
            if (!shaderSource.contains(ANCHOR_LIGHTING) || !shaderSource.contains(ANCHOR_SETUP)
                    || !shaderSource.contains("LIGHTING_SAMPLER_BINDING") || !shaderSource.contains("baseSectionPos")
                    || !shaderSource.contains("ivec2 quadSize = extractSize(rawQuad);") || !shaderSource.contains("uint face = extractFace(rawQuad);")) {
                ColorLightClient.LOGGER.warn("[ColorLight] Voxy's LOD shader looks different from the one this was written for; far light colours are off");
                volumeFailed = true;
                return null;
            }

            boolean useVk = isVulkanBackend();
            boolean volume = volumeWanted();
            // Vulkan: Voxy's terrain pipelines use bindings 0,1,3,4,5,8,9,10; ours is added next to them by VkTerrainRendererMixin.
            // OpenGL: ask the driver. There is NO GL context at all while Minecraft runs on Vulkan, so never touch GL there.
            int chosen = useVk ? VoxyVkLight.BINDING : glBinding();
            if (chosen < 0) {
                ColorLightClient.LOGGER.warn("[ColorLight] Not enough shader storage bindings for far light colours");
                volumeFailed = true;
                return null;
            }

            String out;
            if (volume) {
                out = patchVertexVolume(shaderSource, chosen);
            } else {
                String block = assemble("colorlight_lod.glsl", chosen);

                out = shaderSource;
                out = replaceFirst(out, ANCHOR_FUNCTION, block + "\n" + ANCHOR_FUNCTION);
                out = replaceFirst(out, ANCHOR_LIGHTING, "vec4 tinting = cl_getLighting(lighting);");
                out = replaceFirst(out, ANCHOR_SETUP, "    cl_setQuad(extractPos(rawQuad), lodScale, baseSection, lodLevel, face, quadSize);\n" + ANCHOR_SETUP);
            }

            binding = chosen;
            vk = useVk;
            volumeMode = volume;
            patched = true;
            if (!useVk) {
                if (volume)
                    ensureVolumeBuffer(); // so the very first draw already finds a bound (disabled) buffer
                else
                    ensureBuffer();
                GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, buffer);
            }
            ColorLightClient.LOGGER.info("[ColorLight] Far light colours added to Voxy's LOD shader ({} backend, {} mode, SSBO binding {})",
                    useVk ? "Vulkan" : "OpenGL", volume ? "volume" : "per-quad", chosen);
            return out;
        } catch (Throwable t) {
            volumeFailed = true;
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
        LodDiag.frame();
        if (!patched)
            return;

        if (volumeMode) {
            if (vk && vkVolumeFailed)
                return;
            try {
                onFrameVolume(camBlockX, camBlockZ);
            } catch (Throwable t) {
                ColorLightClient.LOGGER.error("[ColorLight] Far light colours for Voxy turned off: {}", t.toString());
                if (vk) {
                    // the pipelines were built with the light buffer's binding: it has to stay bound, only "disabled"
                    vkVolumeFailed = true;
                } else {
                    patched = false;
                }
                disableVolume();
            }
            return;
        }

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

            if (enabled && !DIAG_NO_UPLOAD)
                uploadMethodTables();

            writeHeader(camBlockX, camBlockZ, enabled);

            if (enabled && !DIAG_NO_UPLOAD)
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
        h[2] = (enabled && !DIAG_NO_SHADER) ? 1 : 0;
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
        LodDiag.headerWrites.incrementAndGet();
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
            LodDiag.uploads.incrementAndGet();
            LodDiag.uploadBytes.addAndGet((long) (CELL_INTS + count * 4) * Integer.BYTES);
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

    // ------------------------------------------------------------------------------------------------------
    // Volume mode (OpenGL): the CPU keeps the light as a sparse volume (LodLightVolume, built by LodVolumeBuilder), this
    // class mirrors it into the SSBO that the vertex and fragment shaders read.
    // ------------------------------------------------------------------------------------------------------

    /** Vulkan guarantees a storage buffer range of 128 MiB; used when the device's real limit cannot be read. */
    private static final long VK_GUARANTEED_BYTES = 128L << 20;
    /** The largest storage buffer the graphics API allows, bytes (0 = not asked yet). */
    private static long hwLimitBytes;
    /** What the running volume (and its GPU buffer) was made for. */
    private static int volumeTilesInUse;

    /** Tiles for a given memory budget in MB: the hash table is paid for first, the rest is the pool of tiles. */
    static int tilesForMegabytes(int mb) {
        long bytes = (long) mb << 20;
        long poolBytes = bytes - (long) LodLightVolume.POOL_OFFSET_INTS * Integer.BYTES;
        long tiles = poolBytes / ((long) LodLightVolume.TILE_VOXELS * Integer.BYTES);
        return (int) Math.max(LodLightVolume.MIN_TILES, Math.min(LodLightVolume.MAX_TILES, tiles));
    }

    /** The most tiles the graphics card can address in one storage buffer. */
    static int hardwareTiles() {
        long limit = hwLimitBytes;
        if (limit <= 0) {
            limit = vk ? VoxyVkLight.maxStorageBytes() : glStorageLimit();
            if (limit <= 0)
                limit = VK_GUARANTEED_BYTES; // not known yet (Vulkan before its first frame): the guarantee, asked again later
            else
                hwLimitBytes = limit;
        }
        long pool = limit - (long) LodLightVolume.POOL_OFFSET_INTS * Integer.BYTES;
        return (int) Math.max(LodLightVolume.MIN_TILES, Math.min(LodLightVolume.MAX_TILES, pool / ((long) LodLightVolume.TILE_VOXELS * Integer.BYTES)));
    }

    private static long glStorageLimit() {
        try {
            return GL45C.glGetInteger64(GL43C.GL_MAX_SHADER_STORAGE_BLOCK_SIZE);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** How many tiles the volume should hold now: the setting (MB), or -Dcolorlight.lod.maxTiles, limited by the hardware. */
    private static int volumeTiles() {
        Integer forced = Integer.getInteger("colorlight.lod.maxTiles");
        int want = forced != null
                ? Math.max(LodLightVolume.MIN_TILES, Math.min(LodLightVolume.MAX_TILES, forced))
                : tilesForMegabytes(Math.max(32, Math.min(512, ColorLightClient.config.VOXY_LIGHT_MEMORY_MB)));
        return Math.min(want, hardwareTiles());
    }

    // Vulkan: VoxyVkLight owns the GPU buffer and drains the volume into it while the frame's commands are recorded
    private static volatile boolean vkVolumeFailed;
    private static volatile boolean vkVolumeReset = true;
    private static final int TILES_PER_FRAME = 96;
    private static final int TILES_PER_FRAME_CATCHUP = 256;
    private static final int ENTRIES_PER_FRAME = 512;
    /** Tiles with consecutive slots are written with one call, this many at most. */
    private static final int RUN_TILES = 256;

    private static LodLightStore volumeStore;
    private static LodLightVolume volume;
    private static LodVolumeBuilder volumeBuilder;
    private static LodLightVolume.Params volumeParams;
    private static long volumeParamsKey = Long.MIN_VALUE;
    private static final int[] volHeader = new int[LodLightVolume.HEADER_INTS];
    private static final int[] volLastHeader = new int[LodLightVolume.HEADER_INTS];
    private static boolean volHeaderWritten;
    private static IntBuffer volHeaderBuf;
    private static IntBuffer volRun;
    private static IntBuffer volEntry;
    private static java.nio.ByteBuffer volMinusOne;
    private static boolean volBufferReady;
    private static int volPending;
    private static final VolumeSink VOLUME_SINK = new VolumeSink();

    private static void ensureVolumeBuffer() {
        if (volBufferReady)
            return;
        buffer = GL45C.glCreateBuffers();
        volumeTilesInUse = volumeTiles();
        long bytes = (long) LodLightVolume.totalInts(volumeTilesInUse) * Integer.BYTES;
        GL45C.glNamedBufferStorage(buffer, bytes, GL44C.GL_DYNAMIC_STORAGE_BIT);
        // a new buffer's contents are undefined: all zeros (header disabled, every tile empty)
        GL45C.glClearNamedBufferData(buffer, GL30C.GL_R32UI, GL30C.GL_RED_INTEGER, GL11C.GL_UNSIGNED_INT, (java.nio.ByteBuffer) null);
        volHeaderBuf = MemoryUtil.memAllocInt(LodLightVolume.HEADER_INTS);
        volRun = MemoryUtil.memAllocInt(RUN_TILES * LodLightVolume.TILE_VOXELS);
        volEntry = MemoryUtil.memAllocInt(4);
        volMinusOne = MemoryUtil.memAlloc(4);
        volMinusOne.putInt(0, -1);
        volBufferReady = true;
        volHeaderWritten = false;
        resetGpuHash();
        ColorLightClient.LOGGER.info("[ColorLight] Far light volume buffer: {} MB, up to {} tiles", bytes / (1024 * 1024), volumeTilesInUse);
    }

    /** Every hash entry "empty" (slot -1): no tile is found by the shader. */
    private static void resetGpuHash() {
        GL45C.glClearNamedBufferSubData(buffer, GL30C.GL_R32I, (long) LodLightVolume.HASH_OFFSET_INTS * Integer.BYTES,
                (long) LodLightVolume.HASH_ENTRIES * 4 * Integer.BYTES, GL30C.GL_RED_INTEGER, GL11C.GL_INT, volMinusOne);
    }

    /** The setting changed: the OpenGL buffer is made again, in the new size. */
    private static void releaseVolumeBuffer() {
        if (!volBufferReady)
            return;
        GL45C.glDeleteBuffers(buffer);
        buffer = 0;
        MemoryUtil.memFree(volHeaderBuf);
        MemoryUtil.memFree(volRun);
        MemoryUtil.memFree(volEntry);
        MemoryUtil.memFree(volMinusOne);
        volHeaderBuf = null;
        volRun = null;
        volEntry = null;
        volMinusOne = null;
        volBufferReady = false;
    }

    private static void startVolume(LodLightStore store) {
        if (vk || volumeTilesInUse == 0)
            volumeTilesInUse = volumeTiles(); // OpenGL: the buffer already made decides
        volume = new LodLightVolume(volumeTilesInUse);
        volumeBuilder = new LodVolumeBuilder(store, volume);
        volumeStore = store;
        volumeParamsKey = Long.MIN_VALUE;
        volumeParams = null;
        volPending = 0;
        resetGpuHashAny();
    }

    private static void teardownVolume() {
        if (volumeBuilder != null) {
            volumeBuilder.shutdown();
            volumeBuilder = null;
        }
        volume = null;
        volumeStore = null;
        volumeParams = null;
        volumeParamsKey = Long.MIN_VALUE;
        volPending = 0;
        if (volBufferReady || vk)
            resetGpuHashAny(); // nothing of the old world may show
    }

    /** The GPU's hash table is emptied: now (OpenGL) or by VoxyVkLight before its next drain (Vulkan). */
    private static void resetGpuHashAny() {
        if (vk)
            vkVolumeReset = true;
        else if (volBufferReady)
            resetGpuHash();
    }

    private static void onFrameVolume(int camX, int camZ) {
        if (volumeTilesInUse != 0 && volumeTiles() != volumeTilesInUse) {
            // the memory setting changed: the volume starts over in a buffer of the new size
            teardownVolume();
            if (!vk)
                releaseVolumeBuffer();
            volumeTilesInUse = 0;
        }
        if (!vk) {
            ensureVolumeBuffer();
            // bind every frame: an indexed binding is global GL state, and this is cheap
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, buffer);
        }

        VoxyLightCache cache = LodLight.cache();
        boolean enabled = cache != null && ColorLightClient.config.VOXY_LIGHT && ColorLightClient.config.ENABLE
                && ColorLightEngineHolder.get() != null;
        LodLightStore store = enabled ? cache.store() : null;
        if (store != volumeStore) {
            teardownVolume();
            if (store != null)
                startVolume(store);
        }

        writeVolumeHeader(enabled && volume != null);
        if (!enabled || volume == null)
            return;

        refreshVolumeParams();
        volumeBuilder.tick(camX, camZ);

        if (!DIAG_NO_UPLOAD && !vk) { // Vulkan drains in VoxyVkLight, where the frame's commands are
            int budget = volPending > 2000 ? TILES_PER_FRAME_CATCHUP : TILES_PER_FRAME;
            volPending = volume.drain(VOLUME_SINK, budget, ENTRIES_PER_FRAME);
            VOLUME_SINK.flush();
        }
        if (LodDiag.ENABLED) {
            LodDiag.volTiles = volume.tileCount();
            LodDiag.volPending = volPending;
            LodDiag.volOverflow += volume.takeOverflow();
        }
    }

    /** What turns sources into colours: rebuilt (and the whole volume with it) when the method tables, the reach or the mixing change. */
    private static void refreshVolumeParams() {
        int reach = Math.max(1, Math.min(16, ColorLightEngineHolder.getMaxRangeBlocks()));
        boolean additive = me.mrhikmen.colorlight.client.core.light.color.ColorLightUtil.additiveMix();
        boolean whiteOverlay = me.mrhikmen.colorlight.client.core.light.LightTuning.whiteOverlay;
        int whiteSpread = me.mrhikmen.colorlight.client.core.light.LightTuning.whiteSpread;
        int whiteCover = me.mrhikmen.colorlight.client.core.light.LightTuning.whiteCoverPercent;
        long key = LodMethodTables.key() * 31L + reach * 2L + (additive ? 1L : 0L);
        key = key * 131L + (whiteOverlay ? 1L : 0L);
        key = key * 131L + whiteSpread;
        key = key * 131L + whiteCover;
        if (volumeParams == null || key != volumeParamsKey) {
            int[] bits = LodMethodTables.build();
            float[][] d = new float[LodMethodTables.TABLES][LodMethodTables.TABLE_INTS];
            for (int t = 0; t < LodMethodTables.TABLES; t++)
                for (int i = 0; i < LodMethodTables.TABLE_INTS; i++)
                    d[t][i] = Float.intBitsToFloat(bits[t * LodMethodTables.TABLE_INTS + i]);
            volumeParams = new LodLightVolume.Params(d, 255f / reach, additive, whiteOverlay, whiteSpread, whiteCover, key);
            volumeParamsKey = key;
        }
        volumeBuilder.setParams(volumeParams);
    }

    private static void writeVolumeHeader(boolean enabled) {
        Arrays.fill(volHeader, 0);
        volHeader[2] = (enabled && !DIAG_NO_SHADER) ? 1 : 0;
        volHeader[3] = Float.floatToRawIntBits(Math.max(0f, ColorLightClient.config.TINT_GAMMA));
        volHeader[4] = Float.floatToRawIntBits(1.0f);
        if (vk)
            return; // VoxyVkLight copies volHeader into the buffer when the frame is drawn
        if (volHeaderWritten && Arrays.equals(volHeader, volLastHeader))
            return;
        volHeaderBuf.clear();
        volHeaderBuf.put(volHeader).flip();
        GL45C.glNamedBufferSubData(buffer, 0L, volHeaderBuf);
        System.arraycopy(volHeader, 0, volLastHeader, 0, volHeader.length);
        volHeaderWritten = true;
    }

    /** After a failure: the shader sees "disabled" and shows Voxy's own lighting. */
    private static void disableVolume() {
        try {
            if (vk)
                Arrays.fill(volHeader, 0); // VoxyVkLight writes this header: [2] = 0, disabled
            if (volBufferReady) {
                volHeaderBuf.clear();
                for (int i = 0; i < LodLightVolume.HEADER_INTS; i++)
                    volHeaderBuf.put(0);
                volHeaderBuf.flip();
                GL45C.glNamedBufferSubData(buffer, 0L, volHeaderBuf);
            }
            if (volumeBuilder != null)
                volumeBuilder.shutdown();
        } catch (Throwable ignored) {
        }
    }

    // ---- what VoxyVkLight (Vulkan) reads ----

    static boolean vkVolumeActive() {
        return patched && vk && volumeMode;
    }

    static LodLightVolume vkVolume() {
        return volume;
    }

    static int[] vkVolumeHeader() {
        return volHeader;
    }

    static int vkVolumeTiles() {
        return volumeTilesInUse != 0 ? volumeTilesInUse : volumeTiles();
    }

    static boolean takeVkVolumeReset() {
        boolean r = vkVolumeReset;
        vkVolumeReset = false;
        return r;
    }

    static int vkVolumeBudget() {
        return volPending > 2000 ? TILES_PER_FRAME_CATCHUP : TILES_PER_FRAME;
    }

    static int vkVolumeEntryBudget() {
        return ENTRIES_PER_FRAME;
    }

    static void vkVolumeDrained(int pending) {
        volPending = pending;
        if (LodDiag.ENABLED && volume != null) {
            LodDiag.volTiles = volume.tileCount();
            LodDiag.volPending = pending;
            LodDiag.volOverflow += volume.takeOverflow();
        }
    }

    /** Writes what the volume hands over into the buffer; tiles with consecutive slots go out in one call. */
    private static final class VolumeSink implements LodLightVolume.GpuSink {
        private int runStart = -1;
        private int runCount;

        @Override
        public void reset() {
            flush();
            resetGpuHash();
        }

        @Override
        public void tile(int slot, int[] voxels) {
            if (runCount > 0 && (slot != runStart + runCount || runCount == RUN_TILES))
                flush();
            if (runCount == 0)
                runStart = slot;
            volRun.position(runCount * LodLightVolume.TILE_VOXELS);
            volRun.put(voxels, 0, LodLightVolume.TILE_VOXELS);
            runCount++;
            LodDiag.volUploadTiles.incrementAndGet();
        }

        @Override
        public void entry(int index, int a, int b, int c, int d) {
            flush(); // a tile's data is always in before the entry that points to it
            volEntry.clear();
            volEntry.put(a).put(b).put(c).put(d).flip();
            GL45C.glNamedBufferSubData(buffer, (long) (LodLightVolume.HASH_OFFSET_INTS + index * 4) * Integer.BYTES, volEntry);
            LodDiag.volUploadEntries.incrementAndGet();
        }

        void flush() {
            if (runCount == 0)
                return;
            volRun.position(0);
            volRun.limit(runCount * LodLightVolume.TILE_VOXELS);
            GL45C.glNamedBufferSubData(buffer, (long) (LodLightVolume.POOL_OFFSET_INTS + (long) runStart * LodLightVolume.TILE_VOXELS) * Integer.BYTES, volRun);
            volRun.clear();
            runCount = 0;
            runStart = -1;
        }
    }

    private VoxyLightBridge() {
    }
}
