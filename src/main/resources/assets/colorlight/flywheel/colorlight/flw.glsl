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
const int CL_OPAQUE_BIT = 1 << 30;
const int CL_TWO_LIGHTS_BIT = 1 << 29;

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

vec3 cl_fetchLight(vec3 rel, vec3 n, ivec3 camBlock) {
    ivec3 camSection = camBlock >> 4;

    if (!cl_smooth()) {
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

        sum += w * cl_decodeCell(raw) * 255.0;
        wsum += w;
    }

    return (wsum > 0.0) ? (sum / (wsum * 255.0)) : vec3(0.0);
}

// Same look as the terrain: this is include/colorlight_light.glsl (cl_lightColor) with Flywheel's lightmap (flw_lightTex).
// Brightness curve (keep the numbers in sync with include/colorlight_light.glsl). Brighter close to the source, dimmer towards the edge.
//   CL_FLW_CURVE_POWER > 1 pushes the weak (far) part down, CL_FLW_CURVE_GAIN lifts the strong (near) part.
//   Equal to the old look at CL_FLW_CURVE_POWER = 1.0 and CL_FLW_CURVE_GAIN = 1.0.
const float CL_FLW_CURVE_POWER = 1.3;
const float CL_FLW_CURVE_GAIN = 1.3;

float cl_shapeLevel(float lvl) {
    return clamp(pow(max(lvl, 0.0), CL_FLW_CURVE_POWER) * CL_FLW_CURVE_GAIN, 0.0, 1.0);
}

vec3 cl_lightColor(vec2 lightUV, vec3 cl, float lvl) {
    // the hue comes from the raw light, the brightness from the shaped one
    float rawLvl = lvl;
    lvl = cl_shapeLevel(lvl);

    float tintStrength = cl_headerFloat(7);
    float tintGamma = cl_headerFloat(8);

    // Sodium encodes light levels 0..15 as (level * 16 + 8) / 256
    float vanillaBlock = clamp((lightUV.x * 256.0 - 8.0) / 240.0, 0.0, 1.0);
    float vanillaSky = clamp((lightUV.y * 256.0 - 8.0) / 240.0, 0.0, 1.0);
    float fullBright = step(0.99, vanillaBlock) * step(0.99, vanillaSky);

    float blockLevel = max(lvl, fullBright);
    vec2 uv = vec2((blockLevel * 240.0 + 8.0) / 256.0, lightUV.y);

    // No volume light here (most of the world): the result below reduces exactly to the plain lightmap lookup
    // (coverage = 0 -> no tint), so skip the second lightmap fetch and the pow/smoothstep maths.
    if (lvl <= 0.0) {
        return clamp(textureLod(flw_lightTex, uv, 0.0).rgb, vec3(0.0), vec3(1.0));
    }

    vec3 full = textureLod(flw_lightTex, uv, 0.0).rgb;
    vec3 skyOnly = textureLod(flw_lightTex, vec2(8.0 / 256.0, uv.y), 0.0).rgb;          // block 0, sky level

    // What the block light adds on top of the sky light, as the lightmap actually shows it (a bright sky saturates
    // the sum, so by day this is small, which is how daylight washes a light out).
    vec3 vis = max(full - skyOnly, vec3(0.0));
    float visPeak = max(vis.r, max(vis.g, vis.b));

    // Day and night: the tint of the sky/ambient part only shows as far as block light is what makes the pixel bright:
    // fully at night, not at all under a bright sky.
    float fullPeak = max(full.r, max(full.g, full.b));
    float skyPeak = max(skyOnly.r, max(skyOnly.g, skyOnly.b));
    float blockShare = (fullPeak > 0.0001) ? clamp(1.0 - skyPeak / fullPeak, 0.0, 1.0) * 10.0 : 1.0;
    float daylight = smoothstep(0.0, 1.0, blockShare);

    // tint only what the volume contributes; a full-bright quad without volume light stays vanilla white
    float coverage = clamp(lvl / max(blockLevel, 0.0001), 0.0, 1.0);
    const float GAMMA_DEFAULT = 0.55;
    float exponent = max(0.0, GAMMA_DEFAULT + (tintGamma - GAMMA_DEFAULT) * 3.0);
    float vivid = 1.0 + max(GAMMA_DEFAULT - tintGamma, 0.0);
    float retention = clamp(1.0 - tintGamma, 0.0, 1.0);
    float shaped = pow(max(lvl, 0.0001), exponent);
    float kept = smoothstep(0.0, 0.06, lvl);
    float strength = clamp(tintStrength * mix(shaped, kept, retention) * coverage, 0.0, 1.0);

    vec3 hue = cl / max(rawLvl, 0.0001); // brightest channel == 1

    // The block light's own contribution is recoloured to the light's hue at EVERY distance, daylight does not touch
    // it: when the tint faded out with daylight towards the edge of a light, what was left there was vanilla's
    // warm-white block light, a pale rim round the coloured light. The brightest channel of the hue is 1, so capping
    // the scalar (not each channel) keeps the hue when vivid pushes the brightness past 1.
    float blockPeak = min(visPeak * vivid, 1.0);
    vec3 blockTinted = strength * (hue * blockPeak) + (1.0 - strength) * vis;

    // The sky/ambient part follows the hue too, so the sum does not run into the 1.0 ceiling (a bluish sky plus an
    // orange light saturated G and B into a white ring). This part does fade with daylight. With no light, by day
    // or on a full-bright quad it is exactly vanilla.
    // Far edge of a light: the block light adds little there, so the sky colour must not be recoloured yet (a blue sky
    // times a yellow hue just goes muddy olive). The recolouring grows in with how much the block light adds.
    float skyShare = smoothstep(0.0, 1.5, visPeak / max(skyPeak, 0.03));
    vec3 skyTinted = skyOnly * (vec3(1.0) + (hue - vec3(1.0)) * (strength * daylight * skyShare));
    return clamp(skyTinted + blockTinted, vec3(0.0), vec3(1.0));
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
