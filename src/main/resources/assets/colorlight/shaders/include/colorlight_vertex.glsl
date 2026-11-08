// ColorLight: the light of one VERTEX, for the vertex shaders of terrain, entities, items, particles and moving blocks.
//
// Requires CL_FRAGMENT to be defined before including colorlight_data.glsl (that define guards the sampling code, it
// does not mean "fragment shader" any more). The fragment shader interpolates the colour this returns and shapes it
// with cl_lightColor (colorlight_light.glsl), so the light is no longer worked out per pixel from screen-space
// derivatives: those give a wrong normal on the edges of blocks, and a pixel there could sample inside a wall.
//
//   pos: the vertex position, camera-relative (the same space as the vertex's ModelViewMat, like vanilla's)
//
// Smooth lighting, like vanilla's: the trilinear average over the 8 cells around the vertex, with opaque cells and
// unknown sections left out. A vertex on a face takes the light of the air in front of it, because the solid cells of
// the wall are skipped. Returns 0 while ColorLight is off, and the fragment shader then gives the vanilla light.
vec3 cl_vertexLight(vec3 pos) {
    if (!cl_enabled()) {
        return vec3(0.0);
    }

    vec3 rel = pos + cl_cameraFrac(); // position relative to the camera's integer block
    ivec3 camBlock = cl_cameraBlock();
    ivec3 camSection = (camBlock - (camBlock & 15)) / 16;

    vec3 s = rel - 0.5; // cell centres sit at +0.5, so a vertex on a cell boundary is halfway between two of them
    vec3 fl = floor(s);
    vec3 f = s - fl;
    ivec3 base = ivec3(fl) + camBlock;

    vec3 sum = vec3(0.0);
    float wsum = 0.0;
    for (int i = 0; i < 8; i++) {
        ivec3 o = ivec3(i & 1, (i >> 1) & 1, (i >> 2) & 1);
        vec3 w3 = mix(vec3(1.0) - f, f, vec3(o));
        float w = w3.x * w3.y * w3.z;
        if (w <= 0.0001) {
            continue;
        }

        int raw = cl_cellRaw(base + o, camSection);
        if (raw < 0 || (raw & CL_OPAQUE_BIT) != 0) {
            continue;
        }

        sum += w * cl_decodeCell(raw);
        wsum += w;
    }
    return (wsum > 0.0) ? (sum / wsum) : vec3(0.0);
}
