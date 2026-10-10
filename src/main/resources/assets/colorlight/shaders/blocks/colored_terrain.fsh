#version 460 core

// ColorLight copy of sodium:blocks/block_layer_opaque.fsh.
// Everything marked COLORLIGHT is ours; the rest must stay identical to Sodium's shader.

#include <sodium:globals.glsl>
#include <sodium:fog.glsl>
#include <sodium:chunk_material.glsl>
#include <minecraft:oit.glsl>

layout(location = 0) in vec4 v_Color; // The interpolated vertex color
layout(location = 1) in vec2 v_TexCoord; // The interpolated block texture coordinates
layout(location = 2) in vec2 v_FragDistance; // The fragment's distance from the camera (cylindrical and spherical)
layout(location = 3) in float fadeFactor;

// COLORLIGHT: per-pixel coloured block light
#ifndef OIT_ALPHA_ONLY
layout(location = 4) in vec4 v_ColorBase; // vertex colour without the lightmap
layout(location = 5) in vec2 v_LightUV;   // Sodium's lightmap coordinate (block, sky)
layout(location = 6) in vec3 v_CLRel;     // position relative to the camera's integer block

uniform sampler2D u_LightTex;

#define CL_FRAGMENT
#include <colorlight:colorlight_data.glsl>
#endif

uniform sampler2D u_BlockTex; // The block texture

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor; // The output fragment for the color framebuffer
#endif

vec4 calculateFinalColor(vec4 color) {
    #ifdef OIT_ACCUMULATE
        color = sampleColorForAccumulation(color);
        vec4 fogColor = vec4(u_FogColor.rgb * color.a, u_FogColor.a);
    #else
        vec4 fogColor = u_FogColor;
    #endif

    #ifdef OIT_ALPHA_ONLY
    float factor = 1.0;
    #else
    float factor = fadeFactor;
    #endif

    return _linearFog(color, v_FragDistance, fogColor, u_EnvironmentFog, u_RenderFog, factor);
}
vec4 sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize, vec2 du, vec2 dv, vec2 texelScreenSize) {
    // Convert our UV back up to texel coordinates and find out how far over we are from the center of each pixel
    vec2 uvTexelCoords = uv / pixelSize;
    vec2 texelCenter = round(uvTexelCoords) - 0.5f;
    vec2 texelOffset = uvTexelCoords - texelCenter;

    // Move our offset closer to the texel center based on texel size on screen
    texelOffset = (texelOffset - 0.5f) * pixelSize / texelScreenSize + 0.5f;
    texelOffset = clamp(texelOffset, 0.0f, 1.0f);

    uv = (texelCenter + texelOffset) * pixelSize;
    return textureGrad(source, uv, du, dv);
}

vec4 sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);
    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    return sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);
}

// Rotated Grid Super-Sampling
vec4 sampleRGSS(sampler2D source, vec2 uv, vec2 pixelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);

    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    float maxTexelSize = max(texelScreenSize.x, texelScreenSize.y);

    float minPixelSize = min(pixelSize.x, pixelSize.y);

    float transitionStart = minPixelSize * 1.0;
    float transitionEnd = minPixelSize * 2.0;
    float blendFactor = smoothstep(transitionStart, transitionEnd, maxTexelSize);

    float duLength = length(du);
    float dvLength = length(dv);
    float minDerivative = min(duLength, dvLength);
    float maxDerivative = max(duLength, dvLength);

    float effectiveDerivative = sqrt(minDerivative * maxDerivative);

    float mipLevelExact = max(0.0, log2(effectiveDerivative / minPixelSize));

    const vec2 offsets[4] = vec2[](
    vec2(0.125, 0.375),
    vec2(-0.125, -0.375),
    vec2(0.375, -0.125),
    vec2(-0.375, 0.125)
    );

    vec4 rgssColor = vec4(0.0);
    for (int i = 0; i < 4; ++i) {
        vec2 sampleUV = uv + offsets[i] * pixelSize;
        rgssColor += textureLod(source, sampleUV, mipLevelExact);
    }
    rgssColor *= 0.25;

    vec4 nearestColor = sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);

    return mix(nearestColor, rgssColor, blendFactor);
}

