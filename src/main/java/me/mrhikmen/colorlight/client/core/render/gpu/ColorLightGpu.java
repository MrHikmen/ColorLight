package me.mrhikmen.colorlight.client.core.render.gpu;

import me.mrhikmen.colorlight.client.ColorLightClient;
import me.mrhikmen.colorlight.client.compat.nvidium.NvidiumBridge;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngine;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngineHolder;

import me.mrhikmen.colorlight.client.compat.lod.voxy.VoxyLightBridge;
import me.mrhikmen.colorlight.client.compat.lod.dh.DhLightBridge;

import me.mrhikmen.colorlight.client.compat.sable.SableLightVolume;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;

/**
 * The GPU light pipeline: instead of baking colours into chunk meshes, Sodium's terrain shader is swapped for
 * ColorLight's copy ({@code assets/colorlight/shaders/blocks/colored_terrain.*}) which looks the light up per pixel in a
 * {@link GpuLightVolume}.
 * <p>
 * <b>How it plugs into Sodium</b> (three small mixins in {@code mixin.sodium}, nothing else of Sodium is touched):
 * <ol>
 *     <li>{@code ShaderChunkRenderer}: the terrain bind group gets the volume's two buffers, and the terrain pipelines
 *     are pointed at our shader files. Our shaders still {@code #include} Sodium's own globals / fog / vertex
 *     format files, so only the two {@code main()} functions are copies.</li>
 *     <li>{@code SodiumWorldRenderer.setupTerrain}: once per frame, before any pass, the camera goes into the volume.</li>
 *     <li>{@code DefaultChunkRenderer.render}: binds the volume's buffers for every terrain draw.</li>
 * </ol>
 * <b>Safety net.</b> The pipeline only turns on when all three mixins applied (they are skipped for Sodium versions
 * we haven't checked, see {@code ColorLightMixinPlugin}). If anything is missing at run time, {@link #fail} switches
 * back to the vertex-colour pipeline and re-meshes. With another renderer in charge (Iris shader packs) the hooks
 * never run and the vertex-colour pipeline is not affected at all.
 * <p>
 * Whether the pipeline is used is decided once per session ({@link #gpuMode}): Sodium compiles its pipelines once.
 */
public final class ColorLightGpu {

    private static final ResourceLocation SODIUM_TERRAIN_VERTEX = ResourceLocation.fromNamespaceAndPath("sodium", "blocks/block_layer_opaque.vsh");
    private static final ResourceLocation SODIUM_TERRAIN_FRAGMENT = ResourceLocation.fromNamespaceAndPath("sodium", "blocks/block_layer_opaque.fsh");
    private static final ResourceLocation COLORLIGHT_TERRAIN_VERTEX = ResourceLocation.fromNamespaceAndPath("colorlight", "blocks/colored_terrain.vsh");
    private static final ResourceLocation COLORLIGHT_TERRAIN_FRAGMENT = ResourceLocation.fromNamespaceAndPath("colorlight", "blocks/colored_terrain.fsh");

    private static final String DEFAULT_RENDERER = "net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer";
    private static final String SHADER_RENDERER = "net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer";
    private static final String WORLD_RENDERER = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer";

    private static final int MODE_UNKNOWN = 0;
    private static final int MODE_ON = 1;
    private static final int MODE_OFF = 2;

    private static volatile int mode = MODE_UNKNOWN;
    private static volatile boolean failed;

    /** Set by the mixins when their piece really ran; checked on the first draw. */
    public static volatile boolean vertexRedirected;
    public static volatile boolean fragmentRedirected;
    private static volatile boolean frameHooked;
    private static volatile boolean drawHookRan;
    private static int framesSeen;
    private static boolean watchdogDone;

    private static GpuLightVolume volume;

    /** Sable sub-levels get their own light windows (compat.sable); only touched when Sable is installed. */
    private static final boolean SABLE = FabricLoader.getInstance().isModLoaded("sable");

    /** Section of the camera in the last rendered frame; the volume is centred on it. */
    private static int camSx, camSy, camSz;
    private static boolean camKnown;

    /** Camera of the current frame as the frame hook saw it; the draw hook reuses it. */
    private static volatile boolean frameCamSet;
    private static volatile int frameCamX, frameCamY, frameCamZ;
    private static volatile float frameFracX, frameFracY, frameFracZ;

    // ------------------------------------------------------------------------------------------------------
    // Decision
    // ------------------------------------------------------------------------------------------------------

