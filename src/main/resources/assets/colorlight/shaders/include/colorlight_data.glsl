// ColorLight: sparse light volume that lives on the GPU.
//
// Keep every constant in this file in sync with me.mrhikmen.colorlight.client.gpu.GpuLightVolume.
//
//   u_CLHeader : 16 ints, see below.
//   u_CLData : one int per section "page": the pool slot of that section, or -1 when the section holds no light.
//              Pages are indexed toroidally: (sx & 63) | ((sz & 63) << 6) | ((sy & 31) << 12).
//   u_CLPool : slots of 4096 cells; texel = slot * 4096 + (y << 8 | z << 4 | x).
//              cell = 0x00BBGGRR (light colour, 0..255 per channel) | CL_OPAQUE_BIT for solid blocks.
//
// Header ints: 0..2 camera block (ints), 3 enabled (1/0), 4..6 camera fraction (float bits),
//              7 tint strength (float bits), 8 tint gamma (float bits), 9 smooth lighting (1/0).

#define CL_SECTION_RADIUS_XZ 31
#define CL_SECTION_RADIUS_Y 15
const int CL_OPAQUE_BIT = 1 << 24;

uniform isamplerBuffer u_CLHeader;

ivec3 cl_cameraBlock() {
    return ivec3(texelFetch(u_CLHeader, 0).r, texelFetch(u_CLHeader, 1).r, texelFetch(u_CLHeader, 2).r);
}

bool cl_enabled() {
    return texelFetch(u_CLHeader, 3).r == 1;
}

bool cl_smooth() {
    return texelFetch(u_CLHeader, 9).r == 1;
}

float cl_headerFloat(int index) {
    return intBitsToFloat(texelFetch(u_CLHeader, index).r);
}

vec3 cl_cameraFrac() {
    return vec3(cl_headerFloat(4), cl_headerFloat(5), cl_headerFloat(6));
}

#ifdef CL_FRAGMENT

uniform isamplerBuffer u_CLData;
uniform isamplerBuffer u_CLPool;

// Raw cell value, or -1 when nothing is known about the cell: its section is outside the window or holds no light,
// so it has no light data and, above all, no solid/air information.
int cl_cellRaw(ivec3 cell, ivec3 camSection) {
    ivec3 section = (cell - (cell & 15)) / 16; // exact floor division, also for negative coordinates
    ivec3 d = section - camSection;
    if (abs(d.x) > CL_SECTION_RADIUS_XZ || abs(d.z) > CL_SECTION_RADIUS_XZ || abs(d.y) > CL_SECTION_RADIUS_Y) {
        return -1;
    }

    int page = (section.x & 63) | ((section.z & 63) << 6) | ((section.y & 31) << 12);
    int slot = texelFetch(u_CLData, page).r;
    if (slot < 0) {
        return -1;
    }

    ivec3 l = cell & 15;
    return texelFetch(u_CLPool, (slot << 12) | (l.y << 8) | (l.z << 4) | l.x).r;
}

// Trilinear light colour (0..1 per channel) in front of a surface.
// rel: position relative to the camera's integer block; n: unit normal facing the camera.
// Solid cells are left out of the average, like the CPU smooth-lighting path does. So are cells of sections that
// hold no light: we can't tell if they are rock or air, and counting them as dark air would put a dark seam on every
// section border (rock next to a lit cave, for example).
vec3 cl_fetchLight(vec3 rel, vec3 n, ivec3 camBlock) {
    ivec3 camSection = (camBlock - (camBlock & 15)) / 16;

    if (!cl_smooth()) {
        // one sample per block face: the cell right in front of it, like vanilla's flat lighting
        int raw = cl_cellRaw(ivec3(floor(rel + n * 0.5)) + camBlock, camSection);
        if (raw < 0 || (raw & CL_OPAQUE_BIT) != 0) {
            return vec3(0.0);
        }
        return vec3(float(raw & 255), float((raw >> 8) & 255), float((raw >> 16) & 255)) / 255.0;
    }

    vec3 s = rel + n * 0.5 - 0.5; // cell centres sit at +0.5
    vec3 fl = floor(s);
    vec3 f = s - fl;
    ivec3 base = ivec3(fl) + camBlock;

    vec3 sum = vec3(0.0);
    float wsum = 0.0;
    for (int i = 0; i < 8; i++) {
        ivec3 o = ivec3(i & 1, (i >> 1) & 1, (i >> 2) & 1);
        vec3 w3 = mix(vec3(1.0) - f, f, vec3(o));
        float w = w3.x * w3.y * w3.z;
        if (w <= 0.0001) {
            continue;
        }

        int raw = cl_cellRaw(base + o, camSection);
        if (raw < 0 || (raw & CL_OPAQUE_BIT) != 0) {
            continue;
        }

        sum += w * vec3(float(raw & 255), float((raw >> 8) & 255), float((raw >> 16) & 255));
        wsum += w;
    }

    return (wsum > 0.0) ? (sum / (wsum * 255.0)) : vec3(0.0);
}

#endif
