// ColorLight: coloured light for Voxy's LODs. This text is inserted into Voxy's vertex shader (quads3.vert) by
// me.mrhikmen.colorlight.client.compat.voxy.VoxyLightBridge; __CL_BINDING__ is replaced with a free SSBO binding.
//
// Buffer layout (ints), written by VoxyLightBridge, built by VoxyLightCache (a list of coloured light sources found
// in Voxy's own saved level-0 data; the colour of every LOD block is worked out right here from the sources
// around it - nothing is precomputed per distance or per LOD level):
//   [0] camera block x   [1] camera block z   [2] enabled (1/0)
//   [3] tint gamma (float bits)   [4] tint strength (float bits)   [5] light reach in blocks (1..32)
//   [16 + ((col.x & 255) | (col.y & 255) * 256) * 2 + 0/1]: first source / source count of a 32x32-block column
//   [16 + 131072 + i * 4 + 0..3]: source i = x, y, z, 0x00BBGGRR
// The table is toroidal and valid out to 90 columns (2880 blocks) from where it was filled, with an 8 column margin
// for the camera moving before the next refill. Keep the numbers in sync with VoxyLightCache / VoxyLightBridge.

#ifdef LIGHTING_SAMPLER_BINDING

layout(binding = __CL_BINDING__, std430) readonly restrict buffer ColorLightLodBuffer {
    int clLod[];
};

ivec3 cl_quadBlock = ivec3(0);

// Called by setupQuad for every vertex: where the quad is, in blocks.
void cl_setQuad(vec3 sectionLocalPos, float lodScale, ivec3 baseSection, uint lodLevel) {
    cl_quadBlock = baseSectionPos * 32 + (baseSection << 5) + ivec3(floor(sectionLocalPos * lodScale));
}

// Colour (0..255 per channel, already faded) of the light that reaches this LOD block: the strongest source per
// channel, each fading linearly to nothing over the light reach, by true 3D distance - like the CPU engine does
// near the player. Voxy's own light value for the quad still decides how bright the result is (see cl_getLighting).
vec3 cl_light() {
    vec3 p = vec3(cl_quadBlock) + 0.5;
    ivec2 col = ivec2(cl_quadBlock.x >> 5, cl_quadBlock.z >> 5);
    ivec2 camCol = ivec2(clLod[0] >> 5, clLod[1] >> 5);
    ivec2 away = abs(col - camCol);
    if (away.x > 82 || away.y > 82) {
        return vec3(0.0); // outside the part of the table that is valid (90 columns, minus the 8 column margin)
    }

    float reach = float(max(clLod[5], 1));
    vec3 best = vec3(0.0);
    int budget = 384; // most sources looked at per vertex, so a dense town cannot stall the vertex shader
    for (int dz = -1; dz <= 1; dz++) {
        for (int dx = -1; dx <= 1; dx++) {
            ivec2 c = col + ivec2(dx, dz);
            int cell = 16 + ((c.x & 255) | ((c.y & 255) << 8)) * 2;
            int first = clLod[cell];
            int count = clLod[cell + 1];
            for (int i = 0; i < count && budget > 0; i++, budget--) {
                int s = 16 + 131072 + (first + i) * 4;
                float w = 1.0 - distance(p, vec3(clLod[s], clLod[s + 1], clLod[s + 2]) + 0.5) / reach;
                if (w > 0.0) {
                    int rgb = clLod[s + 3];
                    best = max(best, vec3(float(rgb & 255), float((rgb >> 8) & 255), float((rgb >> 16) & 255)) * w);
                }
            }
        }
    }
    return best;
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

    vec3 cl = cl_light();
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
    float blockShare = (fullPeak > 0.0001) ? clamp(1.0 - skyPeak / fullPeak, 0.0, 1.0) * 10 : 1.0;
    // See colored_terrain.fsh: stretched from (0.0, 0.5) so dusk/dawn fade the tint in and out gradually across
    // most of the sky-light fade instead of snapping partway through it, kept in sync with that shader.
    float daylight = smoothstep(0.0, 1.0, blockShare);

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