#ifndef OIT_ALPHA_ONLY
// COLORLIGHT: the vanilla lightmap look, but block light comes only from the light volume.
//  - vanilla's own block-light level (vertex data) is ignored, so the stock light never shows up
//  - exception: full-bright quads (emissive models) are lit by vanilla at 15/15; they keep that
//  - the block level is the smooth (trilinear) level from the volume, not vanilla's stepped 0..15
//  - sky light is left alone, so daylight washes the tint out the way it should
vec3 cl_lightColor(vec2 lightUV, vec3 cl, float lvl) {
    float tintStrength = cl_headerFloat(7);
    float tintGamma = cl_headerFloat(8);

    // Sodium encodes light levels 0..15 as (level * 16 + 8) / 256
    float vanillaBlock = clamp((lightUV.x * 256.0 - 8.0) / 240.0, 0.0, 1.0);
    float vanillaSky = clamp((lightUV.y * 256.0 - 8.0) / 240.0, 0.0, 1.0);
    float fullBright = step(0.99, vanillaBlock) * step(0.99, vanillaSky);

    float blockLevel = max(lvl, fullBright);
    vec2 uv = vec2((blockLevel * 240.0 + 8.0) / 256.0, lightUV.y);

    vec3 full = textureLod(u_LightTex, uv, 0.0).rgb;
    vec3 base = textureLod(u_LightTex, vec2(8.0 / 256.0, 8.0 / 256.0), 0.0).rgb;      // block 0, sky 0
    vec3 blockOnly = textureLod(u_LightTex, vec2(uv.x, 8.0 / 256.0), 0.0).rgb;        // block level, sky 0

    // What the block light adds on top of "no block light". It is exactly zero when the level is zero,
    // so everything below fades out smoothly at the edge of the light, whatever the tint settings are.
    vec3 delta = max(blockOnly - base, vec3(0.0));
    float peak = max(delta.r, max(delta.g, delta.b));

    // Day and night: the lightmap texture follows the time of day (and weather, night vision, dimension), so the
    // sky-only colour below is bright by day and dark by night. The tint only shows as far as block light is what
    // makes the pixel bright: fully at night, not at all under a bright sky.
    vec3 skyOnly = textureLod(u_LightTex, vec2(8.0 / 256.0, uv.y), 0.0).rgb;
    float fullPeak = max(full.r, max(full.g, full.b));
    float skyPeak = max(skyOnly.r, max(skyOnly.g, skyOnly.b));
    float blockShare = (fullPeak > 0.0001) ? clamp(1.0 - skyPeak / fullPeak, 0.0, 1.0) * 10 : 1.0;
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

// Replaces Sodium's per-vertex "vertex colour * lightmap" (v_Color) for every pixel while the pipeline is enabled.
vec4 cl_resolveVertexColor() {
    if (!cl_enabled()) {
        return v_Color;
    }

    // face normal from screen-space derivatives, flipped to face the camera (the camera sits at cl_cameraFrac())
    vec3 n = cross(dFdx(v_CLRel), dFdy(v_CLRel));
    float nLength = length(n);
    n = (nLength > 1.0e-12) ? (n / nLength) : vec3(0.0, 1.0, 0.0);
    if (dot(n, v_CLRel - cl_cameraFrac()) > 0.0) {
        n = -n;
    }

    vec3 cl = cl_fetchLight(v_CLRel, n, cl_cameraBlock());
    float lvl = max(cl.r, max(cl.g, cl.b));

    return v_ColorBase * vec4(cl_lightColor(v_LightUV, cl, lvl), 1.0);
}
#endif

void main() {
    vec4 color = u_UseRGSS ? sampleRGSS(u_BlockTex, v_TexCoord, u_TexelSize) : sampleNearest(u_BlockTex, v_TexCoord, u_TexelSize);
#ifdef OIT_ALPHA_ONLY
    color *= v_Color; // Apply per-vertex color modulator
#else
    color *= cl_resolveVertexColor(); // COLORLIGHT: per-pixel light instead of the per-vertex one
#endif

#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif

    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    fragColor = calculateFinalColor(color);
    #endif
}
