// ColorLight: coloured light for Voxy's LODs, volume mode (vertex part). This text is inserted into Voxy's vertex shader
// (quads3.vert + quad_util.glsl) by me.mrhikmen.colorlight.client.compat.lod.voxy.VoxyLightBridge; __CL_BINDING__ is
// replaced with a free SSBO binding.
//
// The light itself is NOT worked out here any more: the fragment shader reads it per pixel from a volume that the CPU
// keeps (see LodLightVolume and colorlight_vol_frag.glsl). The vertex shader only passes on what the fragment shader
// needs: where the pixel is, the sky light level of the quad and the face's brightness factor.
//
// Buffer header (ints), written by VoxyLightBridge:  [2] enabled (1/0)  [3] tint gamma (float bits)  [4] tint strength
// (float bits)

#ifdef LIGHTING_SAMPLER_BINDING

layout(binding = __CL_BINDING__, std430) readonly restrict buffer ColorLightLodBuffer {
    int clLod[];
};

// the corner's position, in blocks relative to baseSectionPos * 32 (exact enough; the fragment shader adds the integer base)
layout(location = 2) out vec3 clRel;
// per quad: x = sky light level 0..15, y = the face's directional brightness factor, z = the quad's level of detail (0 = blocks)
layout(location = 3) out flat vec3 clFlat;

vec3 cl_cornerPoint(in QuadData quad, uint cornerId) {
    vec2 cornerMask = vec2((cornerId >> 1) & 1u, cornerId & 1u) * quad.lodScale;
    return quad.basePoint + swizzelDataAxis(quad.axis, vec3(quad.quadSizeAddin * cornerMask, 0));
}

// Voxy's lighting for the quad. Voxy's own block light is never used on the LODs: the block light comes only from
// ColorLight's sources, added per pixel by the fragment shader, so the quad starts from its sky light alone.
vec4 cl_getLighting(uint lighting) {
    clFlat.x = float(lighting & 0xFu);
    if (clLod[2] != 1) {
        return getLighting(lighting);
    }
    return getLighting(lighting & 0xFu);
}

#endif
