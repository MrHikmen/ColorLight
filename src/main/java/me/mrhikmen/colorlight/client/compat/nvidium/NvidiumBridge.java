package me.mrhikmen.colorlight.client.compat.nvidium;

import me.mrhikmen.colorlight.client.ColorLightClient;

import me.mrhikmen.colorlight.client.core.render.gpu.ColorLightGpu;
import me.mrhikmen.colorlight.client.core.render.gpu.GpuLightVolume;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL41C;
import org.lwjgl.opengl.GL45C;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.stream.Collectors;

/**
 * Nvidium support, entirely on ColorLight's side (the mixins are in {@code mixin.nvidium}).
 * <p>
 * Nvidium cancels Sodium's terrain draw, so ColorLight's Sodium hooks never run. Instead:
 * <ul>
 *     <li>{@link #patchShader}: the text of Nvidium's mesh / fragment terrain shaders is patched. The mesh shaders pass
 *     three more interpolants (vertex colour, lightmap coordinate, camera-relative position); the fragment shader gets
 *     colorlight_data.glsl + colorlight_light.glsl inlined and swaps its per-vertex light for the per-pixel one.
 *     Nvidium's barycentric fragment mode is switched off, because the position has to be interpolated.</li>
 *     <li>{@link #bindCurrentProgram}: before each terrain draw the light volume's three GL buffers are attached to the
 *     current program as texel buffers (units 5..7; Nvidium uses 0..4).</li>
 * </ul>
 * The light volume lives in plain GL buffers for this ({@link GpuLightVolume} raw mode). Whether ColorLight lights the
 * terrain at a given moment is the header's enabled flag, so the config switch, shader packs and failures need no
 * shader rebuild: the shader falls back to Nvidium's own light.
 */
public final class NvidiumBridge {

    private static final int FIRST_UNIT = 5;
    private static final String[] UNIFORMS = {"u_CLHeader", "u_CLData", "u_CLPool"};

    private static volatile boolean patchedAny;
    private static boolean warned;
    private static int[] textures;

    private static String dataGlsl, lightGlsl;

    private NvidiumBridge() {
    }

    // ------------------------------------------------------------------------------------------------------
    // Shader text
    // ------------------------------------------------------------------------------------------------------

