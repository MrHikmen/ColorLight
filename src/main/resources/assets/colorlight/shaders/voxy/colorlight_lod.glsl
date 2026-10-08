// ColorLight: coloured light for Voxy's LODs. This text is inserted into Voxy's vertex shader (quads3.vert) by
// me.mrhikmen.colorlight.client.compat.lod.VoxyLightBridge; __CL_BINDING__ is replaced with a free SSBO binding.
//
// Buffer layout (ints), written by VoxyLightBridge, filled from LodLightMap:
//   [0] camera block x   [1] camera block z   [2] enabled (1/0)
//   [3] tint gamma (float bits)   [4] tint strength (float bits)
//   [16 + section * 262144 + ((cx & 511) | (cz & 511) << 9)]: 0x00BBGGRR light colour of the cell, 0 = none.
// Section 0 is LodLightMap's standalone "full" tier (4-block cells, each light source kept separate); sections
// 1..3 are its coarsening levels 0..2 (8/16/32-block cells). Cells farther than 255 cells from the camera (in
// whichever section's own cell size) are not stored.
//
// Which section a quad samples depends only on its real distance to the camera (see cl_lodRaw), not on Voxy's own
// LOD level for that quad:
//   0-700 blocks    -> section 0 (4x4,  each source separate)
//   700-1300 blocks  -> section 1 (8x8)
//   1300-1900 blocks -> section 2 (16x16)
//   1900+ blocks     -> section 3 (32x32, stays 32x32 out to the LOD horizon)
// Keep the constants in sync with LodLightMap / VoxyLightBridge.

#ifdef LIGHTING_SAMPLER_BINDING

layout(binding = __CL_BINDING__, std430) readonly restrict buffer ColorLightLodBuffer {
    int clLod[];
};

ivec3 cl_quadBlock = ivec3(0);

// Called by setupQuad for every vertex: where the quad is, in blocks.
void cl_setQuad(vec3 sectionLocalPos, float lodScale, ivec3 baseSection, uint lodLevel) {
    cl_quadBlock = baseSectionPos * 32 + (baseSection << 5) + ivec3(floor(sectionLocalPos * lodScale));
}

int cl_lodRaw() {
    ivec2 b = cl_quadBlock.xz;
    ivec2 cam = ivec2(clLod[0], clLod[1]);
    float dist = length(vec2(b - cam));

    int section;
    int cell;
    if (dist <= 700.0) {
        section = 0;
        cell = 4;
    } else if (dist <= 1300.0) {
        section = 1;
        cell = 8;
    } else if (dist <= 1900.0) {
        section = 2;
        cell = 16;
    } else {
        section = 3;
        cell = 32;
    }

    ivec2 c    = (b   - (b   & (cell - 1))) / cell; // exact floor division
    ivec2 camC = (cam - (cam & (cell - 1))) / cell;

    ivec2 d = abs(c - camC);
    if (d.x > 255 || d.y > 255) {
        return 0;
    }
    return clLod[16 + section * 262144 + ((c.x & 511) | ((c.y & 511) << 9))];
}

// Voxy's own lighting for the quad, with the block-light part recoloured by the far light map. Same maths as the
// terrain shader, with the quad's vanilla block level standing in for the volume's light level.
vec4 cl_getLighting(uint lighting) {
    vec4 vanilla = getLighting(lighting);
    if (clLod[2] != 1) {
        return vanilla;
    }

    uint blockLevel = (lighting >> 4) & 0xFu;
    uint skyLevel = lighting & 0xFu;
    if (blockLevel == 0u) {
        return vanilla;
    }

    int raw = cl_lodRaw();
    if (raw == 0) {
        return vanilla;
    }

    vec3 cl = vec3(float(raw & 255), float((raw >> 8) & 255), float((raw >> 16) & 255));
    float clPeak = max(cl.r, max(cl.g, cl.b));
    if (clPeak <= 0.0) {
        return vanilla;
    }
    vec3 hue = cl / clPeak;

    float tintGamma = intBitsToFloat(clLod[3]);
    float tintStrength = intBitsToFloat(clLod[4]);
    float lvl = float(blockLevel) / 15.0;

    vec3 full = vanilla.rgb;
    vec3 base = getLighting(0u).rgb;                // block 0, sky 0
    vec3 blockOnly = getLighting(blockLevel << 4).rgb;
    vec3 skyOnly = getLighting(skyLevel).rgb;

    vec3 delta = max(blockOnly - base, vec3(0.0));
    float peak = max(delta.r, max(delta.g, delta.b));

    // day and night: the lightmap follows the time of day, the tint only shows as far as block light makes it bright
    float fullPeak = max(full.r, max(full.g, full.b));
    float skyPeak = max(skyOnly.r, max(skyOnly.g, skyOnly.b));
    float blockShare = (fullPeak > 0.0001) ? clamp(1.0 - skyPeak / fullPeak, 0.0, 1.0) : 1.0;
    // See colored_terrain.fsh: stretched from (0.0, 0.5) so dusk/dawn fade the tint in and out gradually across
    // most of the sky-light fade instead of snapping partway through it, kept in sync with that shader.
    float daylight = smoothstep(0.0, 0.85, blockShare);

    const float GAMMA_DEFAULT = 0.55;
    float exponent = max(0.0, GAMMA_DEFAULT + (tintGamma - GAMMA_DEFAULT) * 3.0);
    float vivid = 1.0 + max(GAMMA_DEFAULT - tintGamma, 0.0);
    float retention = clamp(1.0 - tintGamma, 0.0, 1.0);
    float shaped = pow(max(lvl, 0.0001), exponent);
    float kept = smoothstep(0.0, 0.06, lvl);
    float amount = clamp(tintStrength * mix(shaped, kept, retention) * daylight, 0.0, 1.0);

    vec3 lit = clamp(full + amount * (hue * peak * vivid - delta), vec3(0.0), vec3(1.0));
    return vec4(lit, vanilla.a);
}

#endif
