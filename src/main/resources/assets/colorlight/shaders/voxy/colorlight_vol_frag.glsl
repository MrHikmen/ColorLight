// ColorLight: coloured light for Voxy's LODs, volume mode (fragment part, two pieces). The first is inserted before
// uint2vec4RGBA in Voxy's fragment shader (quads.frag), the second before computeColour; __CL_BINDING__ is replaced with
// the same SSBO binding the vertex part uses.
//
// Buffer layout (ints), written by VoxyLightBridge from LodLightVolume (keep the numbers in sync with it):
//   [0 .. 16)                     header: [2] enabled (1/0)  [3] tint gamma (float bits)  [4] tint strength (float bits)
//   [16 + h * 4 + 0..3]           hash entry h: tileX, (tileY & 0xFFFFFF) | level << 24, tileZ, slot (-1 empty, -2 deleted); 262144 entries
//   [524304 + slot * 512 + (x&7)*64 + (y&7)*8 + (z&7)]   the colour of a cell, 0x00BBGGRR (0 = no light)
// A tile is 8x8x8 cells; a cell is 1, 2, 4 or 8 blocks wide on level 0..3 (tile coordinates = block coordinates >> (3 + level)).
// The colour is the light (0..255 per channel, already faded) that reaches the cell.

layout(binding = __CL_BINDING__, std430) readonly restrict buffer ColorLightLodBuffer {
    int clLod[];
};

// the lightmap: Voxy binds it at 1 on OpenGL and at 9 on Vulkan (VOXY_VULKAN is defined by the Vulkan shader compiler)
#ifdef VOXY_VULKAN
layout(binding = 9) uniform sampler2D lightSampler;
#else
layout(binding = 1) uniform sampler2D lightSampler;
#endif

layout(location = 2) in vec3 clRel;
layout(location = 3) in flat vec3 clFlat;

vec4 cl_getLm(uint i) {
    return textureLod(lightSampler, getLightmapUv(i), 0);
}

const int CL_HASH_OFFSET = 16;
const int CL_HASH_MASK = 262143;
const int CL_POOL_OFFSET = 1048592;

// same function as LodLightVolume.hash
uint cl_hash(int tx, int b, int tz) {
    uint h = uint(tx) * 0x8DA6B343u ^ uint(b) * 0xD8163841u ^ uint(tz) * 0xCB1AB31Fu;
    h ^= h >> 15;
    h *= 0x2C1B3C6Du;
    h ^= h >> 12;
    return h;
}

// The light colour (0..255 per channel) of the cell that holds the block at world position w, on one level of detail.
vec3 cl_voxelAt(ivec3 w, int lvl) {
    ivec3 t = w >> (3 + lvl);
    int b = (t.y & 0xFFFFFF) | (lvl << 24);
    uint h = cl_hash(t.x, b, t.z) & uint(CL_HASH_MASK);
    for (int i = 0; i < 16; i++) {
        int e = CL_HASH_OFFSET + int(h) * 4;
        int slot = clLod[e + 3];
        if (slot == -1) {
            return vec3(0.0);
        }
        if (slot >= 0 && clLod[e] == t.x && clLod[e + 1] == b && clLod[e + 2] == t.z) {
            ivec3 l = (w >> lvl) & 7;
            int v = clLod[CL_POOL_OFFSET + slot * 512 + ((l.x << 6) | (l.y << 3) | l.z)];
            return vec3(float(v & 255), float((v >> 8) & 255), float((v >> 16) & 255));
        }
        h = (h + 1u) & uint(CL_HASH_MASK);
    }
    return vec3(0.0);
}

// The light of the block at w for a quad of level of detail g: the cell of the same level, or, where that level has no tile
// (its share of the pool is full, or it is out of its range), the next coarser one. Levels above 3 use level 3.
vec3 cl_voxel(ivec3 w, int g) {
    int lvl = min(g, 3);
    vec3 c = cl_voxelAt(w, lvl);
    if (lvl < 3 && max(c.r, max(c.g, c.b)) <= 0.0) {
        c = cl_voxelAt(w, lvl + 1);
    }
    return c;
}

// The colour of a surface with ColorLight's light on it: sky light from the quad (skyLevel) plus the block light cl (0..255
// per channel, from cl_lightAt), shaped like colored_terrain.fsh does for the normal chunks. Returns the finished rgb.
// Same brightness curve as CL_CURVE_POWER / CL_CURVE_GAIN in include/colorlight_light.glsl (keep the numbers in sync):
// brighter close to the source, dimmer towards the edge of the reach.
const float CL_LOD_CURVE_POWER = 1.3;
const float CL_LOD_CURVE_GAIN = 1.3;

