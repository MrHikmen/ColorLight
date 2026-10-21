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
    // (coverage = 0 -> amount = 0), so skip the other three lightmap fetches and the pow/smoothstep maths.
    if (lvl <= 0.0) {
        return clamp(textureLod(CL_LIGHT_TEX, uv, 0.0).rgb, vec3(0.0), vec3(1.0));
    }

    vec3 full = textureLod(CL_LIGHT_TEX, uv, 0.0).rgb;
    vec3 base = textureLod(CL_LIGHT_TEX, vec2(8.0 / 256.0, 8.0 / 256.0), 0.0).rgb;      // block 0, sky 0
    vec3 blockOnly = textureLod(CL_LIGHT_TEX, vec2(uv.x, 8.0 / 256.0), 0.0).rgb;        // block level, sky 0

    // What the block light adds on top of "no block light". It is exactly zero when the level is zero,
    // so everything below fades out smoothly at the edge of the light, whatever the tint settings are.
    vec3 delta = max(blockOnly - base, vec3(0.0));
    float peak = max(delta.r, max(delta.g, delta.b));

    // Day and night: the lightmap texture follows the time of day (and weather, night vision, dimension), so the
    // sky-only colour below is bright by day and dark by night. The tint only shows as far as block light is what
    // makes the pixel bright: fully at night, not at all under a bright sky.
    vec3 skyOnly = textureLod(CL_LIGHT_TEX, vec2(8.0 / 256.0, uv.y), 0.0).rgb;
    float fullPeak = max(full.r, max(full.g, full.b));
    float skyPeak = max(skyOnly.r, max(skyOnly.g, skyOnly.b));
    float blockShare = (fullPeak > 0.0001) ? clamp(1.0 - skyPeak / fullPeak, 0.0, 1.0) * 10.0 : 1.0;
    // Stretched from (0.0, 0.5): under full open sky blockShare is still exactly 0, so the tint is still fully
    // gone at high noon, but the ramp up to full tint now spans almost the whole blockShare range instead of
    // just its lower half. Dusk and dawn move through this range gradually (vanilla's own sky-light fade is not
    // instant either), so the tint now fades in and out over most of that fade instead of snapping on/off
    // partway through it, and it starts appearing/lingering earlier/later at the dim end of the sky fade.
    float daylight = smoothstep(0.0, 1.0, blockShare);

    // tint only what the volume contributes; a full-bright quad without volume light stays vanilla white
    float coverage = clamp(lvl / max(blockLevel, 0.0001), 0.0, 1.0);
    // TINT_GAMMA, stretched so the slider matters: 0.55 (the default) behaves as it always did, lower values
    // colour even faint light fully and make the colour brighter, higher values keep the tint for strong light only
    const float GAMMA_DEFAULT = 0.55;
    float exponent = max(0.0, GAMMA_DEFAULT + (tintGamma - GAMMA_DEFAULT) * 3.0);
    float vivid = 1.0 + max(GAMMA_DEFAULT - tintGamma, 0.0);
    // The same setting also decides how far the colour carries: the lower it is, the less the tint depends on
    // distance. At 0 the hue stays fully saturated to the edge of the light and only the brightness falls off (the
    // tint then only fades over the very last sliver, so there is no seam); from 100% up it fades with the light.
    float retention = clamp(1.0 - tintGamma, 0.0, 1.0);
    float shaped = pow(max(lvl, 0.0001), exponent);
    float kept = smoothstep(0.0, 0.06, lvl);
    float amount = clamp(tintStrength * mix(shaped, kept, retention) * coverage * daylight, 0.0, 1.0);

    vec3 hue = (lvl > 0.0) ? (cl / lvl) : vec3(1.0); // brightest channel == 1

    // vanilla result, with the light's added part recoloured to the light's hue at the same peak brightness
    return clamp(full + amount * (hue * peak * vivid - delta), vec3(0.0), vec3(1.0));
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