    /**
     * True if ColorLight's terrain pipeline is installed in Sodium for this session (decided on first use, then fixed:
     * Sodium compiles its pipelines once). Whether it is actually used for lighting is {@link #isActive()}, which
     * follows the config switch and shader packs live.
     */
    public static boolean gpuMode() {
        int m = mode;
        if (m == MODE_UNKNOWN) {
            synchronized (ColorLightGpu.class) {
                m = mode;
                if (m == MODE_UNKNOWN) {
                    boolean on = decide();
                    m = on ? MODE_ON : MODE_OFF;
                    mode = m;
                    ColorLightClient.LOGGER.info("[ColorLight] GPU light pipeline: {}", on ? "installed" : "not available (vertex colours)");
                }
            }
        }
        return m == MODE_ON;
    }

    /** True while coloured light is rendered by the shader (so meshes must NOT carry baked colours). */
    public static boolean isActive() {
        return !failed && !shaderPackActive && gpuMode() && ColorLightClient.config.GPU_PIPELINE;
    }

    /** True while an Iris shader pack is on. Asked fresh, for the settings screen. */
    public static boolean shaderPackInUse() {
        return queryShaderPack();
    }

    // ------------------------------------------------------------------------------------------------------
    // Switching between the shader pipeline and vertex colours while playing. Whatever flips isActive() (the
    // config switch, a shader pack, a failure) changes how meshes have to be built, so every lit section is built
    // again, and a volume that sat idle is refilled.
    // ------------------------------------------------------------------------------------------------------

    private static boolean lastActive;
    private static volatile boolean volumeNeedsReset;
    private static boolean lastActiveKnown;

    private static void syncMode() {
        boolean now = isActive();
        if (!lastActiveKnown) {
            lastActive = now;
            lastActiveKnown = true;
            return;
        }
        if (now == lastActive)
            return;
        lastActive = now;

        ColorLightClient.LOGGER.info("[ColorLight] Lighting is now {}", now ? "rendered by the GPU pipeline" : "baked into vertex colours");
        ColorLightEngine engine = ColorLightEngineHolder.get();
        if (engine != null)
            engine.markEverythingDirty();
        meshRebuild = true;
        if (now)
            volumeNeedsReset = true; // it was not kept up to date while idle; done in the tick (no GPU writes here)
    }

    // ------------------------------------------------------------------------------------------------------
    // Iris shader packs replace Sodium's terrain renderer: our hooks never run then, so vertex colours take over
    // (they are read by pack shaders as well). Polled, because packs can be switched while playing.
    // ------------------------------------------------------------------------------------------------------

    private static volatile boolean shaderPackActive;
    private static volatile boolean meshRebuild;
    private static int shaderPackTimer;
    private static boolean irisResolved;
    private static Object irisApi;
    private static java.lang.reflect.Method irisInUse;

    private static boolean queryShaderPack() {
        if (!FabricLoader.getInstance().isModLoaded("iris"))
            return false;
        try {
            if (!irisResolved) {
                irisResolved = true;
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                irisApi = api.getMethod("getInstance").invoke(null);
                irisInUse = api.getMethod("isShaderPackInUse");
            }
            return irisInUse != null && (boolean) irisInUse.invoke(irisApi);
        } catch (Throwable t) {
            irisInUse = null;
            return false;
        }
    }

    private static void pollShaderPack() {
        if (++shaderPackTimer < 4)
            return;
        shaderPackTimer = 0;

        boolean now = queryShaderPack();
        if (now == shaderPackActive)
            return;

        ColorLightClient.LOGGER.info("[ColorLight] Shader pack {}", now ? "enabled" : "disabled");
        shaderPackActive = now;
        syncMode();
    }

    /** True while the dirty flusher must still turn drained sections into mesh rebuilds although the GPU pipeline is on. */
    public static boolean meshRebuildPending() {
        return meshRebuild;
    }

    public static void meshRebuildDone() {
        meshRebuild = false;
    }

    private static boolean decide() {
        if (Boolean.getBoolean("colorlight.noGpu"))
            return false;
        return sodiumHooksApplied();
    }

