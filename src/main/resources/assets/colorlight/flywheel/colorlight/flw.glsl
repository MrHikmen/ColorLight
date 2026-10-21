// ColorLight x Flywheel: reads ColorLight's GPU light volume from Flywheel's fragment shaders.
//
// Included (through ColorLightFlywheelCompat's source patches) by every Flywheel light shader and by common.frag.
// The volume layout is the one of include/colorlight_data.glsl (keep both in sync): header / page table / cell pool as
// three texel buffers, here on texture units 10..12 (the samplers are bound by ColorLightFlywheelCompat).
// The header's "enabled" flag decides at run time: when it is 0 (no GPU pipeline, ColorLight off) Flywheel's own light
// is used unchanged.

uniform isamplerBuffer u_CLHeader;
uniform isamplerBuffer u_CLData;
uniform isamplerBuffer u_CLPool;

const int CL_SECTION_RADIUS_XZ = 31;
const int CL_SECTION_RADIUS_Y = 15;
const int CL_OPAQUE_BIT = 1 << 24;

// set by cl_flwApply, consumed by the patched common.frag instead of the lightmap lookup
bool _cl_flwActive = false;
vec3 _cl_flwMult = vec3(1.0);

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

// Sub-level (Sable) window: >= 0 selects the page table region of the sub-level volume; set by the apply file.
int _cl_pageBase = -1;
int cl_cellRawPlot(ivec3 cell, ivec3 camSection); // defined by the apply file (a stub without Sable)

// Per-invocation cache of the last world page-table lookup: the 8 taps of the trilinear filter nearly always share a
// section, so the dependent u_CLData fetch happens once instead of up to 8 times.
ivec3 _cl_cacheSection = ivec3(2147483647);
int _cl_cacheSlot = -1;

int cl_cellRaw(ivec3 cell, ivec3 camSection) {
    if (_cl_pageBase >= 0) {
        return cl_cellRawPlot(cell, camSection);
    }
    ivec3 section = cell >> 4;

    int slot;
    if (section == _cl_cacheSection) {
        slot = _cl_cacheSlot;
    } else {
        ivec3 d = abs(section - camSection);
        if (d.x > CL_SECTION_RADIUS_XZ || d.z > CL_SECTION_RADIUS_XZ || d.y > CL_SECTION_RADIUS_Y) {
            slot = -1;
        } else {
            int page = (section.x & 63) | ((section.z & 63) << 6) | ((section.y & 31) << 12);
            slot = texelFetch(u_CLData, page).r;
        }
        _cl_cacheSection = section;
        _cl_cacheSlot = slot;
    }
    if (slot < 0) {
        return -1;
    }

    ivec3 l = cell & 15;
    return texelFetch(u_CLPool, (slot << 12) | (l.y << 8) | (l.z << 4) | l.x).r;
}

