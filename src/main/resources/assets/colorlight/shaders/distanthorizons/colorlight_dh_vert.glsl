// ColorLight: vertex-shader part of the far light for Distant Horizons. Inserted into DH's terrain vertex shader by
// DhLightBridge (same buffer and binding as the fragment part). One read per vertex of a coarse flag table says
// whether any light source is within about 32 blocks; the fragment shader only does real work where it is.
//   [16 + 524288 + 131072 * 4 + ((c.x & 255) | (c.y & 255) * 256)]: 1 if a source is in or next to 32-block cell c

layout(binding = __CL_BINDING__, std430) readonly buffer ColorLightDhBuffer {
    int clDh[];
};

float cl_dh_near(vec3 rel) {
    if (clDh[2] != 1) {
        return 0.0;
    }
    vec3 camF = vec3(intBitsToFloat(clDh[7]), intBitsToFloat(clDh[8]), intBitsToFloat(clDh[9]));
    ivec3 p = ivec3(clDh[0], clDh[6], clDh[1]) + ivec3(floor(camF + rel));
    ivec2 c = ivec2(p.x >> 5, p.z >> 5);
    ivec2 away = abs(c - ivec2(clDh[0] >> 5, clDh[1] >> 5));
    if (away.x > 22 || away.y > 22) {
        return 0.0;
    }
    return float(clDh[16 + 524288 + 131072 * 4 + ((c.x & 255) | ((c.y & 255) << 8))]);
}
