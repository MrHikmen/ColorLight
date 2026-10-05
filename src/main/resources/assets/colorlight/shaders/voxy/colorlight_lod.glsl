// ColorLight: coloured light for Voxy's LODs. This text is inserted into Voxy's vertex shader (quads3.vert) by
// me.mrhikmen.colorlight.client.compat.lod.VoxyLightBridge; __CL_BINDING__ is replaced with a free SSBO binding.
//
// Buffer layout (ints), written by VoxyLightBridge, filled from LodLightMap:
//   [0] camera block x   [1] camera block z   [2] enabled (1/0)
//   [3] tint gamma (float bits)   [4] tint strength (float bits)
//   [16 + level * 16384 + (cx & 127) | (cz & 127) << 7]: 0x00BBGGRR light colour of the cell, 0 = none.
// Level k has cells of (8 << k) blocks; cells farther than 63 from the camera cell are not stored.
// Keep the constants in sync with LodLightMap / VoxyLightBridge.

#ifdef LIGHTING_SAMPLER_BINDING

layout(binding = __CL_BINDING__, std430) readonly restrict buffer ColorLightLodBuffer {
    int clLod[];
};

ivec3 cl_quadBlock = ivec3(0);
uint cl_lodLevel = 0u;

// Called by setupQuad for every vertex: where the quad is, in blocks.
void cl_setQuad(vec3 sectionLocalPos, float lodScale, ivec3 baseSection, uint lodLevel) {
    cl_quadBlock = baseSectionPos * 32 + (baseSection << 5) + ivec3(floor(sectionLocalPos * lodScale));
    cl_lodLevel = lodLevel;
}

int cl_lodRaw() {
    int k = int(min(cl_lodLevel, 4u));
    int cell = 8 << k;

    ivec2 b = cl_quadBlock.xz;
    ivec2 c = (b - (b & (cell - 1))) / cell; // exact floor division
    ivec2 cam = ivec2(clLod[0], clLod[1]);
    ivec2 camC = (cam - (cam & (cell - 1))) / cell;

    ivec2 d = abs(c - camC);
    if (d.x > 63 || d.y > 63) {
        return 0;
    }
    return clLod[16 + k * 16384 + ((c.x & 127) | ((c.y & 127) << 7))];
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
