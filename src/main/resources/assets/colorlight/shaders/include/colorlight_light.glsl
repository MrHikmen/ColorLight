// ColorLight: the lighting maths shared by every terrain shader that reads the GPU light volume
// (Sodium's colored_terrain.fsh and Nvidium's frag.frag).
//
// Include colorlight_data.glsl first (with CL_FRAGMENT defined), and #define CL_LIGHT_TEX as the name of the lightmap
// sampler2D of the including shader before including this file.

// COLORLIGHT: the vanilla lightmap look, but block light comes only from the light volume.
//  - vanilla's own block-light level (vertex data) is ignored, so the stock light never shows up
//  - exception: full-bright quads (emissive models) are lit by vanilla at 15/15; they keep that
//  - the block level is the smooth (trilinear) level from the volume, not vanilla's stepped 0..15
//  - sky light is left alone, so daylight washes the tint out the way it should
vec3 cl_lightColor(vec2 lightUV, vec3 cl, float lvl) {
    float tintStrength = cl_tintStrength();
    float tintGamma = cl_tintGamma();

    // Sodium encodes light levels 0..15 as (level * 16 + 8) / 256
    float vanillaBlock = clamp((lightUV.x * 256.0 - 8.0) / 240.0, 0.0, 1.0);
    float vanillaSky = clamp((lightUV.y * 256.0 - 8.0) / 240.0, 0.0, 1.0);
    float fullBright = step(0.99, vanillaBlock) * step(0.99, vanillaSky);

    float blockLevel = max(lvl, fullBright);
    vec2 uv = vec2((blockLevel * 240.0 + 8.0) / 256.0, lightUV.y);

    // No volume light here (most of the world): the result below reduces exactly to the plain lightmap lookup
    // (coverage = 0 -> no tint), so skip the second lightmap fetch and the pow/smoothstep maths.
    if (lvl <= 0.0) {
        return clamp(textureLod(CL_LIGHT_TEX, uv, 0.0).rgb, vec3(0.0), vec3(1.0));
    }

    vec3 full = textureLod(CL_LIGHT_TEX, uv, 0.0).rgb;
    vec3 skyOnly = textureLod(CL_LIGHT_TEX, vec2(8.0 / 256.0, uv.y), 0.0).rgb;          // block 0, sky level

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

    vec3 hue = cl / lvl; // brightest channel == 1

    // The block light's own contribution is recoloured to the light's hue at EVERY distance, daylight does not touch
    // it: when the tint faded out with daylight towards the edge of a light, what was left there was vanilla's
    // warm-white block light, a pale rim round the coloured light. The brightest channel of the hue is 1, so capping
    // the scalar (not each channel) keeps the hue when vivid pushes the brightness past 1.
    float blockPeak = min(visPeak * vivid, 1.0);
    vec3 blockTinted = strength * (hue * blockPeak) + (1.0 - strength) * vis;

    // The sky/ambient part follows the hue too, so the sum does not run into the 1.0 ceiling (a bluish sky plus an
    // orange light saturated G and B into a white ring). This part does fade with daylight. With no light, by day
    // or on a full-bright quad it is exactly vanilla.
    vec3 skyTinted = skyOnly * (vec3(1.0) + (hue - vec3(1.0)) * (strength * daylight));
    return clamp(skyTinted + blockTinted, vec3(0.0), vec3(1.0));
}

// Light multiplier (rgb) of a pixel from its position alone: finds the surface normal from screen-space derivatives
// (flipped to face the camera), reads the volume and shapes the result like cl_lightColor.
// rel: position relative to the camera's integer block; lightUV: Sodium's lightmap coordinate (block, sky).
// Must be called from uniform control flow (it uses dFdx / dFdy).
vec3 cl_lightAt(vec3 rel, vec2 lightUV) {
    vec3 n = cross(dFdx(rel), dFdy(rel));
    float nLength = length(n);
    n = (nLength > 1.0e-12) ? (n / nLength) : vec3(0.0, 1.0, 0.0);
    if (dot(n, rel - cl_cameraFrac()) > 0.0) {
        n = -n;
    }

    vec3 cl = cl_fetchLight(rel, n, cl_cameraBlock());
    float lvl = max(cl.r, max(cl.g, cl.b));
    return cl_lightColor(lightUV, cl, lvl);
}