vec3 cl_fetchLight(vec3 rel, vec3 n, ivec3 camBlock) {
    ivec3 camSection = camBlock >> 4;

    if (!cl_smooth()) {
        int raw = cl_cellRaw(ivec3(floor(rel + n * 0.5)) + camBlock, camSection);
        if (raw < 0 || (raw & CL_OPAQUE_BIT) != 0) {
            return vec3(0.0);
        }
        return vec3(float(raw & 255), float((raw >> 8) & 255), float((raw >> 16) & 255)) / 255.0;
    }

    vec3 s = rel + n * 0.5 - 0.5;
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

// Same shaping as include/colorlight_light.glsl (cl_lightColor); the lightmap is Flywheel's flw_lightTex.
vec3 cl_lightColor(vec2 lightUV, vec3 cl, float lvl) {
    float tintStrength = cl_headerFloat(7);
    float tintGamma = cl_headerFloat(8);

    float vanillaBlock = clamp((lightUV.x * 256.0 - 8.0) / 240.0, 0.0, 1.0);
    float vanillaSky = clamp((lightUV.y * 256.0 - 8.0) / 240.0, 0.0, 1.0);
    float fullBright = step(0.99, vanillaBlock) * step(0.99, vanillaSky);

    float blockLevel = max(lvl, fullBright);
    vec2 uv = vec2((blockLevel * 240.0 + 8.0) / 256.0, lightUV.y);

    // No volume light here (most of the world): the result below reduces exactly to the plain lightmap lookup
    // (coverage = 0 -> amount = 0), so skip the other three lightmap fetches and the pow/smoothstep maths.
    if (lvl <= 0.0) {
        return clamp(textureLod(flw_lightTex, uv, 0.0).rgb, vec3(0.0), vec3(1.0));
    }

    vec3 full = textureLod(flw_lightTex, uv, 0.0).rgb;
    vec3 base = textureLod(flw_lightTex, vec2(8.0 / 256.0, 8.0 / 256.0), 0.0).rgb;
    vec3 blockOnly = textureLod(flw_lightTex, vec2(uv.x, 8.0 / 256.0), 0.0).rgb;

    vec3 delta = max(blockOnly - base, vec3(0.0));
    float peak = max(delta.r, max(delta.g, delta.b));

    vec3 skyOnly = textureLod(flw_lightTex, vec2(8.0 / 256.0, uv.y), 0.0).rgb;
    float fullPeak = max(full.r, max(full.g, full.b));
    float skyPeak = max(skyOnly.r, max(skyOnly.g, skyOnly.b));
    float blockShare = (fullPeak > 0.0001) ? clamp(1.0 - skyPeak / fullPeak, 0.0, 1.0) * 10.0 : 1.0;
    float daylight = smoothstep(0.0, 1.0, blockShare);

    float coverage = clamp(lvl / max(blockLevel, 0.0001), 0.0, 1.0);
    const float GAMMA_DEFAULT = 0.55;
    float exponent = max(0.0, GAMMA_DEFAULT + (tintGamma - GAMMA_DEFAULT) * 3.0);
    float vivid = 1.0 + max(GAMMA_DEFAULT - tintGamma, 0.0);
    float retention = clamp(1.0 - tintGamma, 0.0, 1.0);
    float shaped = pow(max(lvl, 0.0001), exponent);
    float kept = smoothstep(0.0, 0.06, lvl);
    float amount = clamp(tintStrength * mix(shaped, kept, retention) * coverage * daylight, 0.0, 1.0);

    vec3 hue = (lvl > 0.0) ? (cl / lvl) : vec3(1.0);

    return clamp(full + amount * (hue * peak * vivid - delta), vec3(0.0), vec3(1.0));
}

// rel: position relative to camBlock (the camera block, or the anchor of a sub-level window).
// Must run in uniform control flow (derivatives).
void cl_flwFinish(vec3 rel, ivec3 camBlock) {
    // facing: decided in world space (flw_vertexPos, same space as flw_cameraPos), applied to the volume-space normal;
    // both spaces differ by a rotation, so the derivative cross products keep their handedness
    vec3 wp = flw_vertexPos.xyz;
    vec3 nw = cross(dFdx(wp), dFdy(wp));
    bool flip = dot(nw, wp - flw_cameraPos) > 0.0;

    vec3 n = cross(dFdx(rel), dFdy(rel));
    float nLength = length(n);
    n = (nLength > 1.0e-12) ? (n / nLength) : vec3(0.0, 1.0, 0.0);
    if (flip) {
        n = -n;
    }

    vec3 cl = cl_fetchLight(rel, n, camBlock);
    float lvl = max(cl.r, max(cl.g, cl.b));

    // Block level of the instance itself (15 = full-bright / emissive) and Flywheel's sky light, in Sodium's encoding.
    // Flywheel's own block light is left out on purpose: the volume replaces it.
    vec2 lightUV = vec2(flw_vertexLight.x, flw_fragLight.y) + 8.0 / 256.0;

    _cl_flwMult = cl_lightColor(lightUV, cl, lvl);
    _cl_flwActive = true;
}
