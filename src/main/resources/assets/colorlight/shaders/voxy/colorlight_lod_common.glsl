layout(binding = __CL_BINDING__, std430) readonly restrict buffer ColorLightLodBuffer {
    int clLod[];
};

// Colour (0..255 per channel, already faded) of the light that reaches this LOD block: the strongest source per
// channel, each fading linearly to nothing over the light reach, by true 3D distance - like the CPU engine does
// near the player. Voxy's own light value for the quad still decides how bright the result is (see cl_getLighting).
vec3 cl_lightAt(vec3 pos) {
    ivec3 qb = ivec3(floor(pos));
    vec3 p = vec3(qb) + 0.5;
    ivec2 cell = ivec2(qb.x >> 4, qb.z >> 4);
    ivec2 camCell = ivec2(clLod[0] >> 4, clLod[1] >> 4);
    ivec2 away = abs(cell - camCell);
    if (away.x > 160 || away.y > 160) {
        return vec3(0.0); // outside the part of the table that is valid (180 cells, minus the 16 cell margin)
    }

    float reach = float(clamp(clLod[5], 1, 16)); // the 3x3 cells of 16 blocks around a block cover 16 blocks
    float lossPerBlock = 255.0 / reach;
    vec3 best = vec3(0.0);
    for (int dz = -1; dz <= 1; dz++) {
        // distance along z from the block to this row of cells; rows further than the reach are skipped unread
        float z0 = float(((cell.y + dz) << 4) - qb.z);
        float rz = max(max(z0 - 0.5, 0.5 - (z0 + 16.0)), 0.0);
        if (rz >= reach) {
            continue;
        }
        for (int dx = -1; dx <= 1; dx++) {
            float x0 = float(((cell.x + dx) << 4) - qb.x);
            float rx = max(max(x0 - 0.5, 0.5 - (x0 + 16.0)), 0.0);
            if (max(rx, rz) >= reach) { // a square, not a circle: a method may carry light further along the diagonal
                continue;
            }
            ivec2 c = cell + ivec2(dx, dz);
            int entry = 16 + ((c.x & 511) | ((c.y & 511) << 9)) * 2;
            int first = clLod[entry];
            int count = min(clLod[entry + 1], 96);
            for (int i = 0; i < count; i++) {
                int s = 16 + 524288 + (first + i) * 4;
                int rgb = clLod[s + 3];
                // the block's propagation method decides how far the light has travelled: its distance table is looked
                // up (any method works: the built-in diamond and round ones and those of resource packs)
                int table = (rgb >> 29) & 7;
                ivec3 off = ivec3(round(p - (vec3(clLod[s], clLod[s + 1], clLod[s + 2]) + 0.5)));
                if (abs(off.x) <= 16 && abs(off.y) <= 16 && abs(off.z) <= 16) {
                    int at = 16 + 524288 + 131072 * 4 + table * 35937 + ((off.x + 16) * 33 + (off.y + 16)) * 33 + (off.z + 16);
                    float dist = intBitsToFloat(clLod[at]);
                    // what the near-field engine does: every block of walking distance costs the same number of light
                    // units (255 / reach), whatever the source's colour or strength, so a dim source simply dies sooner
                    // (its strength is already in its colour)
                    float loss = dist * lossPerBlock;
                    vec3 col = vec3(float(rgb & 255), float((rgb >> 8) & 255), float((rgb >> 16) & 255));
                    float top = max(col.r, max(col.g, col.b));
                    float left = top - loss;
                    if (left > 0.0) {
                        vec3 v;
                        if (clLod[7] == 1) {
                            // per-channel fading (Colorful Lighting by erykczy aka thecode, MIT), the hue frozen below two
                            // thirds brightness: the same rule as TablePropagator, so no red tail
                            if (left > 170.0) {
                                v = max(col - loss, vec3(0.0));
                            } else {
                                vec3 atFreeze = max(col - max(top - 170.0, 0.0), vec3(0.0));
                                v = atFreeze * (left / max(max(atFreeze.r, max(atFreeze.g, atFreeze.b)), 1.0));
                            }
                        } else {
                            v = col * (left / top); // the hue stays the same all the way
                        }
                        best = max(best, v);
                    }
                }
            }
        }
    }
    return best;
}

// The colour of a surface with ColorLight's light on it: sky light from the quad (skyLevel) plus the block light cl (0..255
// per channel, from cl_lightAt), shaped like colored_terrain.fsh does for the normal chunks. Returns the finished rgb.
vec3 cl_shade(uint skyLevel, vec3 cl) {
    float clPeak = max(cl.r, max(cl.g, cl.b));
    vec3 hue = cl / max(clPeak, 0.0001);

    float tintGamma = intBitsToFloat(clLod[3]);
    float tintStrength = intBitsToFloat(clLod[4]);

    vec3 base = cl_getLm(0u).rgb;                // block 0, sky 0
    vec3 skyOnly = cl_getLm(skyLevel).rgb;

    // the block level is ColorLight's own (fractional, 0..15), looked up between two whole levels
    float lv = clamp(clPeak / 255.0, 0.0, 1.0) * 15.0;
    uint lo = uint(floor(lv));
    uint hi = min(lo + 1u, 15u);
    float t = fract(lv);
    float lvl = lv / 15.0;
    vec3 full = mix(cl_getLm((lo << 4) | skyLevel).rgb, cl_getLm((hi << 4) | skyLevel).rgb, t);
    vec3 blockOnly = mix(cl_getLm(lo << 4).rgb, cl_getLm(hi << 4).rgb, t);

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
    // a very faint colour (the far edge of a light's reach) only tints a little, instead of a full hue from nothing
    float amount = clamp(tintStrength * mix(shaped, kept, retention) * daylight * smoothstep(0.0, 24.0, clPeak), 0.0, 1.0);

    vec3 lit = clamp(full + amount * (hue * peak * vivid - delta), vec3(0.0), vec3(1.0));
    // No pale rim at the edge of the light (same cap as in include/colorlight_light.glsl, kept in sync with it): a channel
    // may not rise above "sky-only + this light's own share of that channel", otherwise vanilla's warm-white block
    // light plus a coloured light that doesn't carry some channel leaves a white fringe round the light.
    vec3 cap = skyOnly + hue * (peak * vivid * tintStrength) + delta * (1.0 - tintStrength);
    return min(lit, cap);
}

