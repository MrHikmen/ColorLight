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
import net.minecraft.client.renderer.RenderType;
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
        bindBuffers(program, new int[]{header, data[0], data[1]});
        set3i(program, "u_CLAnchor", anchorX, anchorY, anchorZ);
        set3f(program, "u_CLCamOff", (float) (camPlot.x - anchorX), (float) (camPlot.y - anchorY), (float) (camPlot.z - anchorZ));
        int loc = GL20C.glGetUniformLocation(program, "u_CLPageBase");
        if (loc >= 0)
            GL41C.glProgramUniform1i(program, loc, window.pageBase());

        return ours;
    }

    private static void set3i(int program, String name, int x, int y, int z) {
        int loc = GL20C.glGetUniformLocation(program, name);
        if (loc >= 0)
            GL41C.glProgramUniform3i(program, loc, x, y, z);
    }

    private static void set3f(int program, String name, float x, float y, float z) {
        int loc = GL20C.glGetUniformLocation(program, name);
        if (loc >= 0)
            GL41C.glProgramUniform3f(program, loc, x, y, z);
    }

    private static void bindBuffers(int program, int[] names) {
        if (textures == null) {
            textures = new int[UNIFORMS.length];
            for (int i = 0; i < textures.length; i++)
                textures[i] = GL45C.glCreateTextures(GL31C.GL_TEXTURE_BUFFER);
        }
        for (int i = 0; i < UNIFORMS.length; i++) {
            GL45C.glTextureBuffer(textures[i], GL30C.GL_R32I, names[i]);
            GL45C.glBindTextureUnit(FIRST_UNIT + i, textures[i]);
            int location = GL20C.glGetUniformLocation(program, UNIFORMS[i]);
            if (location >= 0)
                GL41C.glProgramUniform1i(program, location, FIRST_UNIT + i);
        }
    }

    private ColorLightSableCompat() {
    }
}
