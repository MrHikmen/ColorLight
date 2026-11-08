package me.mrhikmen.colorlight.client.core.render.gpu;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.UniformType;

import me.mrhikmen.colorlight.client.ColorLightClient;
import me.mrhikmen.colorlight.client.compat.nvidium.NvidiumBridge;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngine;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngineHolder;

import me.mrhikmen.colorlight.client.compat.lod.voxy.VoxyLightBridge;
import me.mrhikmen.colorlight.client.compat.lod.dh.DhLightBridge;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;

import org.lwjgl.system.MemoryUtil;

import java.util.Map;

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
 * we haven't checked, see {@code colorlighting.mixins.json}). If anything is missing at run time, {@link #fail} switches
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
        // PERF: our terrain shader copy (extra varyings, header reads in every vertex/pixel) costs frames even when the
        // light is switched off. If the GPU pipeline is off in the config at launch, leave Sodium's own shader alone
        // (vertex colours are used). Turning it on later needs a game restart.
        if (!ColorLightClient.config.GPU_PIPELINE && !Boolean.getBoolean("colorlight.forceGpu"))
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
                ColorLightClient.config.TINT_GAMMA, ColorLightClient.config.SMOOTH_LIGHTING,
                ColorLightEngineHolder.get() != null ? ColorLightEngineHolder.get().palette() : null);

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
        DhLightBridge.onFrame(camBlockX, camBlockY, camBlockZ, fracX, fracY, fracZ);

        if (!pipelineInUse())
            return;

        prepareFrame(camBlockX, camBlockY, camBlockZ, fracX, fracY, fracZ);

        if (volume().isRawGl())
            return; // Nvidium owns the draw; this hook only runs if Sodium draws after all, and has no GpuBuffers to bind

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
    // Entities, items and particles
    // ------------------------------------------------------------------------------------------------------
    // Vanilla draws them with core/entity, core/item and core/particle. ColorLight's copies (shaders/core/cl_*) light each
    // pixel from the same volume the terrain uses. Three pieces, as for the terrain:
    //   1. RenderPipelineBuilderMixin: those pipelines get ENTITY_LIGHT_GROUP (the volume's three buffers), always;
    //   2. ShaderConfigsMixin: the shader files are swapped when a pipeline is compiled, only while the GPU pipeline is on;
    //   3. FrontendRenderPassMixin + LevelRendererMixin: after every pipeline switch the buffers are bound. A uniform
    //      a pipeline reads must be set, so outside the world pass (GUI, hand, inventory) or with the light off the
    //      pipeline gets a disabled header (all zero: the shader returns the vanilla light).
    // ------------------------------------------------------------------------------------------------------

    /**
     * The bind group added to every entity, item, particle and moving block pipeline. It is added whatever the mode is: the mode
     * is decided at launch and the pipelines are built before the config is read, so the groups can't depend on it.
     */
    public static final BindGroupLayout ENTITY_LIGHT_GROUP = BindGroupLayout.builder()
            .withUniform("u_CLHeader", UniformType.TEXEL_BUFFER, GpuFormat.R32_SINT)
            .withUniform("u_CLData", UniformType.TEXEL_BUFFER, GpuFormat.R32_SINT)
            .withUniform("u_CLPool", UniformType.TEXEL_BUFFER, GpuFormat.R32_SINT)
            .withUniform("u_CLPass", UniformType.TEXEL_BUFFER, GpuFormat.R32_SINT)
            .build();

    private static final Identifier VANILLA_ENTITY_SHADER = Identifier.fromNamespaceAndPath("minecraft", "core/entity");
    private static final Identifier VANILLA_ITEM_SHADER = Identifier.fromNamespaceAndPath("minecraft", "core/item");
    private static final Identifier VANILLA_PARTICLE_SHADER = Identifier.fromNamespaceAndPath("minecraft", "core/particle");
    // The block pipelines (falling blocks, piston heads: moving blocks) are not Sodium's, so they had no light at all.
    private static final Identifier VANILLA_BLOCK_SHADER = Identifier.fromNamespaceAndPath("minecraft", "core/block");

    /** Vanilla shader id -> ColorLight's copy (same name, in our namespace). */
    private static final Map<Identifier, Identifier> ENTITY_SHADERS = Map.of(
            VANILLA_ENTITY_SHADER, Identifier.fromNamespaceAndPath("colorlight", "core/cl_entity"),
            VANILLA_ITEM_SHADER, Identifier.fromNamespaceAndPath("colorlight", "core/cl_item"),
            VANILLA_PARTICLE_SHADER, Identifier.fromNamespaceAndPath("colorlight", "core/cl_particle"),
            VANILLA_BLOCK_SHADER, Identifier.fromNamespaceAndPath("colorlight", "core/cl_block"));

    private static final int USAGE_LIGHT = GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_WRITE;
    /** The disabled pages and pool are never read (the header says "off"); they only have to be a valid texel buffer. */
    private static final long DISABLED_PAGE_BYTES = 16L;

    private static volatile boolean worldPass;
    private static volatile boolean handPass;
    private static GpuBuffer disabledHeader, disabledData, disabledPool;
    /** The pass flag the shader reads (u_CLPass): 0 for the world, 1 for the first-person hand. */
    private static GpuBuffer passWorld, passHand;
    private static volatile boolean entityShaderLogged;

    /** True for the vanilla entity, item and particle shader ids (the pipelines that get {@link #ENTITY_LIGHT_GROUP}). */
    public static boolean isEntityShader(Identifier id) {
        return ENTITY_SHADERS.containsKey(id);
    }

    /**
     * For {@link me.mrhikmen.colorlight.client.mixin.minecraft.renderer.ShaderConfigsMixin}: the shader to compile for a vanilla id.
     * ColorLight's copy while the GPU pipeline is on, the vanilla id otherwise.
     */
    public static Identifier entityShader(Identifier id) {
        Identifier ours = ENTITY_SHADERS.get(id);
        if (ours == null || !gpuMode())
            return id;
        if (!entityShaderLogged) {
            entityShaderLogged = true;
            ColorLightClient.LOGGER.info("[ColorLight] Entity, item and particle shaders: ColorLight copies ({})", ours);
        }
        return ours;
    }

    /** Called by LevelRendererMixin around the world render: entities, items and particles are drawn only there. */
    public static void setWorldPass(boolean on) {
        worldPass = on;
    }

    /** Called by GameRendererMixin around the first-person hand (renderItemInHand): lit from the camera's position. */
    public static void setHandPass(boolean on) {
        handPass = on;
    }

    /**
     * Binds the light buffers to the pipeline that was just set. Called after every pipeline switch: the pass keeps the
     * uniforms it was given, but the names only apply to pipelines that have them. Real buffers only in the world pass
     * with the light on; anything else gets the disabled header, so the shader returns the vanilla light.
     */
    public static void bindEntityLight(RenderPass pass) {
        GpuLightVolume v = volume;
        ensureDisabledBuffers();
        if ((worldPass || handPass) && isActive() && v != null && !v.isRawGl() && v.hasBuffers()) {
            pass.setUniform("u_CLHeader", v.headerBuffer());
            pass.setUniform("u_CLData", v.dataBuffer());
            pass.setUniform("u_CLPool", v.poolBuffer());
            pass.setUniform("u_CLPass", handPass ? passHand : passWorld);
            return;
        }
        pass.setUniform("u_CLHeader", disabledHeader);
        pass.setUniform("u_CLData", disabledData);
        pass.setUniform("u_CLPool", disabledPool);
        pass.setUniform("u_CLPass", passWorld);
    }

    private static GpuBuffer createPassFlag(int value) {
        var device = RenderSystem.getDevice();
        GpuBuffer buffer = device.createBuffer(() -> "ColorLight pass flag", USAGE_LIGHT, Integer.BYTES);
        try (var mapping = buffer.map(false, true)) {
            mapping.data().putInt(0, value);
        }
        return buffer;
    }

    private static void ensureDisabledBuffers() {
        if (disabledHeader != null)
            return;
        var device = RenderSystem.getDevice();
        disabledHeader = device.createBuffer(() -> "ColorLight disabled header", USAGE_LIGHT, (long) GpuLightVolume.HEADER_TOTAL * Integer.BYTES);
        disabledData = device.createBuffer(() -> "ColorLight disabled pages", USAGE_LIGHT, DISABLED_PAGE_BYTES);
        disabledPool = device.createBuffer(() -> "ColorLight disabled pool", USAGE_LIGHT, DISABLED_PAGE_BYTES);
        for (GpuBuffer b : new GpuBuffer[] {disabledHeader, disabledData, disabledPool}) {
            try (var mapping = b.map(false, true)) {
                MemoryUtil.memSet(mapping.data(), 0); // all zero: header flag "off"
            }
        }
        passWorld = createPassFlag(0);
        passHand = createPassFlag(1);
    }

    private static void closeDisabledBuffers() {
        for (GpuBuffer b : new GpuBuffer[] {disabledHeader, disabledData, disabledPool, passWorld, passHand}) {
            if (b != null)
                b.close();
        }
        disabledHeader = disabledData = disabledPool = passWorld = passHand = null;
    }

    // ------------------------------------------------------------------------------------------------------
    // Client tick side
    // ------------------------------------------------------------------------------------------------------

    private static GpuLightVolume volume() {
        if (volume == null)
            volume = new GpuLightVolume(ColorLightClient.config.GPU_LIGHT_SECTIONS, nvidiumRenders());
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
        if (failed || !gpuMode() || !nvidiumRenders())
            return null;
        GpuLightVolume v = volume;
        return v == null ? null : v.glBufferNames();
    }

    /**
     * For Flywheel (plain OpenGL): OpenGL names of the volume's {header, data, pool} buffers, whichever way the volume
     * keeps them (raw GL buffers, or blaze3d buffers on the OpenGL backend). Null when there are none: no volume yet,
     * the pipeline failed or is off, or the device is not OpenGL.
     */
    public static int[] flywheelBuffers() {
        if (failed || !gpuMode())
            return null;
        GpuLightVolume v = volume;
        return v == null ? null : v.anyGlNames();
    }

    /** Client-side block change (from the chunk mixin, main thread): the section's solid cells may have changed. */
    public static void onBlockChanged(BlockPos pos) {
        GpuLightVolume v = volume;
        if (v != null)
            v.blockChanged(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
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
        closeDisabledBuffers();
    }

    private ColorLightGpu() {
    }
}