    private static boolean sodiumHooksApplied() {
        try {
            ClassLoader loader = ColorLightGpu.class.getClassLoader();
            // initialize = false: loads (and mixes in) the class without running its static initialiser,
            // which is what calls back into gpuMode()
            Class<?> shader = Class.forName(SHADER_RENDERER, false, loader);
            Class<?> draw = Class.forName(DEFAULT_RENDERER, false, loader);
            Class<?> frame = Class.forName(WORLD_RENDERER, false, loader);
            return SodiumHookMarkers.Shader.class.isAssignableFrom(shader)
                    && SodiumHookMarkers.Draw.class.isAssignableFrom(draw)
                    && SodiumHookMarkers.Frame.class.isAssignableFrom(frame);
        } catch (Throwable t) {
            return false; // Sodium not installed
        }
    }

    /** Turns the pipeline off for the rest of the session and brings the vertex colours back. */
    public static void fail(String reason) {
        if (failed)
            return;
        failed = true;
        lastActive = false;
        lastActiveKnown = true;
        ColorLightClient.LOGGER.error("[ColorLight] GPU light pipeline disabled for this session: {}. Falling back to vertex colours.", reason);

        ColorLightEngine engine = ColorLightEngineHolder.get();
        if (engine != null)
            engine.markEverythingDirty(); // the meshes carry no colour yet
        meshRebuild = true;
    }

    // ------------------------------------------------------------------------------------------------------
    // Hooks called from the Sodium mixins (render thread)
    // ------------------------------------------------------------------------------------------------------

    /**
     * True while Sodium's terrain pipelines are ours (and so need our buffers bound on every draw). Deliberately NOT
     * the same as {@link #isActive()}: after an Iris shader pack is switched off, or after {@link #fail}, Sodium still
     * draws with our pipeline, which then renders plain vertex colours ("disabled" in the header) but must still find
     * its buffers bound or the draw crashes.
     */
    private static boolean pipelineInUse() {
        return gpuMode();
    }

    /** Once per frame, before any terrain pass. */
    public static void onFrameStart(int camBlockX, int camBlockY, int camBlockZ, float fracX, float fracY, float fracZ) {
        frameCamX = camBlockX; frameCamY = camBlockY; frameCamZ = camBlockZ;
        frameFracX = fracX; frameFracY = fracY; frameFracZ = fracZ;
        frameCamSet = true;

        // Voxy's and Distant Horizons' LODs each have their own pipeline and their own switch; they only need to
        // know where the camera is
        VoxyLightBridge.onFrame(camBlockX, camBlockZ);
        DhLightBridge.onFrame(camBlockX, camBlockY, camBlockZ, fracX, fracY, fracZ);

        prepareFrame(camBlockX, camBlockY, camBlockZ, fracX, fracY, fracZ);
    }

    /**
     * Makes the volume and its header (camera, settings) current. Called from the frame hook and again from every draw:
     * the second call finds nothing to do, but it means a frame whose hook didn't run (it happens on the first frame
     * in a world) is still drawn with a valid header instead of an unbound or stale one.
     */
    private static void prepareFrame(int camBlockX, int camBlockY, int camBlockZ, float fracX, float fracY, float fracZ) {
        if (!pipelineInUse())
            return;

        pollShaderPack(); // Sodium is drawing, so no shader pack is in charge: catch "pack just switched off" at once
        syncMode();       // ... and a config switch flipped since the last tick

        GpuLightVolume v = volume();
        v.ensureBuffers();

        // While a pack switch is still unnoticed the meshes have vertex colours; "disabled" makes the shader use them.
        boolean enabled = isActive() && ColorLightClient.config.ENABLE && ColorLightEngineHolder.get() != null;
        v.writeHeader(camBlockX, camBlockY, camBlockZ, fracX, fracY, fracZ, enabled,
                ColorLightClient.config.TINT_GAMMA, ColorLightClient.config.SMOOTH_LIGHTING);

        camSx = camBlockX >> 4;
        camSy = camBlockY >> 4;
        camSz = camBlockZ >> 4;
        camKnown = true;
        frameHooked = true;

        // Terrain samplers are declared with layout(binding = 5..7): binding the units here makes the terrain shader
        // work even when the per-draw hook below does not run.
        int[] names = v.glBufferNames();
        if (names != null)
            SodiumGlBinder.bindUnits(names);

        watchdog();
    }

    /** Re-binds the main volume to texture units 5..7 (the Sable compat borrows them for sub-level draws). */
    public static void rebindMainUnits() {
        GpuLightVolume v = volume;
        int[] names = v == null ? null : v.glBufferNames();
        if (names != null)
            SodiumGlBinder.bindUnits(names);
    }

