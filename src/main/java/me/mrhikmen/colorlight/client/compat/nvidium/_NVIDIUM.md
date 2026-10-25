# Nvidium

**What it does:** Enables colored lighting on the GPU pipeline (`GPU_PIPELINE`) to work with Nvidium, without falling back to vertex colors (CPU).

**Problem:** Nvidium bypasses `SodiumWorldRenderer.renderLayer`, so the ColorLight hooks in Sodium (`DefaultChunkRenderer`) were never executed, and nothing was reading the light volume.

**How it works** (via the `colorlight.mixins.json` mixins, only when Nvidium is installed):
- `NvidiumShaderLoaderMixin` + `NvidiumBridge.patchShader`: Patches Nvidium shader source code (`terrain/mesh.glsl`, `terrain/translucent/mesh.glsl`, `terrain/frag.frag`). The mesh shaders pass vertex color, lightmap coordinates, and camera-relative position; the fragment shader receives `colorlight_data.glsl` and `colorlight_light.glsl` and computes lighting per pixel.
- `NvidiumRasterizerMixin`: Binds the three light buffers as texel buffers (texture units 5–7) before each render pass (main, temporal, and translucent/OIT).
- `GpuLightVolume`: When Nvidium is active, the light buffers are regular GL buffers (using the same data layout).

**Limitations:**
- Nvidium's barycentric mode (`use_nv_fragment_shader_barycentric`) is disabled for these shaders.
- If `GPU_PIPELINE` is disabled, a shaderpack is active, or an error occurs, the shader falls back to Nvidium's own lighting.
- If the shader anchors in Nvidium don't match (e.g., due to a different version), an error is logged and Nvidium continues to work as usual.

**Disable:** `-Dcolorlight.noNvidium=true`. Nvidium itself is not modified.
---

<details>
<summary>Rus</summary>

**Что даёт:** цветной свет на GPU-пайплайне (`GPU_PIPELINE`) работает вместе с Nvidium, без отката на вершинные цвета (CPU).

**Проблема:** Nvidium отменяет `SodiumWorldRenderer.renderLayer`, поэтому хуки ColorLight в Sodium (`DefaultChunkRenderer`) не выполнялись и объём света никто не читал.

**Как устроено** (миксины `colorlight.mixins.json`, только если Nvidium установлен):
- `NvidiumShaderLoaderMixin` + `NvidiumBridge.patchShader`: правит текст шейдеров Nvidium (`terrain/mesh.glsl`, `terrain/translucent/mesh.glsl`, `terrain/frag.frag`). Mesh-шейдеры передают цвет вершины, координату лайтмапа и позицию относительно камеры; fragment-шейдер получает `colorlight_data.glsl` и `colorlight_light.glsl` и считает свет попиксельно.
- `NvidiumRasterizerMixin`: перед каждой отрисовкой (основной, temporal, прозрачный/OIT) подключает три буфера света как texel buffer (юниты 5-7).
- `GpuLightVolume`: при активном Nvidium буферы света — обычные GL-буферы (та же раскладка).

**Ограничения:**
- Barycentric-режим Nvidium (`use_nv_fragment_shader_barycentric`) для этих шейдеров отключается.
- Если `GPU_PIPELINE` выключен, активен шейдерпак или произошёл сбой, шейдер сам использует свет Nvidium.
- Если якоря в шейдерах Nvidium не совпали (другая версия), в лог пишется ошибка, Nvidium работает как раньше.

**Отключить:** `-Dcolorlight.noNvidium=true`. Сам Nvidium не изменяется.

</details>

---