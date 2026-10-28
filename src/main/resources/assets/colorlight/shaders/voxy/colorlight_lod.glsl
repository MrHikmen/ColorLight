// ColorLight: coloured light for Voxy's LODs. This text is inserted into Voxy's vertex shader (quads3.vert) by
// me.mrhikmen.colorlight.client.compat.lod.voxy.VoxyLightBridge; __CL_BINDING__ is replaced with a free SSBO binding.
//
// Buffer layout (ints), written by VoxyLightBridge, built by VoxyLightCache (a list of coloured light sources found
// in Voxy's own saved level-0 data; the colour of every LOD block is worked out right here from the sources
// around it - nothing is precomputed per distance or per LOD level):
//   [0] camera block x   [1] camera block z   [2] enabled (1/0)
//   [3] tint gamma (float bits)   [4] tint strength (float bits)   [5] light reach in blocks (1..16)
//   [7] 1: per-channel fading (the "additive" setting), 0: the hue-keeping fade
//   [16 + ((cell.x & 511) | (cell.y & 511) * 512) * 2 + 0/1]: first source / source count of a 16x16-block cell
//   [16 + 524288 + i * 4 + 0..3]: source i = x, y, z, 0xSSBBGGRR; SS: bits 0..4 the block's own reach in blocks
//   (not used any more, a block's strength is in its colour), bits 5..7 which distance table
//   (below) its propagation method uses (0 = the method of the settings)
//   [16 + 524288 + 131072 * 4 + t * 35937 + ((x + 16) * 33 + (y + 16)) * 33 + (z + 16)]: float bits, table t = how far (in blocks
//   walked along an axis) the propagation method carries light from a source to the block at offset x, y, z (1e9 = never)
// The table is toroidal and valid out to 180 cells (2880 blocks) from where it was filled, with a 16 cell margin
// for the camera moving before the next refill. Keep the numbers in sync with VoxyLightCache / VoxyLightBridge.

#ifdef LIGHTING_SAMPLER_BINDING

vec4 cl_getLm(uint i) {
    return getLighting(i);
}

vec3 cl_quadStart = vec3(0.0); // the first block of the quad, in world blocks
vec3 cl_quadExt = vec3(1.0);   // how many blocks the quad covers along x, y and z
vec3 cl_quadFront = vec3(0.0); // from the middle of the quad to the middle of the block cell right in front of its face

//@COMMON@
// Called by setupQuad for every quad: where it is and how big, in blocks. A face of a LOD cell lies in a plane: its
// two sizes (quadSize, in cells) run along the two axes other than the face's own (like swizzelDataAxis in Voxy's
// quad_util.glsl), the third one is just the cell itself.
void cl_setQuad(vec3 sectionLocalPos, float lodScale, ivec3 baseSection, uint lodLevel, uint face, ivec2 quadSize) {
    cl_quadStart = vec3(baseSectionPos * 32 + (baseSection << 5)) + floor(sectionLocalPos * lodScale);
    vec2 size = vec2(quadSize) * lodScale;
    uint axis = face >> 1;
    cl_quadExt = (axis == 0u) ? vec3(size.x, lodScale, size.y)
               : (axis == 1u) ? vec3(size.x, size.y, lodScale)
               : vec3(lodScale, size.x, size.y);
    // The near-field renderer lights a face with the cell in front of it (the air next to the block), not with the block
    // itself: one step further along the face's normal (Voxy's faces: bit 0 = positive side; axis 0 = y, 1 = z, 2 = x).
    float side = ((face & 1u) == 1u) ? 1.0 : -1.0;
    float front = 0.5 * lodScale + 0.5;
    cl_quadFront = (axis == 0u) ? vec3(0.0, side * front, 0.0)
                 : (axis == 1u) ? vec3(0.0, 0.0, side * front)
                 : vec3(side * front, 0.0, 0.0);
}

// The light of the whole quad. A quad can be long (Voxy merges equal blocks into one), and its colour is one value for
// all of it, so a long quad is measured at both ends and in the middle and gets the weakest of the three: a strip that
// starts inside a light and runs far out of it is not lit along its whole length.
vec3 cl_light() {
    vec3 centre = cl_quadStart + cl_quadExt * 0.5 + cl_quadFront;
    float longest = max(cl_quadExt.x, max(cl_quadExt.y, cl_quadExt.z));
    if (longest <= 2.0) {
        return cl_lightAt(centre);
    }
    vec3 dir = (cl_quadExt.x >= cl_quadExt.y && cl_quadExt.x >= cl_quadExt.z) ? vec3(1.0, 0.0, 0.0)
             : (cl_quadExt.y >= cl_quadExt.z) ? vec3(0.0, 1.0, 0.0) : vec3(0.0, 0.0, 1.0);
    vec3 halfLen = dir * (longest * 0.5 - 0.5);
    vec3 a = cl_lightAt(centre - halfLen);
    vec3 b = cl_lightAt(centre);
    vec3 c = cl_lightAt(centre + halfLen);
    return min(a, min(b, c));
}

// Voxy's lighting for the quad. Voxy's own block light is never used: the block light of the LODs comes only from
// ColorLight's sources (their colour, reach and propagation method), so the sky light is all that is taken from Voxy.
// The light is worked out once for the whole quad (here, in the vertex shader), not per pixel: that is much cheaper.
vec4 cl_getLighting(uint lighting) {
    vec4 vanilla = getLighting(lighting);
    if (clLod[2] != 1) {
        return vanilla;
    }

    uint skyLevel = lighting & 0xFu;
    vec4 sky = getLighting(skyLevel);

    vec3 cl = cl_light();
    if (max(cl.r, max(cl.g, cl.b)) <= 0.0) {
        return sky;
    }
    return vec4(cl_shade(skyLevel, cl), sky.a);
}

#endif