vec3 cl_shade(uint skyLevel, vec3 cl) {
    float clPeak = max(cl.r, max(cl.g, cl.b));
    vec3 hue = cl / max(clPeak, 0.0001);

    float tintGamma = intBitsToFloat(clLod[3]);
    float tintStrength = intBitsToFloat(clLod[4]);

    vec3 skyOnly = cl_getLm(skyLevel).rgb;

    // the block level is ColorLight's own (fractional, 0..15), looked up between two whole levels
    float lv = clamp(pow(clamp(clPeak / 255.0, 0.0, 1.0), CL_LOD_CURVE_POWER) * CL_LOD_CURVE_GAIN, 0.0, 1.0) * 15.0;
    uint lo = uint(floor(lv));
    uint hi = min(lo + 1u, 15u);
    float t = fract(lv);
    float lvl = lv / 15.0;
    vec3 full = mix(cl_getLm((lo << 4) | skyLevel).rgb, cl_getLm((hi << 4) | skyLevel).rgb, t);

    // Same shaping as include/colorlight_light.glsl (kept in sync with it, see the comments there).
    vec3 vis = max(full - skyOnly, vec3(0.0));
    float visPeak = max(vis.r, max(vis.g, vis.b));

    float fullPeak = max(full.r, max(full.g, full.b));
    float skyPeak = max(skyOnly.r, max(skyOnly.g, skyOnly.b));
    float blockShare = (fullPeak > 0.0001) ? clamp(1.0 - skyPeak / fullPeak, 0.0, 1.0) * 10.0 : 1.0;
    float daylight = smoothstep(0.0, 1.0, blockShare);

    const float GAMMA_DEFAULT = 0.55;
    float exponent = max(0.0, GAMMA_DEFAULT + (tintGamma - GAMMA_DEFAULT) * 3.0);
    float vivid = 1.0 + max(GAMMA_DEFAULT - tintGamma, 0.0);
    float retention = clamp(1.0 - tintGamma, 0.0, 1.0);
    float shaped = pow(max(lvl, 0.0001), exponent);
    float kept = smoothstep(0.0, 0.06, lvl);
    // a very faint colour (the far edge of a light's reach) only tints a little, instead of a full hue from nothing
    float strength = clamp(tintStrength * mix(shaped, kept, retention) * smoothstep(0.0, 24.0, clPeak), 0.0, 1.0);

    float blockPeak = min(visPeak * vivid, 1.0);
    vec3 blockTinted = strength * (hue * blockPeak) + (1.0 - strength) * vis;
    // Far edge of a light: the block light adds little there, so the sky colour must not be recoloured yet (a blue sky
    // times a yellow hue just goes muddy olive). The recolouring grows in with how much the block light adds.
    float skyShare = smoothstep(0.0, 1.5, visPeak / max(skyPeak, 0.03));
    vec3 skyTinted = skyOnly * (vec3(1.0) + (hue - vec3(1.0)) * (strength * daylight * skyShare));
    return clamp(skyTinted + blockTinted, vec3(0.0), vec3(1.0));
}

//@PART2@
// The tint of the pixel: Voxy's quad tint (sky light times the face's brightness) with ColorLight's block light added
// where the volume has some. The light is looked up in the block in front of the face, like the near-field renderer.
vec4 cl_tint() {
    vec4 base = uint2vec4RGBA(interData.y);
    if (clLod[2] != 1) {
        return base;
    }
    uint face = (interData.x >> 4) & 7u; // bit 0 = positive side; axis 0 = y, 1 = z, 2 = x
    float side = ((face & 1u) == 1u) ? 0.5 : -0.5;
    uint axis = face >> 1;
    vec3 p = clRel + ((axis == 0u) ? vec3(0.0, side, 0.0) : (axis == 1u) ? vec3(0.0, 0.0, side) : vec3(side, 0.0, 0.0));
    ivec3 w = baseSectionPos * 32 + ivec3(floor(p));
    vec3 cl = cl_voxel(w, int(clFlat.z + 0.5));
    if (max(cl.r, max(cl.g, cl.b)) <= 0.0) {
        return base;
    }
    return vec4(cl_shade(uint(clFlat.x + 0.5), cl) * clFlat.y, base.a);
}

