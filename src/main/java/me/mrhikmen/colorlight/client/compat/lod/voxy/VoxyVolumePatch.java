package me.mrhikmen.colorlight.client.compat.lod.voxy;

/**
 * The text changes of Voxy's LOD shaders for the volume mode. Plain string work, no GL and no Voxy class, so it can be
 * run (and tested) on its own: {@link VoxyLightBridge} supplies the pieces of GLSL and the SSBO binding.
 */
public final class VoxyVolumePatch {

    // vertex shader (quads3.vert + quad_util.glsl, after Voxy's own #import handling)
    public static final String ANCHOR_FUNCTION = "uvec3 makeRemainingAttributes(";
    public static final String ANCHOR_LIGHTING = "vec4 tinting = getLighting(lighting);";
    public static final String ANCHOR_FACETINT = "tinting.rgb *= computeDirectionalFaceTint(isShaded, face);";
    public static final String ANCHOR_INTERDATA = "interData = quad.attributeData;";

    // fragment shader (quads.frag)
    public static final String FRAG_ANCHOR_PIECE1 = "vec4 uint2vec4RGBA(uint colour) {";
    public static final String FRAG_ANCHOR_PIECE2 = "vec4 computeColour(vec2 texturePos, vec4 colour) {";
    public static final String FRAG_ANCHOR_TINT = "return (colour * uint2vec4RGBA(interData.y)) + vec4(0,0,0,float(interData.w&0xFFu)/255);";
    public static final String FRAG_TINT_NEW = "return (colour * cl_tint()) + vec4(0,0,0,float(interData.w&0xFFu)/255);";
    private static final String PART2 = "//@PART2@";

    private VoxyVolumePatch() {
    }

    public static String replaceFirst(String text, String target, String replacement) {
        int i = text.indexOf(target);
        if (i < 0)
            throw new IllegalStateException("anchor not found: " + target);
        return text.substring(0, i) + replacement + text.substring(i + target.length());
    }

    /** True if Voxy's raw shader files have every place the volume mode changes. */
    public static boolean filesHaveAnchors(String vert, String util, String frag) {
        if (vert == null || util == null || frag == null)
            return false;
        String v = vert + "\n" + util;
        return v.contains(ANCHOR_FUNCTION) && v.contains(ANCHOR_LIGHTING) && v.contains(ANCHOR_FACETINT)
                && v.contains(ANCHOR_INTERDATA) && v.contains("LIGHTING_SAMPLER_BINDING") && v.contains("baseSectionPos")
                && frag.contains(FRAG_ANCHOR_PIECE1) && frag.contains(FRAG_ANCHOR_PIECE2) && frag.contains(FRAG_ANCHOR_TINT)
                && frag.contains("layout(location = 0) in flat uvec4 interData;") && frag.contains("getLightmapUv");
    }

    public static boolean isLodFragment(String src) {
        return src.contains(FRAG_ANCHOR_PIECE1) && src.contains(FRAG_ANCHOR_PIECE2) && src.contains("interData")
                && src.contains("outColour");
    }

    /** @param block the GLSL of colorlight_vol_vert.glsl, binding already filled in */
    public static String vertex(String src, String block) {
        String out = replaceFirst(src, ANCHOR_FUNCTION, block + "\n" + ANCHOR_FUNCTION);
        out = replaceFirst(out, ANCHOR_LIGHTING, "vec4 tinting = cl_getLighting(lighting);");
        out = replaceFirst(out, ANCHOR_FACETINT, "{ float clFt = computeDirectionalFaceTint(isShaded, face); tinting.rgb *= clFt; clFlat.y = clFt; }");
        out = replaceFirst(out, ANCHOR_INTERDATA, "clRel = cl_cornerPoint(quad, cornerId);\n    clFlat.z = floor(log2(quad.lodScale) + 0.5);\n    " + ANCHOR_INTERDATA);
        return out;
    }

    /** @param piece the text of colorlight_vol_frag.glsl (two pieces split by //@PART2@), {@code __CL_BINDING__} not yet replaced */
    public static String fragment(String src, String piece, int binding) {
        int i = piece.indexOf(PART2);
        if (i < 0)
            throw new IllegalStateException("fragment piece has no " + PART2);
        String part1 = piece.substring(0, i).replace("__CL_BINDING__", Integer.toString(binding));
        String part2 = piece.substring(i + PART2.length()).replace("__CL_BINDING__", Integer.toString(binding));
        String out = replaceFirst(src, FRAG_ANCHOR_PIECE1, part1 + "\n" + FRAG_ANCHOR_PIECE1);
        out = replaceFirst(out, FRAG_ANCHOR_PIECE2, part2 + "\n" + FRAG_ANCHOR_PIECE2);
        return replaceFirst(out, FRAG_ANCHOR_TINT, FRAG_TINT_NEW);
    }
}
