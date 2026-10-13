// ColorLight: coloured light for Distant Horizons' LOD terrain. This text is inserted into DH's terrain fragment
// shader (assets/distanthorizons/shaders/terrain/gl/frag.frag) by
// me.mrhikmen.colorlight.client.compat.distanthorizons.DhLightBridge; __CL_BINDING__ is replaced with a free SSBO
// binding. A matching pair of varyings (vClSkyLight / vClBlockLight) is added to vert.vert and frag.frag by the
// same class, next to this block's declarations.
//
// Buffer layout (ints), written by DhLightBridge, built by DhLightCache (a list of coloured light sources found in
// Distant Horizons' own saved LOD data via the public DhApi terrain data repo; the colour of every fragment is
// worked out right here from the sources around it - nothing is precomputed per distance or per LOD level):
//   [0] camera block x   [1] camera block z   [2] enabled (1/0)
//   [3] tint gamma (float bits)   [4] tint strength (float bits)   [5] light reach in blocks (1..16)
//   [16 + ((cell.x & 511) | (cell.y & 511) * 512) * 2 + 0/1]: first source / count of a 16x16-block cell
//   [16 + 524288 + i * 4 + 0..3]: source i = x, y, z, 0x00BBGGRR
// The table is toroidal and valid out to 32 columns (1024 blocks) from where it was filled, with an 8 column margin
// for the camera moving before the next refill. Keep the numbers in sync with DhLightCache / DhLightBridge.
//
// vertexWorldPos (a DH varying) is RELATIVE TO THE EXACT CAMERA on all three axes (DH's uModelOffset is the
// section's corner minus the camera). header[0]/[6]/[1] = camera block x/y/z and header[7..9] = camera fraction
// x/y/z (float bits) turn it back into a world position.
//   [6] camera block y   [7..9] camera fraction x, y, z (float bits)

layout(binding = __CL_BINDING__, std430) readonly buffer ColorLightDhBuffer {
    int clDh[];
};

// Same per-channel-max, linear-fade-over-reach sampling the near-field engine and the Voxy compat use: strongest
// source per channel wins, each fading to nothing over the light reach by true 3D distance.
vec3 cl_dh_light(vec3 worldPos) {
    vec3 camF = vec3(intBitsToFloat(clDh[7]), intBitsToFloat(clDh[8]), intBitsToFloat(clDh[9]));
    vec3 t = camF + worldPos;
    ivec3 ti = ivec3(floor(t));
    vec3 f = t - vec3(ti);                          // position inside the block p
    ivec3 p = ivec3(clDh[0], clDh[6], clDh[1]) + ti;

    ivec2 cell = ivec2(p.x >> 4, p.z >> 4);
    ivec2 camCell = ivec2(clDh[0] >> 4, clDh[1] >> 4);
    ivec2 away = abs(cell - camCell);
    if (away.x > 44 || away.y > 44) {
        return vec3(0.0); // outside the part of the table that is valid
    }

    float reach = float(clamp(clDh[5], 1, 16));
    vec3 best = vec3(0.0);
    for (int dz = -1; dz <= 1; dz++) {
        // distance along z from the point to this row of cells; cells further than the reach are skipped unread
        float z0 = float(((cell.y + dz) << 4) - p.z);
        float rz = max(max(z0 - f.z, f.z - (z0 + 16.0)), 0.0);
        if (rz >= reach) {
            continue;
        }
        for (int dx = -1; dx <= 1; dx++) {
            float x0 = float(((cell.x + dx) << 4) - p.x);
            float rx = max(max(x0 - f.x, f.x - (x0 + 16.0)), 0.0);
            if (rx + rz >= reach) {
                continue;
            }
            ivec2 c = cell + ivec2(dx, dz);
            int entry = 16 + ((c.x & 511) | ((c.y & 511) << 9)) * 2;
            int count = min(clDh[entry + 1], 32);
            int first = clDh[entry];
            for (int i = 0; i < count; i++) {
                int s = 16 + 524288 + (first + i) * 4;
                vec3 d = abs(vec3(ivec3(clDh[s], clDh[s + 1], clDh[s + 2]) - p) + 0.5 - f);
                // block-grid shape like Minecraft's own light (a diamond in the open), not a sphere
                float w = 1.0 - (d.x + d.y + d.z) / reach;
                if (w > 0.0) {
                    int rgb = clDh[s + 3];
                    best = max(best, vec3(float(rgb & 255), float((rgb >> 8) & 255), float((rgb >> 16) & 255)) * w);
                }
            }
        }
    }
    return best;
}

// Adds the colour from cl_dh_light on top of the fragment's already-resolved colour. Deliberately does NOT gate
// on DH's own per-vertex block-light level (vClBlockLight/blockLevel01): Distant Horizons' LOD data is generated
// far beyond where real block-light propagation is affordable, so that level is frequently flat/zero out there
// even right next to a lit block - gating on it (the way the Voxy compat gates on Voxy's, which does have real
// block light) would mean the tint almost never shows. All of the falloff instead comes from cl_dh_light's own
// distance fade; skyLevel01 only dims the tint a little in full daylight so sunlit faces don't wash out.
vec3 cl_dh_tint(vec3 resolved, float skyLevel01, float blockLevel01, vec3 worldPos) {
    // the vertex shader found no source anywhere near this triangle (or the feature is off): nothing to read
    if (vClNear <= 0.001) {
        return resolved;
    }

    vec3 cl = cl_dh_light(worldPos);
    float clPeak = max(cl.r, max(cl.g, cl.b));
    if (clPeak <= 0.0) {
        return resolved;
    }

    float tintStrength = intBitsToFloat(clDh[4]);
    float dim = 1.0 - 0.6 * skyLevel01;
    // cl is 0..255 per channel; only a part of the way to a full colour is added, so surfaces keep their texture
    return clamp(resolved + (cl / 255.0) * (0.6 * tintStrength * dim), vec3(0.0), vec3(1.0));
}
