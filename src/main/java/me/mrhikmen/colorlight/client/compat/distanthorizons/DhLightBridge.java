package me.mrhikmen.colorlight.client.compat.distanthorizons;

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
 * Coloured light for Distant Horizons' LOD terrain.
 * <p>
 * DH draws with its own OpenGL pipeline and its own shaders, so none of the Sodium hooks apply. Two small pieces
 * connect to it:
 * <ol>
 *     <li>{@link #patch}: DH turns its shader files into source text in {@code GlShader.loadFile(path, ...)}; a
 *     mixin hands every result of that (vertex and fragment shaders alike, for every DH shader program) to us and
 *     we change two of them only: {@code assets/distanthorizons/shaders/terrain/gl/vert.vert} gets two small
 *     varyings added (the sky/block light levels DH already computes, so the fragment shader can tell how much of
 *     the resolved colour is block light) and {@code .../terrain/gl/frag.frag} gets {@code colorlight_dh.glsl}
 *     added plus one line changed in {@code main()}. If any anchor is not found (DH changed), nothing is touched
 *     for that file.</li>
 *     <li>{@link #onFrame}: once per frame a shader storage buffer with the {@link DhLightCache} grid is updated
 *     and bound, exactly like {@code VoxyLightBridge} does for Voxy - same binary layout, independent buffer and
 *     binding.</li>
 * </ol>
 */
public final class DhLightBridge {

    private static final int HEADER_INTS = 16;
    private static final int GRID_CELLS = 512; // 16x16-block cells per side
    private static final int MAX_SOURCES = 131072;
    private static final int CELL_INTS = GRID_CELLS * GRID_CELLS * 2;
    private static final int SOURCE_OFFSET_INTS = HEADER_INTS + CELL_INTS;
    private static final int COARSE_OFFSET_INTS = SOURCE_OFFSET_INTS + MAX_SOURCES * 4;
    private static final int TOTAL_INTS = COARSE_OFFSET_INTS + 256 * 256;
    /** Rebuild the table once the camera has moved this many columns (the shader keeps the same margin). */
    private static final int DRIFT_COLS = 8;

    private static final String VERT_PATH = "assets/distanthorizons/shaders/terrain/gl/vert.vert";
    private static final String FRAG_PATH = "assets/distanthorizons/shaders/terrain/gl/frag.frag";

    private static volatile boolean vertPatched;
    private static volatile boolean fragPatched;
    private static int binding = -1;

    private static int buffer;
    private static final int[] header = new int[HEADER_INTS];
    private static final int[] lastHeader = new int[HEADER_INTS];
    private static boolean headerWritten;
    private static IntBuffer headerBytes;

    private static DhLightCache lastCache;
    private static long uploadedVersion = -1L;
    private static int uploadedColX, uploadedColZ;
    private static long lastUploadNanos;
    /** At most ~6 uploads per second (up to ~2.5 MB each): while sources are being read, the version keeps changing. */
    private static final long MIN_UPLOAD_GAP_NANOS = 160_000_000L;

    // ------------------------------------------------------------------------------------------------------
    // Shader text
    // ------------------------------------------------------------------------------------------------------

    private static final String ANCHOR_VERT_DECL = "out float vertexYPos;";
    private static final String ANCHOR_VERT_ASSIGN = "float blockLight = (mod(float(lights), 16.0)+0.5) / 16.0;";

    private static final String ANCHOR_FRAG_DECL_VARYING = "in vec3 vertexWorldPos;";
    private static final String ANCHOR_FRAG_DECL_BLOCK = "out vec4 fragColor;";
    private static final String ANCHOR_FRAG_MAIN = "fragColor = vertexColor;";

    private static final java.util.concurrent.atomic.AtomicBoolean loggedFirstCall = new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.Set<String> loggedPaths = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * @param path   the resource path {@code GlShader.loadFile} was asked for
     * @param source what it produced
     * @return the changed source, or null to leave it as it is
     */
    public static String patch(String path, String source) {
        // TEMPORARY DIAGNOSTIC: confirms the mixin is actually firing at all, and shows every path it sees once,
        // so a path/package mismatch shows up in the log instead of silence. Safe to remove once DH light works.
        if (loggedFirstCall.compareAndSet(false, true))
            ColorLightClient.LOGGER.info("[ColorLight] GlShaderMixin is active; first shader path seen: {}", path);
        if (path != null && loggedPaths.add(path))
            ColorLightClient.LOGGER.info("[ColorLight] Distant Horizons loaded shader: {}", path);

        if (VERT_PATH.equals(path))
            return patchVert(source);
        if (FRAG_PATH.equals(path))
            return patchFrag(source);
        return null;
    }

    private static String patchVert(String source) {
        if (source == null || !source.contains(ANCHOR_VERT_DECL) || !source.contains(ANCHOR_VERT_ASSIGN)) {
            if (source != null)
                ColorLightClient.LOGGER.warn("[ColorLight] Distant Horizons' terrain vertex shader looks different from the one this was written for; far light colours are off");
            vertPatched = false;
            return null;
        }
        try {
            int chosen = chooseBinding();
            if (chosen < 0) {
                vertPatched = false;
                return null;
            }
            String block = readResource("/assets/colorlight/shaders/distanthorizons/colorlight_dh_vert.glsl").replace("__CL_BINDING__", Integer.toString(chosen));

            String out = source;
            out = replaceFirst(out, ANCHOR_VERT_DECL, ANCHOR_VERT_DECL + "\nout float vClSkyLight;\nout float vClBlockLight;\nout float vClNear;\n" + block);
            out = replaceFirst(out, ANCHOR_VERT_ASSIGN, ANCHOR_VERT_ASSIGN + "\n    vClSkyLight = skyLight;\n    vClBlockLight = blockLight;\n    vClNear = cl_dh_near(vertexWorldPos);");
            out = addExtensions(out);
            vertPatched = true;
            return out;
        } catch (Throwable t) {
            ColorLightClient.LOGGER.error("[ColorLight] Could not add far light varyings to Distant Horizons' terrain vertex shader: {}", t.toString());
            vertPatched = false;
            return null;
        }
    }

    private static String patchFrag(String source) {
        if (source == null || !source.contains(ANCHOR_FRAG_DECL_VARYING) || !source.contains(ANCHOR_FRAG_DECL_BLOCK)
                || !source.contains(ANCHOR_FRAG_MAIN) || !source.contains("uniform sampler2D uBlockAtlas;")) {
            if (source != null)
                ColorLightClient.LOGGER.warn("[ColorLight] Distant Horizons' terrain fragment shader looks different from the one this was written for; far light colours are off");
            fragPatched = false;
            return null;
        }

        try {
            int chosen = chooseBinding();
            if (chosen < 0) {
                ColorLightClient.LOGGER.warn("[ColorLight] Not enough shader storage bindings for far light colours on Distant Horizons");
                fragPatched = false;
                return null;
            }

            String block = readResource("/assets/colorlight/shaders/distanthorizons/colorlight_dh.glsl").replace("__CL_BINDING__", Integer.toString(chosen));

            String out = source;
            out = replaceFirst(out, ANCHOR_FRAG_DECL_VARYING, ANCHOR_FRAG_DECL_VARYING + "\nin float vClSkyLight;\nin float vClBlockLight;\nin float vClNear;");
            out = replaceFirst(out, ANCHOR_FRAG_DECL_BLOCK, ANCHOR_FRAG_DECL_BLOCK + "\n" + block);
            out = replaceFirst(out, ANCHOR_FRAG_MAIN, ANCHOR_FRAG_MAIN + "\n    fragColor.rgb = cl_dh_tint(fragColor.rgb, vClSkyLight, vClBlockLight, vertexWorldPos);");

            out = addExtensions(out);
            binding = chosen;
            fragPatched = true;
            ensureBuffer(); // so the very first draw already finds a bound (disabled) buffer
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, buffer);
            ColorLightClient.LOGGER.info("[ColorLight] Far light colours added to Distant Horizons' terrain shader (SSBO binding {})", chosen);
            return out;
        } catch (Throwable t) {
            ColorLightClient.LOGGER.error("[ColorLight] Could not add far light colours to Distant Horizons: {}", t.toString());
            fragPatched = false;
            return null;
        }
    }

    /** The storage buffer and binding layout need these; put right after the #version line, where they belong. */
    private static String addExtensions(String source) {
        int v = source.indexOf("#version");
        int nl = source.indexOf('\n', v);
        if (v < 0 || nl < 0)
            throw new IllegalStateException("no #version line");
        return source.substring(0, nl + 1)
                + "#extension GL_ARB_shader_storage_buffer_object : enable\n#extension GL_ARB_shading_language_420pack : enable\n"
                + source.substring(nl + 1);
    }

    private static String replaceFirst(String text, String target, String replacement) {
        int i = text.indexOf(target);
        if (i < 0)
            throw new IllegalStateException("anchor not found: " + target);
        return text.substring(0, i) + replacement + text.substring(i + target.length());
    }

    private static String readResource(String path) throws Exception {
        try (InputStream in = DhLightBridge.class.getResourceAsStream(path)) {
            if (in == null)
                throw new IllegalStateException("missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** A binding index high enough to stay clear of anything else bound through this pipeline. */
    private static int chooseBinding() {
        int max = GL11C.glGetInteger(GL43C.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS);
        int chosen = Math.min(max - 1, 29); // one below Voxy's own pick (30), so both compats can coexist
        return chosen >= 12 ? chosen : -1;
    }

    /** Both halves of the patch have to have found their anchors before the shader is trusted to read the buffer. */
    private static boolean patched() {
        return vertPatched && fragPatched;
    }

    // ------------------------------------------------------------------------------------------------------
    // Per frame
    // ------------------------------------------------------------------------------------------------------

    /** Called every frame from the render thread (before the world is drawn) with the camera's block position. */
    public static void onFrame(int camBlockX, int camBlockY, int camBlockZ, float fracX, float fracY, float fracZ) {
        if (!patched())
            return;

        try {
            ensureBuffer();
            // bind every frame: an indexed binding is global GL state, and this is cheap
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, buffer);

            DhLightCache cache = DhLodLight.cache();
            boolean enabled = cache != null && ColorLightClient.config.DH_LIGHT && ColorLightClient.config.ENABLE
                    && ColorLightEngineHolder.get() != null;

            writeHeader(camBlockX, camBlockY, camBlockZ, fracX, fracY, fracZ, enabled);

            if (enabled)
                uploadOneLevel(cache, camBlockX, camBlockZ);
        } catch (Throwable t) {
            ColorLightClient.LOGGER.error("[ColorLight] Far light colours for Distant Horizons turned off: {}", t.toString());
            vertPatched = false;
            fragPatched = false;
            disableInShader();
        }
    }

    private static void ensureBuffer() {
        if (buffer != 0)
            return;
        buffer = GL45C.glCreateBuffers();
        GL45C.glNamedBufferStorage(buffer, (long) TOTAL_INTS * Integer.BYTES, GL44C.GL_DYNAMIC_STORAGE_BIT);
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

    private static void writeHeader(int camX, int camY, int camZ, float fx, float fy, float fz, boolean enabled) {
        Arrays.fill(header, 0);
        header[0] = camX;
        header[1] = camZ;
        header[2] = enabled ? 1 : 0;
        header[3] = Float.floatToRawIntBits(Math.max(0f, ColorLightClient.config.TINT_GAMMA));
        header[4] = Float.floatToRawIntBits(1.0f);
        header[6] = camY;
        header[7] = Float.floatToRawIntBits(fx);
        header[8] = Float.floatToRawIntBits(fy);
        header[9] = Float.floatToRawIntBits(fz);
        header[5] = Math.max(1, Math.min(16, ColorLightEngineHolder.getMaxRangeBlocks())); // light reach in blocks (the shader reads 3x3 columns)

        if (headerWritten && Arrays.equals(header, lastHeader))
            return;

        headerBytes.clear();
        headerBytes.put(header).flip();
        GL45C.glNamedBufferSubData(buffer, 0L, headerBytes);
        System.arraycopy(header, 0, lastHeader, 0, HEADER_INTS);
        headerWritten = true;
    }

    /**
     * The table is built on the cache's filler thread ({@link DhLightCache#requestFill}); here a finished one is
     * just copied to the GPU, at most one per {@link #MIN_UPLOAD_GAP_NANOS}.
     */
    private static void uploadOneLevel(DhLightCache cache, int camX, int camZ) {
        if (cache != lastCache) {
            lastCache = cache;
            uploadedVersion = -1L;
        }

        long now = System.nanoTime();
        long[] stamp = new long[4];
        if (now - lastUploadNanos >= MIN_UPLOAD_GAP_NANOS && cache.pollFilled(stamp)) {
            GL45C.glNamedBufferSubData(buffer, (long) HEADER_INTS * Integer.BYTES, cache.cellBuffer());
            GL45C.glNamedBufferSubData(buffer, (long) COARSE_OFFSET_INTS * Integer.BYTES, cache.coarseBuffer());
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
                GL45C.glNamedBufferSubData(buffer, 0L, headerBytes); // header[2] = 0: shader falls back to DH's own colour
            }
        } catch (Throwable ignored) {
        }
    }

    private DhLightBridge() {
    }
}