    /** Called with the finished source of each Nvidium shader; returns it unchanged unless it is a terrain mesh / fragment shader. */
    public static String patchShader(String path, String source) {
        if (source == null)
            return null;

        boolean frag = path.equals("terrain/frag.frag");
        boolean mesh = path.equals("terrain/mesh.glsl");
        boolean translucentMesh = path.equals("terrain/translucent/mesh.glsl");
        if (!frag && !mesh && !translucentMesh)
            return source;

        if (source.contains("v_CLRel")) // parse(path) calls parse(path, defines): already patched
            return source;

        if (!ColorLightGpu.externalRendererSupported())
            return source;

        try {
            String patched;
            if (frag)
                patched = patchFragment(source);
            else if (mesh)
                patched = patchMesh(source, "OUT[id].v_colour = computeMultiplier(V);", "decodeVertexPosition(V) + origin + subchunkOffset.xyz");
            else
                patched = patchMesh(source, "OUT[outId].v_colour = tint.rgb;", "pos + subchunkOffset.xyz");

            patchedAny = true;
            return patched;
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                ColorLightClient.LOGGER.error("[ColorLight] Could not patch Nvidium's shader {} ({}); coloured light stays off with Nvidium", path, t.toString());
            }
            return source;
        }
    }

    private static final String MEMBERS = "vec3 v_colour;\n    vec3 v_CLColor;\n    vec2 v_CLLight;\n    vec3 v_CLRel;\n";

    private static String removeBarycentric(String src) {
        return src.replaceAll("(?m)^#define USE_NV_FRAGMENT_SHADER_BARYCENTRIC.*$", "");
    }

    private static String addMembers(String src, String closing) {
        // the Interpolants block of the mesh shaders ("} OUT[];") / the fragment shader ("};") ends right after v_colour
        String regex = "vec3 v_colour;\\s*" + closing;
        if (!java.util.regex.Pattern.compile(regex).matcher(src).find())
            throw new IllegalStateException("Interpolants block not found");
        return src.replaceFirst(regex, java.util.regex.Matcher.quoteReplacement(MEMBERS + closing.replace("\\", "")));
    }

    private static String patchMesh(String src, String anchor, String relExpr) {
        src = removeBarycentric(src);
        src = addMembers(src, "\\} OUT\\[\\];");
        if (!src.contains(anchor))
            throw new IllegalStateException("mesh anchor not found: " + anchor);

        String id = anchor.startsWith("OUT[outId]") ? "OUT[outId]" : "OUT[id]";
        String add = anchor + "\n"
                + "    " + id + ".v_CLColor = decodeVertexColour(V).rgb;\n"
                + "    " + id + ".v_CLLight = decodeLightUV(V);\n"
                + "    " + id + ".v_CLRel = " + relExpr + "; // vector camera -> vertex\n";
        return src.replace(anchor, add);
    }

    private static String patchFragment(String src) throws IOException {
        src = removeBarycentric(src);
        src = addMembers(src, "\\};");

        String useAnchor = "color.rgb *= v_colour;";
        if (!src.contains(useAnchor))
            throw new IllegalStateException("fragment anchor not found");
        src = src.replace(useAnchor, "#ifndef OIT_ALPHA_ONLY\n        color.rgb *= cl_multiplier(v_colour);\n#else\n        color.rgb *= v_colour;\n#endif");

        int main = src.lastIndexOf("void main()");
        if (main < 0)
            throw new IllegalStateException("main() not found");

        String block = "\n// ---- ColorLight ----\n"
                + "#ifndef OIT_ALPHA_ONLY\n"
                + "#define CL_FRAGMENT\n"
                + data() + "\n"
                + "#define CL_LIGHT_TEX tex_light\n"
                + light() + "\n"
                + "// Replaces the per-vertex \"vertex colour * lightmap\" by ColorLight's per-pixel light. While ColorLight is off\n"
                + "// (config, shader pack, failure) the header says so and Nvidium's own multiplier is used unchanged.\n"
                + "vec3 cl_multiplier(vec3 vanilla) {\n"
                + "    if (!cl_enabled()) {\n"
                + "        return vanilla;\n"
                + "    }\n"
                + "    // v_CLRel is the vector camera -> pixel; cl_lightAt wants it relative to the camera's integer block\n"
                + "    return v_CLColor * cl_lightAt(v_CLRel + cl_cameraFrac(), v_CLLight);\n"
                + "}\n"
                + "#endif\n"
                + "// ---- end ColorLight ----\n\n";
        return src.substring(0, main) + block + src.substring(main);
    }

    private static String data() throws IOException {
        if (dataGlsl == null)
            dataGlsl = read("colorlight_data.glsl");
        return dataGlsl;
    }

    private static String light() throws IOException {
        if (lightGlsl == null)
            lightGlsl = read("colorlight_light.glsl");
        return lightGlsl;
    }

    private static String read(String file) throws IOException {
        var resource = Minecraft.getInstance().getResourceManager()
                .getResource(Identifier.fromNamespaceAndPath("colorlight", "shaders/include/" + file));
        if (resource.isEmpty())
            throw new IOException("missing " + file);
        try (BufferedReader reader = new BufferedReader(resource.get().openAsReader())) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // Buffers
    // ------------------------------------------------------------------------------------------------------

    /** Attaches the light volume to the program that is current (Nvidium has just bound its terrain program). */
    public static void bindCurrentProgram() {
        if (!patchedAny)
            return;

        int[] names = ColorLightGpu.externalBuffers();
        if (names == null)
            return; // no volume (yet): the samplers stay empty, the shader reads that as "disabled"

        int program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);

        if (textures == null) {
            textures = new int[UNIFORMS.length];
            for (int i = 0; i < textures.length; i++)
                textures[i] = GL45C.glCreateTextures(GL31C.GL_TEXTURE_BUFFER);
        }

        for (int i = 0; i < UNIFORMS.length; i++) {
            // re-attached every draw: the buffers are recreated when the "GPU light memory" setting changes
            GL45C.glTextureBuffer(textures[i], GL30C.GL_R32I, names[i]);
            GL45C.glBindTextureUnit(FIRST_UNIT + i, textures[i]);

            int location = GL20C.glGetUniformLocation(program, UNIFORMS[i]);
            if (location >= 0)
                GL41C.glProgramUniform1i(program, location, FIRST_UNIT + i);
        }
    }
}
