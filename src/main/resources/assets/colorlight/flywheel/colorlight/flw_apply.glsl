// ColorLight x Flywheel, without Sable: the world volume only. See flw.glsl.
#include "colorlight:colorlight/flw.glsl"

int cl_cellRawPlot(ivec3 cell, ivec3 camSection) {
    return -1;
}

// Called at the end of flw_shaderLight(): flw_fragLight holds Flywheel's own result (sky light in particular).
void cl_flwApply() {
    if (!cl_enabled()) {
        return;
    }

    ivec3 camBlock = cl_cameraBlock();
    // flw_vertexPos is relative to flw_renderOrigin; the volume is addressed relative to the camera block
    vec3 rel = flw_vertexPos.xyz + vec3(flw_renderOrigin - camBlock);
    cl_flwFinish(rel, camBlock);
}
