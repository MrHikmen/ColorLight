package me.mrhikmen.colorlight.client.compat.lod;

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
 *     <li>{@link #onFrame}: once per frame a shader storage buffer with the {@link LodLightMap} grids is updated and
 *     bound. The buffer is created and bound through plain GL calls and does not touch any state that Minecraft's
 *     renderer tracks, except for the one indexed SSBO binding that we pick for ourselves.</li>
 * </ol>
 * <p>
 * The buffer holds {@link #SECTIONS} grids: section 0 is {@link LodLightMap}'s standalone 4-block "full" tier,
 * sections 1..3 are its coarsening levels 0..2 (8/16/32-block cells). Which section a quad samples is chosen in
 * {@code colorlight_lod.glsl} purely from the quad's distance to the camera, not from Voxy's own LOD level.
 */
public final class VoxyLightBridge {

    private static final int GRID = 512;                          // cells per side of one grid, toroidal
    private static final int HEADER_INTS = 16;
    private static final int LEVEL_INTS = GRID * GRID;
    /** 0: full tier (4-block cells); 1..3: coarse levels 0..2 (8/16/32-block cells). Keep in sync with the shader. */
    private static final int SECTIONS = 4;
    private static final int FULL_CELL_SHIFT = LodLightMap.FULL_SHIFT;
    private static final int TOTAL_INTS = HEADER_INTS + SECTIONS * LEVEL_INTS;

    private static volatile boolean patched;
    private static int binding = -1;

    private static int buffer;
    private static final int[] header = new int[HEADER_INTS];
    private static final int[] lastHeader = new int[HEADER_INTS];
    private static boolean headerWritten;
    private static IntBuffer headerBytes;
    private static IntBuffer gridBytes;
    private static final int[] gridScratch = new int[LEVEL_INTS];

    private static LodLightMap lastMap;
    private static final long[] uploadedVersion = new long[SECTIONS];
    private static final int[] uploadedCamX = new int[SECTIONS];
    private static final int[] uploadedCamZ = new int[SECTIONS];

    // ------------------------------------------------------------------------------------------------------
    // Shader text
    // ------------------------------------------------------------------------------------------------------

    private static final String ANCHOR_FUNCTION = "uvec3 makeRemainingAttributes(";
    private static final String ANCHOR_LIGHTING = "vec4 tinting = getLighting(lighting);";
    private static final String ANCHOR_SETUP = "    if (generateAttributes) {";

    /**
     * @param shaderSource what Voxy's {@code ShaderLoader.parse} produced
     * @return the changed source, or null to leave it as it is
     */
    public static String patch(String shaderSource) {
        if (shaderSource == null || !shaderSource.contains(ANCHOR_FUNCTION))
            return null; // not the LOD vertex shader

        try {
            if (!shaderSource.contains(ANCHOR_LIGHTING) || !shaderSource.contains(ANCHOR_SETUP)
                    || !shaderSource.contains("LIGHTING_SAMPLER_BINDING") || !shaderSource.contains("baseSectionPos")) {
                ColorLightClient.LOGGER.warn("[ColorLight] Voxy's LOD shader looks different from the one this was written for; far light colours are off");
                return null;
            }

            int chosen = chooseBinding();
            if (chosen < 0) {
                ColorLightClient.LOGGER.warn("[ColorLight] Not enough shader storage bindings for far light colours");
                return null;
            }

            String block = readResource("/assets/colorlight/shaders/voxy/colorlight_lod.glsl").replace("__CL_BINDING__", Integer.toString(chosen));

            String out = shaderSource;
            out = replaceFirst(out, ANCHOR_FUNCTION, block + "\n" + ANCHOR_FUNCTION);
            out = replaceFirst(out, ANCHOR_LIGHTING, "vec4 tinting = cl_getLighting(lighting);");
            out = replaceFirst(out, ANCHOR_SETUP, "    cl_setQuad(extractPos(rawQuad), lodScale, baseSection, lodLevel);\n" + ANCHOR_SETUP);

            binding = chosen;
            patched = true;
            ensureBuffer(); // so the very first draw already finds a bound (disabled) buffer
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, buffer);
            ColorLightClient.LOGGER.info("[ColorLight] Far light colours added to Voxy's LOD shader (SSBO binding {})", chosen);
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

        try {
            ensureBuffer();
            // bind every frame: an indexed binding is global GL state, and this is cheap
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, buffer);

            LodLightMap map = LodLight.map();
            boolean enabled = map != null && ColorLightClient.config.VOXY_LIGHT && ColorLightClient.config.ENABLE
                    && ColorLightEngineHolder.get() != null;

            writeHeader(camBlockX, camBlockZ, enabled);

            if (enabled)
                uploadOneLevel(map, camBlockX, camBlockZ);
        } catch (Throwable t) {
            ColorLightClient.LOGGER.error("[ColorLight] Far light colours for Voxy turned off: {}", t.toString());
            patched = false; // the shader then just sees "disabled" (header stays as last written)
            disableInShader();
        }
    }

    private static void ensureBuffer() {
        if (buffer != 0)
            return;
        buffer = GL45C.glCreateBuffers();
        GL45C.glNamedBufferStorage(buffer, (long) TOTAL_INTS * Integer.BYTES, GL44C.GL_DYNAMIC_STORAGE_BIT);
        headerBytes = MemoryUtil.memAllocInt(HEADER_INTS);
        gridBytes = MemoryUtil.memAllocInt(LEVEL_INTS);
        headerWritten = false;
        headerBytes.clear();
        for (int i = 0; i < HEADER_INTS; i++)
            headerBytes.put(0);
        headerBytes.flip();
        GL45C.glNamedBufferSubData(buffer, 0L, headerBytes); // header[2] = 0: disabled until the first real frame
        Arrays.fill(uploadedVersion, -1L);
        lastMap = null;
    }

    private static void writeHeader(int camX, int camZ, boolean enabled) {
        Arrays.fill(header, 0);
        header[0] = camX;
        header[1] = camZ;
        header[2] = enabled ? 1 : 0;
        header[3] = Float.floatToRawIntBits(Math.max(0f, ColorLightClient.config.TINT_GAMMA));
        header[4] = Float.floatToRawIntBits(1.0f);

        if (headerWritten && Arrays.equals(header, lastHeader))
            return;

        headerBytes.clear();
        headerBytes.put(header).flip();
        GL45C.glNamedBufferSubData(buffer, 0L, headerBytes);
        System.arraycopy(header, 0, lastHeader, 0, HEADER_INTS);
        headerWritten = true;
    }

    /** Refreshes at most one section per frame, so a big refill never lands in a single frame. */
    private static void uploadOneLevel(LodLightMap map, int camX, int camZ) {
        if (map != lastMap) {
            lastMap = map;
            Arrays.fill(uploadedVersion, -1L);
        }

        for (int s = 0; s < SECTIONS; s++) {
            boolean full = s == 0;
            int shift = full ? FULL_CELL_SHIFT : (LodLightMap.BASE_SHIFT + (s - 1));
            long version = full ? map.fullVersion() : map.version(s - 1);
            int cellX = camX >> shift;
            int cellZ = camZ >> shift;
            if (version == uploadedVersion[s] && cellX == uploadedCamX[s] && cellZ == uploadedCamZ[s])
                continue;

            if (full)
                map.fillFullGrid(cellX, cellZ, GRID, gridScratch);
            else
                map.fillGrid(s - 1, cellX, cellZ, GRID, gridScratch);
            gridBytes.clear();
            gridBytes.put(gridScratch, 0, LEVEL_INTS).flip();
            GL45C.glNamedBufferSubData(buffer, (long) (HEADER_INTS + s * LEVEL_INTS) * Integer.BYTES, gridBytes);

            uploadedVersion[s] = version;
            uploadedCamX[s] = cellX;
            uploadedCamZ[s] = cellZ;
            return;
        }
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
