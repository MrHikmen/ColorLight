#version 330
#extension GL_ARB_separate_shader_objects : require

// ColorLight copy of minecraft:core/particle.vsh. The parts marked COLORLIGHT are ours; the rest is
// vanilla and must stay identical to it.

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;
layout(location = 3) in ivec2 UV2;

uniform sampler2D Sampler2;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec2 texCoord0;
layout(location = 3) out vec4 vertexColor;

// COLORLIGHT: the fragment shader lights the particle itself from the light volume (colorlight_entity.glsl)
#ifndef OIT_ALPHA_ONLY
layout(location = 4) out vec4 v_CLBase;
layout(location = 5) out vec3 v_CLLight;
layout(location = 6) out vec2 v_CLLightUV;
layout(location = 7) out vec4 v_CLLightmap;
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

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    texCoord0 = UV0;
    vertexColor = Color * sample_lightmap(Sampler2, UV2);

#ifndef OIT_ALPHA_ONLY
    // COLORLIGHT
    v_CLBase = Color;
    v_CLLight = cl_vertexLight(cl_handPass() ? vec3(0.0) : Position);
    v_CLLightUV = clamp(vec2(UV2) / 256.0 + 0.5 / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0));
    v_CLLightmap = sample_lightmap(Sampler2, UV2);
#endif
}
