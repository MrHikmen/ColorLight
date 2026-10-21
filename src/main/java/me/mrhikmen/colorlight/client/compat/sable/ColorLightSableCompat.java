package me.mrhikmen.colorlight.client.compat.sable;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import me.mrhikmen.colorlight.client.ColorLightClient;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngineHolder;
import me.mrhikmen.colorlight.client.core.render.gpu.ColorLightGpu;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL41C;
import org.lwjgl.opengl.GL45C;

/**
 * Sable sub-levels on the GPU light pipeline.
 * <p>
 * With Sodium, Sable draws sub-levels through the vanilla chunk renderer and vanilla core shaders, not through
 * Sodium's terrain program, so ColorLight's terrain shader never sees them. For the GPU pipeline the meshes carry no
 * colour, so here the core shader of each sub-level draw is replaced by a ColorLight copy
 * ({@code assets/colorlight/shaders/core/sublevel*}) that reads the sub-level's window in {@link SableLightVolume}.
 * <p>
 * <b>Coordinates.</b> Sable draws a section with {@code pos = Position + ChunkOffset}, where {@code pos} equals
 * (plot-space position of the vertex) minus (plot-space position of the camera), the camera being mapped into plot
 * space through the sub-level's render pose. The shader therefore only needs the camera's plot-space position relative
 * to the window's anchor ({@code u_CLCamOff}), computed here in double precision, exactly like the world shader gets
 * the camera fraction.
 */
public final class ColorLightSableCompat {

    private static final ResourceLocation SOLID_ID = ResourceLocation.fromNamespaceAndPath("colorlight", "sublevel_solid");
    private static final ResourceLocation CUTOUT_ID = ResourceLocation.fromNamespaceAndPath("colorlight", "sublevel_cutout");
    private static final ResourceLocation TRANSLUCENT_ID = ResourceLocation.fromNamespaceAndPath("colorlight", "sublevel_translucent");

    private static final int FIRST_UNIT = 5;
    private static final String[] UNIFORMS = {"u_CLHeader", "u_CLData", "u_CLPool"};

    private static ShaderInstance solid, cutout, translucent;
    private static int[] textures;

    public static void register() {
        CoreShaderRegistrationCallback.EVENT.register(context -> {
            context.register(SOLID_ID, DefaultVertexFormat.BLOCK, s -> solid = s);
            context.register(CUTOUT_ID, DefaultVertexFormat.BLOCK, s -> cutout = s);
            context.register(TRANSLUCENT_ID, DefaultVertexFormat.BLOCK, s -> translucent = s);
        });
        // light between the world and sub-levels (dynamic layer); runs after the engine tick of the same frame
        ClientTickEvents.END_CLIENT_TICK.register(SablePlotScanner::tick); // lights that were on a sub-level before it arrived
        ClientTickEvents.END_CLIENT_TICK.register(SableLightLinker::tick);
        ColorLightClient.LOGGER.info("[ColorLight] Sable compat initialized");
    }

    private static ShaderInstance replacementFor(ShaderInstance original) {
        String name = original.getName();
        if (name == null)
            return null;
        return switch (name) {
            case "rendertype_solid" -> solid;
            case "rendertype_cutout", "rendertype_cutout_mipped" -> cutout;
            case "rendertype_translucent" -> translucent;
            default -> null;
        };
    }