    /**
     * A few seconds into the first world, reports what really happened to the three Sodium hooks. A terrain shader
     * that was never installed would leave terrain with neither baked nor shader light, so that case falls back to
     * vertex colours.
     */
    private static void watchdog() {
        if (watchdogDone || ++framesSeen < 300)
            return;
        watchdogDone = true;

        ColorLightClient.LOGGER.info("[ColorLight] GPU pipeline status: vertexShaderRedirected={}, fragmentShaderRedirected={}, drawHookRan={}, active={}, shadersSeen={}",
                vertexRedirected, fragmentRedirected, drawHookRan, isActive(), SEEN_SHADERS);

        if (!failed && !(vertexRedirected && fragmentRedirected))
            fail("Sodium never loaded ColorLight's terrain shader (vertex=" + vertexRedirected + ", fragment=" + fragmentRedirected + "); another mod may replace Sodium's shader loading");
        else if (!drawHookRan)
            ColorLightClient.LOGGER.warn("[ColorLight] The terrain draw hook did not run; relying on the per-frame texture unit binding");
    }

    /**
     * Called from {@code ShaderChunkRendererMixin} for every shader Sodium loads: swaps Sodium's terrain shaders for
     * ColorLight's copies and remembers that it did.
     */
    private static final java.util.Set<String> SEEN_SHADERS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static ResourceLocation redirectSodiumShader(ResourceLocation original) {
        if (original != null && SEEN_SHADERS.size() < 64 && SEEN_SHADERS.add(original.toString()))
            ColorLightClient.LOGGER.info("[ColorLight] Sodium loads shader {}", original);
        if (SODIUM_TERRAIN_VERTEX.equals(original)) {
            vertexRedirected = true;
            return COLORLIGHT_TERRAIN_VERTEX;
        }
        if (SODIUM_TERRAIN_FRAGMENT.equals(original)) {
            fragmentRedirected = true;
            return COLORLIGHT_TERRAIN_FRAGMENT;
        }
        return original;
    }

    /**
     * Before every terrain draw, with Sodium's program already current: attach the volume's buffers to it.
     * (Minecraft 1.21.1 / Sodium 0.8.x draws with plain OpenGL, so the volume lives in raw GL buffers.)
     */
    /** Draw hook that does not see Sodium's camera arguments: uses the camera the frame hook stored. */
    public static void onDraw() {
        if (frameCamSet)
            onDraw(frameCamX, frameCamY, frameCamZ, frameFracX, frameFracY, frameFracZ);
    }

    public static void onDraw(int camBlockX, int camBlockY, int camBlockZ, float fracX, float fracY, float fracZ) {
        if (!drawHookRan) {
            drawHookRan = true;
            ColorLightClient.LOGGER.info("[ColorLight] Terrain draw hook is active");
        }
        // same as in the frame hook, for when that one did not run: cheap, it only uploads when something changed
        VoxyLightBridge.onFrame(camBlockX, camBlockZ);
        DhLightBridge.onFrame(camBlockX, camBlockY, camBlockZ, fracX, fracY, fracZ);

        if (!pipelineInUse())
            return;

        prepareFrame(camBlockX, camBlockY, camBlockZ, fracX, fracY, fracZ);

        if (!failed && !(vertexRedirected && fragmentRedirected)) {
            // vertex colours take over, but the program may still be ours: keep binding below
            fail("a Sodium hook did not apply (vertex=" + vertexRedirected + ", fragment=" + fragmentRedirected + ")");
        }

        GpuLightVolume v = volume();
        v.ensureBuffers();
        int[] names = v.glBufferNames();
        if (names == null) {
            fail("light volume has no GPU buffers");
            return;
        }

        SodiumGlBinder.bindCurrentProgram(names);
    }

    // ------------------------------------------------------------------------------------------------------
    // Client tick side
    // ------------------------------------------------------------------------------------------------------

    private static GpuLightVolume volume() {
        if (volume == null)
            volume = new GpuLightVolume(ColorLightClient.config.GPU_LIGHT_SECTIONS, true);
        return volume;
    }

    // ------------------------------------------------------------------------------------------------------
    // Nvidium: it replaces Sodium's terrain draw (SodiumWorldRenderer.renderLayer is cancelled), so the draw hook above
    // never runs. Nvidium draws with its own GL program and reads the light volume itself: ColorLight keeps the volume
    // in plain GL buffers then (GpuLightVolume raw mode); NvidiumBridge patches Nvidium's shaders through mixins
    // (mixin.nvidium) and attaches the buffers to them as texel buffers.
    // ------------------------------------------------------------------------------------------------------

