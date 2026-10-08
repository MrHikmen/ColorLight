package me.mrhikmen.colorlight.client.core.render.gpu;

import com.mojang.renderpearl.api.commands.RenderPass;

import me.mrhikmen.colorlight.client.ColorLightClient;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngine;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngineHolder;

import me.mrhikmen.colorlight.client.compat.lod.VoxyLightBridge;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;

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

    public static final Identifier SODIUM_TERRAIN_SHADER = Identifier.fromNamespaceAndPath("sodium", "blocks/block_layer_opaque");
    public static final Identifier COLORLIGHT_TERRAIN_SHADER = Identifier.fromNamespaceAndPath("colorlight", "blocks/colored_terrain");

    private static final String DEFAULT_RENDERER = "net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer";
    private static final String SHADER_RENDERER = "net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer";
    private static final String WORLD_RENDERER = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer";

    private static final int MODE_UNKNOWN = 0;
    private static final int MODE_ON = 1;
    private static final int MODE_OFF = 2;

    private static volatile int mode = MODE_UNKNOWN;
    private static volatile boolean failed;

    /** Set by the mixins when their piece really ran; checked on the first draw. */
    public static volatile boolean layoutPatched;
    public static volatile boolean vertexRedirected;
    public static volatile boolean fragmentRedirected;
    private static volatile boolean frameHooked;

    private static GpuLightVolume volume;

    /** Section of the camera in the last rendered frame; the volume is centred on it. */
    private static int camSx, camSy, camSz;
    private static boolean camKnown;

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
        // Voxy's LODs have their own pipeline and their own switch; they only need to know where the camera is
        VoxyLightBridge.onFrame(camBlockX, camBlockZ);

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
    }

    /** Before every terrain draw: make the volume's buffers visible to the shader. */
    public static void onDraw(RenderPass pass, int camBlockX, int camBlockY, int camBlockZ, float fracX, float fracY, float fracZ) {
        // same as in the frame hook, for when that one did not run: cheap, it only uploads when something changed
        VoxyLightBridge.onFrame(camBlockX, camBlockZ);

        if (!pipelineInUse())
            return;

        prepareFrame(camBlockX, camBlockY, camBlockZ, fracX, fracY, fracZ);

        if (!failed && !(layoutPatched && vertexRedirected && fragmentRedirected)) {
            // vertex colours take over, but the pipeline is still ours: keep binding below
            fail("a Sodium hook did not apply (layout=" + layoutPatched + ", vertex=" + vertexRedirected
                    + ", fragment=" + fragmentRedirected + ")");
        }

        GpuLightVolume v = volume();
        v.ensureBuffers();
        if (!v.hasBuffers()) {
            fail("light volume has no GPU buffers");
            return;
        }

        pass.setUniform("u_CLHeader", v.headerBuffer());
        pass.setUniform("u_CLData", v.dataBuffer());
        pass.setUniform("u_CLPool", v.poolBuffer());
    }

    // ------------------------------------------------------------------------------------------------------
    // Client tick side
    // ------------------------------------------------------------------------------------------------------

    private static GpuLightVolume volume() {
        if (volume == null)
            volume = new GpuLightVolume(ColorLightClient.config.GPU_LIGHT_SECTIONS);
        return volume;
    }

    /** The engine reported these sections as changed. */
    public static void onSectionsChanged(long[] sectionKeys) {
        if (isActive())
            volume().markDirty(sectionKeys);
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
        }

        volume().tick(engine, level, sx, sy, sz);
    }

    public static void shutdown() {
        if (volume != null) {
            volume.close();
            volume = null;
        }
    }

    private ColorLightGpu() {
    }
}
