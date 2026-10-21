#version 330 core
#extension GL_ARB_shading_language_420pack : enable
#define CL_BIND_UNITS

// ColorLight copy of sodium:blocks/block_layer_opaque.vsh (Sodium 0.8.13 / Minecraft 1.21.1, plain OpenGL).
// Everything marked COLORLIGHT is ours; the rest must stay identical to Sodium's shader.

#import <sodium:include/fog.glsl>
#import <sodium:include/chunk_vertex.glsl>
#import <sodium:include/chunk_matrices.glsl>

out vec4 v_Color;

// COLORLIGHT: what the fragment shader needs to light the pixel itself
#import <colorlight:include/colorlight_data.glsl>
flat out ivec4 v_CLHdrA;
flat out vec3 v_CLFrac;
flat out vec2 v_CLTint;
out vec4 v_ColorBase;
out vec2 v_LightUV;
out vec3 v_CLRel;
out vec2 v_TexCoord;

flat out uint v_Material;

#ifdef USE_FOG
out float v_FragDistance;
#endif

uniform int u_FogShape;
uniform vec3 u_RegionOffset;
uniform vec2 u_TexCoordShrink;

uniform sampler2D u_LightTex; // The light map texture sampler

uvec3 _get_relative_chunk_coord(uint pos) {
    // Packing scheme is defined by LocalSectionIndex
    return uvec3(pos) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u);
}

vec3 _get_draw_translation(uint pos) {
    return _get_relative_chunk_coord(pos) * vec3(16.0);
}

void main() {
    _vert_init();

    // Transform the chunk-local vertex position into world model space
    vec3 translation = u_RegionOffset + _get_draw_translation(_draw_id);
    vec3 position = _vert_position + translation;

#ifdef USE_FOG
    v_FragDistance = getFragDistance(u_FogShape, position);
#endif

    // Transform the vertex position into model-view-projection space
    gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);

    // Add the light color to the vertex color, and pass the texture coordinates to the fragment shader
    v_Color = _vert_color * texture(u_LightTex, _vert_tex_light_coord);

    // COLORLIGHT: vanilla result above stays as the fallback; these let the fragment shader redo it
    v_ColorBase = _vert_color;
    v_LightUV = _vert_tex_light_coord;
    v_CLRel = position + cl_cameraFrac(); // position relative to the camera's integer block
    v_CLHdrA = ivec4(cl_cameraBlock(), texelFetch(u_CLHeader, 3).r + 2 * texelFetch(u_CLHeader, 9).r);
    v_CLFrac = cl_cameraFrac();
    v_CLTint = vec2(cl_tintStrength(), cl_tintGamma());
    v_TexCoord = (_vert_tex_diffuse_coord_bias * u_TexCoordShrink) + _vert_tex_diffuse_coord; // FMA for precision

    v_Material = _material_params;
}
