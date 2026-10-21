// ColorLight: sparse light volume that lives on the GPU.
//
// Keep every constant in this file in sync with me.mrhikmen.colorlight.client.core.render.gpu.GpuLightVolume.
//
//   u_CLHeader : 16 ints, see below.
//   u_CLData : one int per section "page": the pool slot of that section, or -1 when the section holds no light.
//              Pages are indexed toroidally: (sx & 63) | ((sz & 63) << 6) | ((sy & 31) << 12).
//   u_CLPool : slots of 4096 cells; texel = slot * 4096 + (y << 8 | z << 4 | x).
//              cell = 0x00BBGGRR (light colour, 0..255 per channel) | CL_OPAQUE_BIT for solid blocks.
//
// Header ints: 0..2 camera block (ints), 3 enabled (1/0), 4..6 camera fraction (float bits),
//              7 tint strength (float bits), 8 tint gamma (float bits), 9 smooth lighting (1/0).

// CL_PLOT: sub-level (Sable) variant. The window is not the camera's: it is a 31x31x31 section window around a per-draw
// anchor in the sub-level's plot space, and its page table is a 32768-page region of u_CLData selected by u_CLPageBase.
#ifdef CL_PLOT
#define CL_SECTION_RADIUS_XZ 15
#define CL_SECTION_RADIUS_Y 15
uniform ivec3 u_CLAnchor;   // anchor block (multiple of 16), plot space
uniform vec3 u_CLCamOff;    // camera position in plot space minus the anchor block
uniform int u_CLPageBase;   // first page of this window in u_CLData
#else
#define CL_SECTION_RADIUS_XZ 31
#define CL_SECTION_RADIUS_Y 15
#endif
const int CL_OPAQUE_BIT = 1 << 24;

// CL_BIND_UNITS (Sodium terrain shaders): the buffers sit on fixed texture units 5..7, so no program uniform is needed.
#ifdef CL_BIND_UNITS
#define CL_BINDING(n) layout(binding = n)
#else
#define CL_BINDING(n)
#endif

CL_BINDING(5) uniform isamplerBuffer u_CLHeader;

// CL_HEADER_VARYINGS (Sodium terrain only): the vertex shader reads the header once per vertex and hands the values to
// the fragment shader as flat varyings, so a pixel no longer does ~10 texel fetches just to learn the camera and the
// settings. The vertex shader declares the matching outputs (colored_terrain.vsh).
#if defined(CL_HEADER_VARYINGS) && defined(CL_FRAGMENT) && !defined(CL_PLOT)
#define CL_USE_HDR_VARYINGS
flat in ivec4 v_CLHdrA; // camera block xyz, flags (bit 0 enabled, bit 1 smooth)
flat in vec3 v_CLFrac;  // camera fraction
flat in vec2 v_CLTint;  // tint strength, tint gamma
#endif

#ifdef CL_PLOT
ivec3 cl_cameraBlock() {
    return u_CLAnchor;
}
#elif defined(CL_USE_HDR_VARYINGS)
ivec3 cl_cameraBlock() {
    return v_CLHdrA.xyz;
}
#else
ivec3 cl_cameraBlock() {
    return ivec3(texelFetch(u_CLHeader, 0).r, texelFetch(u_CLHeader, 1).r, texelFetch(u_CLHeader, 2).r);
}
#endif

#ifdef CL_USE_HDR_VARYINGS
bool cl_enabled() {
    return (v_CLHdrA.w & 1) == 1;
}

bool cl_smooth() {
    return (v_CLHdrA.w & 2) == 2;
}
#else
bool cl_enabled() {
    return texelFetch(u_CLHeader, 3).r == 1;
}

bool cl_smooth() {
    return texelFetch(u_CLHeader, 9).r == 1;
}
#endif

float cl_headerFloat(int index) {
    return intBitsToFloat(texelFetch(u_CLHeader, index).r);
}

#ifdef CL_PLOT
vec3 cl_cameraFrac() {
    return u_CLCamOff;
}
#elif defined(CL_USE_HDR_VARYINGS)
vec3 cl_cameraFrac() {
    return v_CLFrac;
}
#else
vec3 cl_cameraFrac() {
    return vec3(cl_headerFloat(4), cl_headerFloat(5), cl_headerFloat(6));
}
#endif

#ifdef CL_USE_HDR_VARYINGS
float cl_tintStrength() {
    return v_CLTint.x;
}

float cl_tintGamma() {
    return v_CLTint.y;
}
#else
float cl_tintStrength() {
    return cl_headerFloat(7);
}

float cl_tintGamma() {
    return cl_headerFloat(8);
}
#endif

#ifdef CL_FRAGMENT

CL_BINDING(6) uniform isamplerBuffer u_CLData;
CL_BINDING(7) uniform isamplerBuffer u_CLPool;

// Per-fragment cache of the last page-table lookup. The 8 taps of the trilinear filter almost always fall into the
// same 16x16x16 section, so the dependent u_CLData fetch is done once instead of up to 8 times.
ivec3 cl_cacheSection = ivec3(2147483647);
int cl_cacheSlot = -1;

// Raw cell value, or -1 when nothing is known about the cell: its section is outside the window or holds no light,
// so it has no light data and, above all, no solid/air information.
int cl_cellRaw(ivec3 cell, ivec3 camSection) {
    ivec3 section = cell >> 4; // arithmetic shift == floor division by 16, also for negative coordinates

    int slot;
    if (section == cl_cacheSection) {
        slot = cl_cacheSlot;
    } else {
        ivec3 d = abs(section - camSection);
        if (d.x > CL_SECTION_RADIUS_XZ || d.z > CL_SECTION_RADIUS_XZ || d.y > CL_SECTION_RADIUS_Y) {
            slot = -1;
        } else {
#ifdef CL_PLOT
            int page = u_CLPageBase + ((section.x & 31) | ((section.z & 31) << 5) | ((section.y & 31) << 10));
#else
            int page = (section.x & 63) | ((section.z & 63) << 6) | ((section.y & 31) << 12);
#endif
            slot = texelFetch(u_CLData, page).r;
        }
        cl_cacheSection = section;
        cl_cacheSlot = slot;
    }

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
    ivec3 camSection = camBlock >> 4;

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
