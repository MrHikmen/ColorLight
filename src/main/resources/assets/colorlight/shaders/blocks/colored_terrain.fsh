#version 330 core
#extension GL_ARB_shading_language_420pack : enable
#define CL_BIND_UNITS

// ColorLight copy of sodium:blocks/block_layer_opaque.fsh (Sodium 0.8.13 / Minecraft 1.21.1, plain OpenGL).
// Everything marked COLORLIGHT is ours; the rest must stay identical to Sodium's shader.

#ifndef MAX_TEXTURE_LOD_BIAS
#error "MAX_TEXTURE_LOD_BIAS constant not specified"
#endif

#import <sodium:include/fog.glsl>
#import <sodium:include/chunk_material.glsl>

in vec4 v_Color; // The interpolated vertex color
in vec2 v_TexCoord; // The interpolated block texture coordinates
in float v_FragDistance; // The fragment's distance from the camera

flat in uint v_Material;

// COLORLIGHT
in vec4 v_ColorBase; // vertex colour without the lightmap
in vec2 v_LightUV;   // Sodium's lightmap coordinate (block, sky)
in vec3 v_CLRel;     // position relative to the camera's integer block

uniform sampler2D u_LightTex;

#define CL_FRAGMENT
#define CL_HEADER_VARYINGS
#import <colorlight:include/colorlight_data.glsl>

uniform sampler2D u_BlockTex; // The block texture

uniform vec4 u_FogColor; // The color of the shader fog
uniform float u_FogStart; // The starting position of the shader fog
uniform float u_FogEnd; // The ending position of the shader fog

out vec4 fragColor; // The output fragment for the color framebuffer

#define CL_LIGHT_TEX u_LightTex
#import <colorlight:include/colorlight_light.glsl>

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

void main() {
    float lodBias = _material_use_mips(v_Material) ? 0.0 : float(-MAX_TEXTURE_LOD_BIAS);

    vec4 color = texture(u_BlockTex, v_TexCoord, lodBias);
    color *= cl_resolveVertexColor(); // COLORLIGHT: per-pixel light instead of the per-vertex one

#ifdef USE_FRAGMENT_DISCARD
    if (color.a < _material_alpha_cutoff(v_Material)) {
        discard;
    }
#endif

    fragColor = _linearFog(color, v_FragDistance, u_FogColor, u_FogStart, u_FogEnd);
}