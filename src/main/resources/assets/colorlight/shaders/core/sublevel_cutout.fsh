#version 330 core

// ColorLight copy of vanilla rendertype_cutout.fsh (1.21.1) for Sable sub-levels.

#moj_import <fog.glsl>
#define CL_PLOT
#define CL_FRAGMENT
#moj_import <colorlight:colorlight_data.glsl>

uniform sampler2D Sampler0;
uniform sampler2D Sampler2;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
in vec4 normal;

in vec4 v_ColorBase;
in vec2 v_LightUV;
in vec3 v_CLRel;

out vec4 fragColor;

#define CL_LIGHT_TEX Sampler2
#moj_import <colorlight:colorlight_light.glsl>

vec4 cl_resolveVertexColor() {
    if (!cl_enabled()) {
        return vertexColor;
    }
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
    vec4 color = texture(Sampler0, texCoord0) * cl_resolveVertexColor() * ColorModulator;
    if (color.a < 0.1) {
        discard;
    }
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
