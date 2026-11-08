#version 330
#extension GL_ARB_separate_shader_objects : require

// ColorLight copy of minecraft:core/item.vsh. The parts marked COLORLIGHT are ours; the rest is
// vanilla and must stay identical to it.

#include <minecraft:light.glsl>
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV1;
layout(location = 4) in ivec2 UV2;
#ifdef GLINT_SPECIAL
layout(location = 5) in vec2 UV3;
#endif
layout(location = 6) in vec3 Normal;

#ifndef OIT_ALPHA_ONLY
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
#endif
layout(location = 2) out vec4 vertexColor;
#ifndef OIT_ALPHA_ONLY
layout(location = 3) out vec4 lightMapColor;
layout(location = 4) out vec4 overlayColor;
#endif

layout(location = 5) out vec2 texCoord0;
#ifdef GLINT
layout(location = 6) out vec2 texCoordGlint;
#endif

// COLORLIGHT: the fragment shader lights the pixel itself from the light volume (colorlight_entity.glsl)
#ifndef OIT_ALPHA_ONLY
layout(location = 7) out vec3 v_CLLight;
layout(location = 8) out vec2 v_CLLightUV;
#endif

// COLORLIGHT: the light of this vertex (colorlight_vertex.glsl); the fragment shader interpolates it
#define CL_FRAGMENT
#include <colorlight:colorlight_data.glsl>
#include <colorlight:colorlight_vertex.glsl>

// COLORLIGHT: 1 while the first-person hand is drawn (GameRendererMixin). The hand's vertices are in view
// space, not in the world, so they get the light of the camera's own position instead.
uniform isamplerBuffer u_CLPass;
bool cl_handPass() {
    return texelFetch(u_CLPass, 0).r == 1;
}

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    #ifndef OIT_ALPHA_ONLY
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    #endif
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color);
    #ifndef OIT_ALPHA_ONLY
    lightMapColor = sample_lightmap(Sampler2, UV2);
    overlayColor = texelFetch(Sampler1, UV1, 0);
    #endif

    #ifndef OIT_ALPHA_ONLY
    // COLORLIGHT
    v_CLLight = cl_vertexLight(cl_handPass() ? vec3(0.0) : Position);
    v_CLLightUV = clamp(vec2(UV2) / 256.0 + 0.5 / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0));
    #endif

    texCoord0 = UV0;
    #ifdef GLINT
    #ifdef GLINT_SPECIAL
    texCoordGlint = (TextureMat * vec4(UV3, 0.0, 1.0)).xy;
    #else
    texCoordGlint = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
    #endif
    #endif
}