    /**
     * Called before a sub-level section layer is drawn. Returns the ColorLight shader to draw with (applied, light
     * volume bound), or null to keep Sable's own.
     */
    static ShaderInstance prepare(Object renderData, ShaderInstance original, Matrix4f modelView, double camX, double camY, double camZ) {
        if (!ColorLightGpu.isActive() || !ColorLightClient.config.ENABLE || ColorLightEngineHolder.get() == null)
            return null;

        ShaderInstance ours = replacementFor(original);
        if (ours == null)
            return null;

        int header = ColorLightGpu.mainHeaderBuffer();
        if (header == 0)
            return null;

        SableAccess.Info info = SableAccess.read(renderData);
        if (info == null)
            return null;

        SableLightVolume.Window window = SableLightVolume.acquire(info.subLevel(),
                ((info.minX() + info.maxX()) >> 1) >> 4,
                ((info.minY() + info.maxY()) >> 1) >> 4,
                ((info.minZ() + info.maxZ()) >> 1) >> 4);
        int[] data = SableLightVolume.buffers();
        if (window == null || data == null)
            return null;

        // Camera in plot space: world = R * (P - origin) + renderPos, with renderPos = position - R * (rotationPoint - origin)
        Vector3d cor = info.orientation().transform(new Vector3d(info.rotationPoint()).sub(info.origin()));
        Vector3d renderPos = new Vector3d(info.position()).sub(cor);
        Vector3d camPlot = info.orientation().transformInverse(new Vector3d(camX, camY, camZ).sub(renderPos)).add(info.origin());

        int anchorX = window.ax << 4, anchorY = window.ay << 4, anchorZ = window.az << 4;

        Window mcWindow = Minecraft.getInstance().getWindow();
        ours.setDefaultUniforms(VertexFormat.Mode.QUADS, modelView, RenderSystem.getProjectionMatrix(), mcWindow);
        ours.apply();

        int program = ours.getId();
        ProgramLocs locs = locsOf(ours, program);
        bindBuffers(locs, header, data[0], data[1]);
        if (locs.anchor >= 0)
            GL41C.glProgramUniform3i(program, locs.anchor, anchorX, anchorY, anchorZ);
        if (locs.camOff >= 0)
            GL41C.glProgramUniform3f(program, locs.camOff, (float) (camPlot.x - anchorX), (float) (camPlot.y - anchorY), (float) (camPlot.z - anchorZ));
        if (locs.pageBase >= 0)
            GL41C.glProgramUniform1i(program, locs.pageBase, window.pageBase());

        return ours;
    }

    /**
     * Uniform locations of one of our programs, looked up once instead of with glGetUniformLocation (a string lookup
     * in the driver) several times per sub-level per draw. Keyed by the ShaderInstance itself and checked against its
     * program id, so a reloaded shader never reuses stale locations.
     */
    private static final class ProgramLocs {
        int program = -1;
        int anchor, camOff, pageBase;
        final int[] samplers = new int[UNIFORMS.length];
        boolean samplersSet;
    }

    private static final java.util.Map<ShaderInstance, ProgramLocs> LOCS = new java.util.IdentityHashMap<>();

    private static ProgramLocs locsOf(ShaderInstance shader, int program) {
        ProgramLocs l = LOCS.get(shader);
        if (l == null) {
            l = new ProgramLocs();
            LOCS.put(shader, l);
        }
        if (l.program != program) {
            l.program = program;
            l.anchor = GL20C.glGetUniformLocation(program, "u_CLAnchor");
            l.camOff = GL20C.glGetUniformLocation(program, "u_CLCamOff");
            l.pageBase = GL20C.glGetUniformLocation(program, "u_CLPageBase");
            for (int i = 0; i < UNIFORMS.length; i++)
                l.samplers[i] = GL20C.glGetUniformLocation(program, UNIFORMS[i]);
            l.samplersSet = false;
        }
        return l;
    }

    private static void bindBuffers(ProgramLocs locs, int header, int data, int pool) {
        if (textures == null) {
            textures = new int[UNIFORMS.length];
            for (int i = 0; i < textures.length; i++)
                textures[i] = GL45C.glCreateTextures(GL31C.GL_TEXTURE_BUFFER);
        }
        int program = locs.program;
        for (int i = 0; i < UNIFORMS.length; i++) {
            int name = i == 0 ? header : i == 1 ? data : pool;
            GL45C.glTextureBuffer(textures[i], GL30C.GL_R32I, name); // always: a recreated buffer can reuse its GL name
            GL45C.glBindTextureUnit(FIRST_UNIT + i, textures[i]);
            if (!locs.samplersSet && locs.samplers[i] >= 0)
                GL41C.glProgramUniform1i(program, locs.samplers[i], FIRST_UNIT + i);
        }
        locs.samplersSet = true;
    }

    private ColorLightSableCompat() {
    }
}
