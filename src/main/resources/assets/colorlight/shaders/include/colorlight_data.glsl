// ColorLight: sparse light volume that lives on the GPU.
//
// Keep every constant in this file in sync with me.mrhikmen.colorlight.client.core.render.gpu.GpuLightVolume.
//
//   u_CLHeader : 16 ints, see below, then the light palette: int 16 + id = colour of palette id 1..255 (0x00BBGGRR).
//   u_CLData : one int per section "page": the pool slot of that section, or -1 when the section holds no light.
//              Pages are indexed toroidally: (sx & 63) | ((sz & 63) << 6) | ((sy & 31) << 12).
//   u_CLPool : slots of 4096 cells; texel = slot * 4096 + (y << 8 | z << 4 | x).
//              cell = baked colour (bits 0-23) for one light, or CL_TWO_LIGHTS_BIT (29) + I|id1|id2|ratio for two;
//              CL_OPAQUE_BIT (30) for solid blocks; see cl_decodeCell.
//
// Header ints: 0..2 camera block (ints), 3 enabled (1/0), 4..6 camera fraction (float bits),
//              7 tint strength (float bits), 8 tint gamma (float bits), 9 smooth lighting (1/0).

#define CL_SECTION_RADIUS_XZ 31
#define CL_SECTION_RADIUS_Y 15
const int CL_OPAQUE_BIT = 1 << 30;
const int CL_TWO_LIGHTS_BIT = 1 << 29;

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

float cl_tintStrength() {
    return cl_headerFloat(7);
}

float cl_tintGamma() {
    return cl_headerFloat(8);
}

// Colour of palette id 1..255 (brightest channel 1.0); the palette follows the 16 header ints.
vec3 cl_palette(int id) {
    int rgb = texelFetch(u_CLHeader, 16 + id).r;
    return vec3(float(rgb & 255), float((rgb >> 8) & 255), float((rgb >> 16) & 255)) / 255.0;
}

// The light colour (0..1 per channel) a cell word shows, same arithmetic as LightCell.packedToRgb:
//   bit 29 clear: bits 0..23 are the colour itself (0x00BBGGRR). Bit 29 set: two lights, bits 0..7 I, 8..15 id1,
//   16..23 id2, 24..28 ratio (second light = I * ratio / 31). Bit 30 opaque.
// Each light is its palette hue scaled by its brightness; where two meet the brighter channel wins (never a sum).
vec3 cl_decodeCell(int raw) {
    if ((raw & CL_TWO_LIGHTS_BIT) == 0) {
        // one light: the colour is already in the word (bits 0..23 = 0x00BBGGRR), no palette lookup
        return vec3(float(raw & 255), float((raw >> 8) & 255), float((raw >> 16) & 255)) * (1.0 / 255.0);
    }
    float level = float(raw & 255);
    vec3 c = cl_palette((raw >> 8) & 255) * level;
    int ratio = (raw >> 24) & 31;
    c = max(c, cl_palette((raw >> 16) & 255) * (level * float(ratio) / 31.0));
    return c / 255.0;
}

// CL_FRAGMENT: this shader samples the volume (the fragment shaders of Nvidium's, and the vertex shaders since the
// light moved to the vertices, colorlight_vertex.glsl). Without it only the uniforms are declared.
#ifdef CL_FRAGMENT

uniform isamplerBuffer u_CLData;
uniform isamplerBuffer u_CLPool;

// Per-fragment cache of the last page-table lookup. The 8 taps of the trilinear filter almost always fall into the
// same 16x16x16 section, so the dependent u_CLData fetch is done once instead of up to 8 times.
ivec3 cl_cacheSection = ivec3(2147483647);
int cl_cacheSlot = -1;

