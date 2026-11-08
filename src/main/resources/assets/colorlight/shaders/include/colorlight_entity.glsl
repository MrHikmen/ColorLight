// ColorLight: the per-pixel colour of entities, items, particles and moving blocks, from the light the VERTEX shader
// took (colorlight_vertex.glsl). Copies of vanilla core/entity, core/item, core/particle and core/block live in
// shaders/core/cl_*.{vsh,fsh}.
//
// Include AFTER colorlight_data.glsl (with CL_FRAGMENT defined) and colorlight_light.glsl, and #define CL_LIGHT_TEX
// as the lightmap sampler2D of the including fragment shader.

// cl: the light of this pixel, interpolated from the vertex shader's cl_vertexLight
// lightUV: the lightmap coordinate of the vertex
// vanilla: the vanilla lightmap colour of the pixel; returned unchanged while ColorLight is off (header flag)
vec4 cl_entityLight(vec3 cl, vec2 lightUV, vec4 vanilla) {
    if (!cl_enabled()) {
        return vanilla;
    }
    float lvl = max(cl.r, max(cl.g, cl.b));
    return vec4(cl_lightColor(lightUV, cl, lvl), 1.0);
}
