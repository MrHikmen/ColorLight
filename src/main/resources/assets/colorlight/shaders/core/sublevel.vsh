#version 330 core

// ColorLight copy of vanilla rendertype_solid.vsh (1.21.1) for Sable sub-levels: light is looked up per pixel in the
// sub-level's window of the light volume (see colorlight_data.glsl, CL_PLOT).

#moj_import <light.glsl>
#moj_import <fog.glsl>
#define CL_PLOT
#moj_import <colorlight:colorlight_data.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 ChunkOffset;
uniform int FogShape;

out float vertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
out vec4 normal;

out vec4 v_ColorBase;
out vec2 v_LightUV;
out vec3 v_CLRel;

void main() {
    vec3 pos = Position + ChunkOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    vertexDistance = fog_distance(pos, FogShape);
    vertexColor = Color * minecraft_sample_lightmap(Sampler2, UV2);
    texCoord0 = UV0;
    normal = ProjMat * ModelViewMat * vec4(Normal, 0.0);

    // pos is plot-space position minus the camera's plot-space position (see ColorLightSableCompat)
    v_ColorBase = Color;
    v_LightUV = (vec2(UV2) + 8.0) / 256.0; // same encoding Sodium uses, which colorlight_light.glsl expects
    v_CLRel = pos + cl_cameraFrac();
}