// Raw cell value, or -1 when nothing is known about the cell: its section is outside the window or holds no light,
// so it has no light data and, above all, no solid/air information.
int cl_cellRaw(ivec3 cell, ivec3 camSection) {
    ivec3 section = (cell - (cell & 15)) / 16; // exact floor division, also for negative coordinates

    int slot;
    if (section == cl_cacheSection) {
        slot = cl_cacheSlot;
    } else {
        ivec3 d = abs(section - camSection);
        if (d.x > CL_SECTION_RADIUS_XZ || d.z > CL_SECTION_RADIUS_XZ || d.y > CL_SECTION_RADIUS_Y) {
            slot = -1;
        } else {
            int page = (section.x & 63) | ((section.z & 63) << 6) | ((section.y & 31) << 12);
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

// One tap of the smooth filter: adds the cell's light with weight w, unless it is solid or its section is unknown.
void cl_tap(ivec3 cell, float w, ivec3 camSection, inout vec3 sum, inout float wsum) {
    if (w <= 0.0001) {
        return;
    }
    int raw = cl_cellRaw(cell, camSection);
    if (raw < 0 || (raw & CL_OPAQUE_BIT) != 0) {
        return;
    }
    sum += w * cl_decodeCell(raw);
    wsum += w;
}

// Smooth light for a face whose normal is a coordinate axis (every full-block face): the surface sits on a cell
// plane, so only the 4 cells of that plane carry weight, a bilinear filter. Same result as the 8-tap loop below,
// which spends the other 4 iterations on zero weights; this is the path nearly every pixel takes.
vec3 cl_fetchLightPlanar(vec3 s, int axis, ivec3 camBlock, ivec3 camSection) {
    vec3 fl = floor(s);
    vec3 f = s - fl;
    ivec3 base = ivec3(fl) + camBlock;
    ivec3 u = (axis == 0) ? ivec3(0, 1, 0) : ivec3(1, 0, 0);
    ivec3 v = (axis == 2) ? ivec3(0, 1, 0) : ivec3(0, 0, 1);
    // along the normal the sample is meant to sit exactly on a cell centre: round, so float noise cannot pick the
    // neighbouring plane
    if (axis == 0) { base.x = int(floor(s.x + 0.5)) + camBlock.x; }
    else if (axis == 1) { base.y = int(floor(s.y + 0.5)) + camBlock.y; }
    else { base.z = int(floor(s.z + 0.5)) + camBlock.z; }
    float fu = dot(f, vec3(u));
    float fv = dot(f, vec3(v));

    vec3 sum = vec3(0.0);
    float wsum = 0.0;
    cl_tap(base, (1.0 - fu) * (1.0 - fv), camSection, sum, wsum);
    cl_tap(base + u, fu * (1.0 - fv), camSection, sum, wsum);
    cl_tap(base + v, (1.0 - fu) * fv, camSection, sum, wsum);
    cl_tap(base + u + v, fu * fv, camSection, sum, wsum);
    return (wsum > 0.0) ? (sum / wsum) : vec3(0.0);
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
        return cl_decodeCell(raw);
    }

    vec3 an = abs(n);
    int axis = (an.x > 0.999) ? 0 : ((an.y > 0.999) ? 1 : ((an.z > 0.999) ? 2 : -1));
    if (axis >= 0) {
        // only a face lying on a block boundary (not a slab top, a carpet...) is on a cell plane
        vec3 sp = rel + n * 0.5 - 0.5;
        float along = (axis == 0) ? sp.x : ((axis == 1) ? sp.y : sp.z);
        float off = abs(along - floor(along + 0.5));
        if (off < 0.002) {
            return cl_fetchLightPlanar(sp, axis, camBlock, camSection);
        }
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

        sum += w * cl_decodeCell(raw) * 255.0;
        wsum += w;
    }

    return (wsum > 0.0) ? (sum / (wsum * 255.0)) : vec3(0.0);
}

// The cell in front of the face only (one read), what the pixel shader uses when smooth lighting is off.
vec3 cl_fetchLightFlat(vec3 rel, vec3 n, ivec3 camBlock) {
    ivec3 camSection = (camBlock - (camBlock & 15)) / 16;
    int raw = cl_cellRaw(ivec3(floor(rel + n * 0.5)) + camBlock, camSection);
    if (raw < 0 || (raw & CL_OPAQUE_BIT) != 0) {
        return vec3(0.0);
    }
    return cl_decodeCell(raw);
}

#endif
