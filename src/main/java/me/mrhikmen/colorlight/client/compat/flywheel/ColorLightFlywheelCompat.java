package me.mrhikmen.colorlight.client.compat.flywheel;

import me.mrhikmen.colorlight.client.ColorLightClient;
import me.mrhikmen.colorlight.client.core.render.gpu.ColorLightGpu;

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
 * <p>
 * The Flywheel of Create-Fly ({@code com.zurrtum.create.client.flywheel}) and the official one
 * ({@code dev.engine_room.flywheel}) are both targeted by the three mixins; nothing happens without either.
 * Flywheel draws with plain OpenGL, so it reads the volume through the OpenGL names behind the volume's buffers
 * ({@link ColorLightGpu#flywheelBuffers}); on a Vulkan device there are none and Flywheel keeps its own light.
 */
public final class ColorLightFlywheelCompat {

    // 10..12: world volume (Flywheel itself uses 0..9)
    private static final int FIRST_UNIT = 10;
    private static final String[] UNIFORMS = {"u_CLHeader", "u_CLData", "u_CLPool"};
    private static final String INCLUDE_WORLD = "#include \"colorlight:colorlight/flw_apply.glsl\"\n";
    private static final String INCLUDE_CORE = "#include \"colorlight:colorlight/flw.glsl\"\n";
    /** The lightmap lookup of common.frag; its argument differs between Flywheel versions, so it is matched, not spelled out. */
    private static final java.util.regex.Pattern LIGHT_LINE = java.util.regex.Pattern.compile("lightColor\\s*=\\s*texture\\(\\s*flw_lightTex\\s*,[^;]*;");

    private static int[] textures;
    private static int dummyBuffer;
    private static boolean bound;
    private static boolean reported;
    private static boolean reportedNoBuffers, reportedBuffers;
    private static boolean reportedPatch;

    // ------------------------------------------------------------------------------------------------------
    // Shader text
    // ------------------------------------------------------------------------------------------------------

    /** Called with the text of every Flywheel shader file; returns it unchanged unless it is one we patch. */
    public static String patch(String source) {
        if (source == null || source.contains("cl_flwApply") || source.contains("colorlight/flw"))
            return source;
        try {
            if (source.contains("void flw_shaderLight()")) {
                if (!reportedPatch) {
                    reportedPatch = true;
                    ColorLightClient.LOGGER.info("[ColorLight] Flywheel: patching the light shaders");
                }
                return INCLUDE_WORLD
                        + source.replace("void flw_shaderLight()", "void _cl_flwOrigLight()")
                        + "\nvoid flw_shaderLight() {\n    _cl_flwOrigLight();\n    cl_flwApply();\n}\n";
            }
            var m = LIGHT_LINE.matcher(source);
            if (m.find()) {
                return INCLUDE_CORE + source.substring(0, m.end())
                        + "\n        if (_cl_flwActive) {\n            lightColor = vec4(_cl_flwMult, 1.0);\n        }"
                        + source.substring(m.end());
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

    /** Before Flywheel draws: the volumes (or all-zero stand-ins, whose header says "disabled") on units 10..12. */
    public static void bind() {
        try {
            if (dummyBuffer == 0) {
                dummyBuffer = GL45C.glCreateBuffers();
                GL45C.glNamedBufferData(dummyBuffer, new int[64], GL15C.GL_STATIC_DRAW);
            }

            int[] names = ColorLightGpu.flywheelBuffers();
            if (names == null) {
                if (!reportedNoBuffers) {
                    reportedNoBuffers = true;
                    ColorLightClient.LOGGER.info("[ColorLight] Flywheel: the light volume has no OpenGL buffers (yet); Flywheel uses its own light until it has");
                }
                names = new int[]{dummyBuffer, dummyBuffer, dummyBuffer};
            } else if (!reportedBuffers) {
                reportedBuffers = true;
                ColorLightClient.LOGGER.info("[ColorLight] Flywheel: reading the light volume (GL buffers {}, {}, {})", names[0], names[1], names[2]);
            }

            int[] all = names;

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

    private static void failed(Throwable t) {
        if (!reported) {
            reported = true;
            ColorLightClient.LOGGER.warn("[ColorLight] Flywheel compat failed; Flywheel keeps its own light", t);
        }
    }

    private ColorLightFlywheelCompat() {
    }
}