    private static final int NVIDIUM_UNKNOWN = 0;
    private static final int NVIDIUM_YES = 1;
    private static final int NVIDIUM_NO = 2;
    private static volatile int nvidium = NVIDIUM_UNKNOWN;

    /**
     * True if Nvidium is installed AND actually in charge of the terrain (it switches itself off on GPUs without mesh
     * shaders). Nvidium decides that when the GL device is created, long before the first frame, so asking late enough
     * (first frame / first volume) is safe; the answer is then fixed for the session.
     */
    public static boolean nvidiumRenders() {
        int n = nvidium;
        if (n == NVIDIUM_UNKNOWN) {
            boolean on = false;
            if (FabricLoader.getInstance().isModLoaded("nvidium")) {
                try {
                    Class<?> cls = Class.forName("me.cortex.nvidium.Nvidium", true, ColorLightGpu.class.getClassLoader());
                    on = cls.getField("IS_ENABLED").getBoolean(null);
                } catch (Throwable t) {
                    ColorLightClient.LOGGER.warn("[ColorLight] Nvidium is installed but its state could not be read: {}", t.toString());
                }
                ColorLightClient.LOGGER.info("[ColorLight] Nvidium {}", on ? "is rendering the terrain: light volume goes to its shaders" : "is installed but inactive");
            }
            n = on ? NVIDIUM_YES : NVIDIUM_NO;
            nvidium = n;
        }
        return n == NVIDIUM_YES;
    }

    /**
     * True if Nvidium's terrain shaders should be patched with the ColorLight code (see {@link NvidiumBridge}). Decided once per session, like {@link #gpuMode()}. Whether the light is used at a given moment
     * is still the header's enabled flag, so the config switch and failures need no shader rebuild.
     */
    public static boolean externalRendererSupported() {
        return gpuMode() && nvidiumRenders();
    }

    /**
     * For {@link NvidiumBridge}: OpenGL names of the light volume's
     * {header, data, pool} buffers, or null while there are none (no frame has been prepared yet, or the pipeline
     * failed). Null makes the shader fall back to the vertex colours / vanilla light.
     */
    public static int[] externalBuffers() {
        if (failed || !gpuMode())
            return null;
        GpuLightVolume v = volume;
        return v == null ? null : v.glBufferNames();
    }

    /** The engine reported these sections as changed. */
    public static void onSectionsChanged(long[] sectionKeys) {
        if (isActive()) {
            volume().markDirty(sectionKeys);
            if (SABLE)
                SableLightVolume.onSectionsChanged(sectionKeys);
        }
    }

    /** OpenGL name of the main volume's header buffer (enabled flag, tint settings), or 0. Used by the Sable compat. */
    public static int mainHeaderBuffer() {
        if (failed || volume == null)
            return 0;
        int[] names = volume.glBufferNames();
        return names == null ? 0 : names[0];
    }

    public static void tick(Minecraft client) {
        if (failed || !gpuMode())
            return;

        pollShaderPack();
        syncMode();
        if (!isActive())
            return;

        // "GPU light memory" setting changed: start over with a volume of the new size (it refills within a second)
        if (volume != null && volume.slotCount() != GpuLightVolume.clampSlots(ColorLightClient.config.GPU_LIGHT_SECTIONS)) {
            volume.close();
            volume = null;
        }

        ClientLevel level = client.level;
        ColorLightEngine engine = ColorLightEngineHolder.get();
        if (level == null || client.player == null || engine == null) {
            if (volume != null)
                volume.reset();
            if (SABLE)
                SableLightVolume.reset();
            camKnown = false;
            return;
        }

        int sx = camSx, sy = camSy, sz = camSz;
        if (!camKnown) {
            var pos = client.player.blockPosition();
            sx = pos.getX() >> 4;
            sy = pos.getY() >> 4;
            sz = pos.getZ() >> 4;
        }

        if (volumeNeedsReset) {
            volumeNeedsReset = false;
            volume().reset();
            if (SABLE)
                SableLightVolume.reset();
        }

        volume().tick(engine, level, sx, sy, sz);
        if (SABLE)
            SableLightVolume.tick(engine, level);
    }

    public static void shutdown() {
        if (SABLE)
            SableLightVolume.shutdown();
        if (volume != null) {
            volume.close();
            volume = null;
        }
    }

    private ColorLightGpu() {
    }
}
