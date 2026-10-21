package me.mrhikmen.colorlight.client.compat.flywheel;

import me.mrhikmen.colorlight.client.ColorLightClient;
import me.mrhikmen.colorlight.client.compat.sable.SableFlywheelScenes;
import me.mrhikmen.colorlight.client.compat.sable.SableLightVolume;
import me.mrhikmen.colorlight.client.core.render.gpu.ColorLightGpu;

import net.fabricmc.loader.api.FabricLoader;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL41C;
import org.lwjgl.opengl.GL45C;

/**
 * Flywheel (instanced / indirect rendering of block entities, entities, Create contraptions...) on the GPU light
 * pipeline.
 * <p>
 * Flywheel builds its fragment shaders from text. Three small source patches (mixin {@code SourceFileMixin}) make them
 * read ColorLight's light volume:
 * <ul>
 *     <li>every light shader ({@code flywheel:light/*.glsl}, anything that defines {@code flw_shaderLight}) keeps its
 *     own code, renamed, and gets a wrapper that runs it and then {@code cl_flwApply()};</li>
 *     <li>{@code internal/common.frag} multiplies by the result of {@code cl_flwApply()} instead of the lightmap
 *     lookup when it ran.</li>
 * </ul>
 * Whether ColorLight is used at a given moment is the volume header's enabled flag, read in the shader, so the config
 * switch, shader packs and failures need no shader rebuild; without the GPU pipeline Flywheel's light is unchanged.
 * <p>
 * The volume's three buffers go to texture units 10..12 (Flywheel uses 0..9) while the engine draws
 * ({@code EngineImplMixin}); the samplers are pointed at them when a program is linked ({@code ContextShaderMixin}).
 */
public final class ColorLightFlywheelCompat {

    // 10..12: world volume; 13..15: Sable scene table, sub-level volume data and pool (Flywheel itself uses 0..9)
    private static final int FIRST_UNIT = 10;
    private static final String[] UNIFORMS = {"u_CLHeader", "u_CLData", "u_CLPool", "u_CLScenes", "u_CLDataP", "u_CLPoolP"};
    private static final String INCLUDE_WORLD = "#include \"colorlight:colorlight/flw_apply.glsl\"\n";
    private static final String INCLUDE_SABLE = "#include \"colorlight:colorlight/flw_apply_sable.glsl\"\n";
    private static final String INCLUDE_CORE = "#include \"colorlight:colorlight/flw.glsl\"\n";
    private static final boolean SABLE = FabricLoader.getInstance().isModLoaded("sable") || classExists("dev.ryanhcode.sable.Sable");
    private static final String LIGHT_LINE = "lightColor = texture(flw_lightTex, clamp(flw_fragLight, 0.5 / 16.0, 15.5 / 16.0));";

    private static int[] textures;
    private static int dummyBuffer;
    private static boolean bound;
    private static boolean reported;

    // ------------------------------------------------------------------------------------------------------
    // Shader text
    // ------------------------------------------------------------------------------------------------------

    /** Called with the text of every Flywheel shader file; returns it unchanged unless it is one we patch. */
    public static String patch(String source) {
        if (source == null || source.contains("cl_flwApply") || source.contains("colorlight/flw"))
            return source;
        try {
            if (source.contains("void flw_shaderLight()")) {
                // Sable's own copies of the light shaders mention its lighting scene; only those can use the sub-level window
                boolean sableLight = SABLE && source.contains("flw_vertexLightingSceneId");
                return (sableLight ? INCLUDE_SABLE : INCLUDE_WORLD)
                        + source.replace("void flw_shaderLight()", "void _cl_flwOrigLight()")
                        + "\nvoid flw_shaderLight() {\n    _cl_flwOrigLight();\n    cl_flwApply();\n}\n";
            }
            if (source.contains(LIGHT_LINE)) {
                return INCLUDE_CORE + source.replace(LIGHT_LINE,
                        LIGHT_LINE + "\n        if (_cl_flwActive) {\n            lightColor = vec4(_cl_flwMult, 1.0);\n        }");
            }
        } catch (Throwable t) {
            failed(t);
        }
        return source;
    }

    // ------------------------------------------------------------------------------------------------------
    // GL
    // ------------------------------------------------------------------------------------------------------

    /** A Flywheel program was just linked and is current: point the volume samplers at units 10..12. */
    public static void onLink() {
        try {
            int program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
            if (program == 0)
                return;
            for (int i = 0; i < UNIFORMS.length; i++) {
                int location = GL20C.glGetUniformLocation(program, UNIFORMS[i]);
                if (location >= 0)
                    GL41C.glProgramUniform1i(program, location, FIRST_UNIT + i);
            }
        } catch (Throwable t) {
            failed(t);
        }
    }

    /** Before Flywheel draws: the volumes (or all-zero stand-ins, whose header says "disabled") on units 10..15. */
    public static void bind() {
        try {
            if (dummyBuffer == 0) {
                dummyBuffer = GL45C.glCreateBuffers();
                GL45C.glNamedBufferData(dummyBuffer, new int[64], GL15C.GL_STATIC_DRAW);
            }

            int[] names = ColorLightGpu.externalBuffers();
            if (names == null)
                names = new int[]{dummyBuffer, dummyBuffer, dummyBuffer};

            int scenes = dummyBuffer, dataP = dummyBuffer, poolP = dummyBuffer;
            if (SABLE && names[0] != dummyBuffer) {
                try {
                    SableFlywheelScenes.update();
                    int[] sub = SableLightVolume.buffers();
                    if (sub != null) {
                        scenes = SableFlywheelScenes.buffer();
                        dataP = sub[0];
                        poolP = sub[1];
                    }
                } catch (Throwable t) {
                    failed(t);
                }
            }
            int[] all = {names[0], names[1], names[2], scenes, dataP, poolP};

            if (textures == null) {
                textures = new int[UNIFORMS.length];
                for (int i = 0; i < textures.length; i++)
                    textures[i] = GL45C.glCreateTextures(GL31C.GL_TEXTURE_BUFFER);
            }
            for (int i = 0; i < UNIFORMS.length; i++) {
                // re-attached every time: the buffers are recreated when the "GPU light memory" setting changes
                GL45C.glTextureBuffer(textures[i], GL30C.GL_R32I, all[i]);
                GL45C.glBindTextureUnit(FIRST_UNIT + i, textures[i]);
            }
            bound = true;
        } catch (Throwable t) {
            failed(t);
        }
    }

    /** After Flywheel drew: give the units back. */
    public static void unbind() {
        if (!bound)
            return;
        bound = false;
        try {
            for (int i = 0; i < UNIFORMS.length; i++)
                GL45C.glBindTextureUnit(FIRST_UNIT + i, 0);
        } catch (Throwable t) {
            failed(t);
        }
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, ColorLightFlywheelCompat.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void failed(Throwable t) {
        if (!reported) {
            reported = true;
            ColorLightClient.LOGGER.warn("[ColorLight] Flywheel compat failed; Flywheel keeps its own light", t);
        }
    }

    private ColorLightFlywheelCompat() {
    }
}
