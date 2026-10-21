// ColorLight x Flywheel with Sable: objects of a sub-level (embedding with a lighting scene id) read the sub-level's
// window of ColorLight's sub-level volume. See flw.glsl and compat/flywheel/_Flywheel.md.
//
// u_CLScenes: 8 ints per Sable lighting scene id (see SableFlywheelScenes):
//   0..2 window anchor block (plot space), 3 first page of the window, 4..5 plot-space x / z of scene coordinate 0, 6 valid
// Sable's flw_vertexLightingPos is the vertex in scene coordinates: plot space minus (centre chunk min x, 0, centre chunk min z).
#include "colorlight:colorlight/flw.glsl"

uniform isamplerBuffer u_CLScenes;
uniform isamplerBuffer u_CLDataP;
uniform isamplerBuffer u_CLPoolP;

// Cache of the last page lookup of the sub-level window (valid for one _cl_pageBase; reset when the window changes).
ivec3 _cl_plotCacheSection = ivec3(2147483647);
int _cl_plotCacheBase = -1;
int _cl_plotCacheSlot = -1;

int cl_cellRawPlot(ivec3 cell, ivec3 camSection) {
    ivec3 section = cell >> 4;

    int slot;
    if (section == _cl_plotCacheSection && _cl_pageBase == _cl_plotCacheBase) {
        slot = _cl_plotCacheSlot;
    } else {
        ivec3 d = abs(section - camSection);
        if (d.x > 15 || d.z > 15 || d.y > 15) {
            slot = -1;
        } else {
            int page = _cl_pageBase + ((section.x & 31) | ((section.z & 31) << 5) | ((section.y & 31) << 10));
            slot = texelFetch(u_CLDataP, page).r;
        }
        _cl_plotCacheSection = section;
        _cl_plotCacheBase = _cl_pageBase;
        _cl_plotCacheSlot = slot;
    }
    if (slot < 0) {
        return -1;
    }

    ivec3 l = cell & 15;
    return texelFetch(u_CLPoolP, (slot << 12) | (l.y << 8) | (l.z << 4) | l.x).r;
}

void cl_flwApply() {
    if (!cl_enabled()) {
        return;
    }

    #ifdef FLW_EMBEDDED
    if (flw_vertexLightingSceneId != 0u) {
        int b = int(flw_vertexLightingSceneId) * 8;
        if (texelFetch(u_CLScenes, b + 6).r == 0) {
            return; // no window for this sub-level (yet): Flywheel's own light
        }
        ivec3 anchor = ivec3(texelFetch(u_CLScenes, b).r, texelFetch(u_CLScenes, b + 1).r, texelFetch(u_CLScenes, b + 2).r);
        _cl_pageBase = texelFetch(u_CLScenes, b + 3).r;
        // anchor - origin is small (the window is around the plot's centre), so this stays exact in float
        ivec3 shift = anchor - ivec3(texelFetch(u_CLScenes, b + 4).r, 0, texelFetch(u_CLScenes, b + 5).r);
        vec3 rel = flw_vertexLightingPos.xyz - vec3(shift);
        cl_flwFinish(rel, anchor);
        _cl_pageBase = -1;
        return;
    }
    #endif

    ivec3 camBlock = cl_cameraBlock();
    vec3 rel = flw_vertexPos.xyz + vec3(flw_renderOrigin - camBlock);
    cl_flwFinish(rel, camBlock);
}
