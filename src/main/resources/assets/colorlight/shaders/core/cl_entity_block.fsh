#version 330
#extension GL_ARB_separate_shader_objects : require

// ColorLight copy of ponder:entity_block.fsh. The parts marked COLORLIGHT are ours; the rest must stay
// identical to the original.

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>

uniform sampler2D Sampler0;

// COLORLIGHT: per-pixel light from the light volume (colorlight_entity.glsl)
#ifndef OIT_ALPHA_ONLY
uniform sampler2D Sampler2;
#define CL_LIGHT_TEX Sampler2
#define CL_FRAGMENT
#include <colorlight:colorlight_data.glsl>
#include <colorlight:colorlight_light.glsl>
#include <colorlight:colorlight_entity.glsl>
#endif

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexColor;
#ifndef OIT_ALPHA_ONLY
layout(location = 3) in vec4 lightMapColor;
#endif
layout(location = 4) in vec2 texCoord0;

#ifndef OIT_ALPHA_ONLY
layout(location = 5) in vec3 v_CLLight;     // COLORLIGHT
layout(location = 6) in vec2 v_CLLightUV;
#endif

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

#ifndef OIT_ALPHA_ONLY
vec4 lightMul; // COLORLIGHT: the light of this pixel, set in main() (vanilla lightmap or ColorLight's)

vec4 calculateFinalColor(vec4 color) {
    color *= lightMul;

    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
    #else
    vec4 fogColor = FogColor;
    #endif

    return apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
}
#endif

void main() {
    vec4 color = texture(Sampler0, texCoord0);

#ifndef OIT_ALPHA_ONLY
    // COLORLIGHT: taken before the discard below, so the derivatives are in uniform control flow
    lightMul = cl_entityLight(v_CLLight, v_CLLightUV, lightMapColor);
#endif

    #ifdef OIT_ADDITIVE
    color.a = min(0.99, color.a);
    #endif

    #ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
    #endif

    color *= vertexColor * ColorModulator;

    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    fragColor = calculateFinalColor(color);
    #endif
}
