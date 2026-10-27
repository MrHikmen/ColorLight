package me.mrhikmen.colorlight.client.core.render.gpu;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL41C;
import org.lwjgl.opengl.GL45C;

/**
 * Attaches the light volume's three GL buffers to the GL program that is current (Sodium's terrain program) as
 * texel buffers. Sodium uses texture units 0..2 for its own samplers, so ours go to 5..7.
 */
final class SodiumGlBinder {

    private static final int FIRST_UNIT = 5;
    private static final String[] UNIFORMS = {"u_CLHeader", "u_CLData", "u_CLPool"};

    private static int[] textures;

    /**
     * Binds the three buffers to texture units 5..7 without touching any program. The terrain shaders declare their
     * samplers with {@code layout(binding = 5..7)} (CL_BIND_UNITS), so this alone is enough for them, even if the
     * per-draw hook never runs (another mod changed Sodium's draw code, a Veil/Connector setup...).
     */
    static void bindUnits(int[] bufferNames) {
        if (textures == null) {
            textures = new int[UNIFORMS.length];
            for (int i = 0; i < textures.length; i++)
                textures[i] = GL45C.glCreateTextures(GL31C.GL_TEXTURE_BUFFER);
        }

        for (int i = 0; i < UNIFORMS.length; i++) {
            // re-attached every time: the buffers are recreated when the "GPU light memory" setting changes
            GL45C.glTextureBuffer(textures[i], GL30C.GL_R32I, bufferNames[i]);
            GL45C.glBindTextureUnit(FIRST_UNIT + i, textures[i]);
        }
    }

    static void bindCurrentProgram(int[] bufferNames) {
        int program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);

        bindUnits(bufferNames);

        for (int i = 0; i < UNIFORMS.length; i++) {
            int location = GL20C.glGetUniformLocation(program, UNIFORMS[i]);
            if (location >= 0)
                GL41C.glProgramUniform1i(program, location, FIRST_UNIT + i);
        }
    }

    private SodiumGlBinder() {
    }
}
