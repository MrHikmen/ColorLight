# Voxy

**What it does:** Enables colored lighting for Voxy's distant LODs, beyond the normal render distance.

**How it works** (via `colorlight.voxy.mixins.json`, applied when Voxy 0.2.x is installed):
- `ShaderLoaderMixin` + `VoxyLightBridge.patch`: Patches the LOD vertex shader source code by adding `colorlight_lod.glsl`. If the expected anchors are not found, the shader is left unchanged.
- `VoxyLightBridge.onFrame`: Updates and binds an SSBO containing light grids once per frame (`VoxyLightCache`, with 7 cell levels ranging from 1 to 128 blocks, selected based on distance from the camera).
- `LodLight` / `LodLightMap`: A simplified lighting map for distant chunks. It is populated by the engine (with a budget of 1.5 ms per tick), rescanned when the engine is first initialized, and automatically saved every 5 minutes to `<game dir>/colorlight/lodlight/<uuid>.bin` (a separate file for each world and dimension).

**Vulkan backend** (Voxy on Vulkan has no GL context, so the GL buffer path is never touched there; detected via `VulkanBackend.shouldUseVulkan`):
- `VoxyLightBridge` keeps a CPU mirror of the same buffer; `VoxyVkLight` copies it into a host-visible Voxy `VkBuffer` (3 slots, rotated by Voxy's frame index) at the start of `renderTerrain`.
- `VkTerrainRendererMixin` adds SSBO binding 11 to the terrain pipelines' binding table, `VkBinderMixin` adds it to the push descriptors of the terrain draws.

**Configuration:** `VOXY_LIGHT` (enabled by default).

**Limitations:** The engine only knows about chunks loaded by the client, so lighting at great distances is approximate.

**Disable:** `-Dcolorlight.noVoxy=true`; `-Dcolorlight.forceVoxy=true` applies the patch to any Voxy version.

---

<details>
<summary>Rus</summary>

**Что даёт:** цветной свет на дальних LOD Voxy, за пределами обычной дальности прорисовки.

**Как устроено** (`colorlight.voxy.mixins.json`, применяется при установленном Voxy 0.2.x):
- `ShaderLoaderMixin` + `VoxyLightBridge.patch`: правит текст вершинного шейдера LOD (добавляет `colorlight_lod.glsl`). Если якоря не найдены, ничего не меняется.
- `VoxyLightBridge.onFrame`: раз в кадр обновляет и привязывает SSBO с сетками света (`VoxyLightCache`, 7 уровней ячеек от 1 до 128 блоков, выбор по расстоянию до камеры).
- `LodLight` / `LodLightMap`: упрощённая карта света для дальних чанков. Питается от движка (бюджет 1.5 мс/тик), пересканируется при первом появлении движка и автосохраняется каждые 5 минут в `<game dir>/colorlight/lodlight/<uuid>.bin` (отдельный файл на мир и измерение).

**Vulkan-бэкенд** (у Voxy на Vulkan нет GL-контекста, поэтому GL-путь с буфером там не трогается; определяется через `VulkanBackend.shouldUseVulkan`):
- `VoxyLightBridge` ведёт CPU-копию того же буфера; `VoxyVkLight` в начале `renderTerrain` копирует её в host-visible `VkBuffer` Voxy (3 слота по индексу кадра Voxy).
- `VkTerrainRendererMixin` добавляет SSBO-биндинг 11 в таблицу биндингов террейн-пайплайнов, `VkBinderMixin` — в push-дескрипторы террейн-draw'ов.

**Настройка:** `VOXY_LIGHT` (по умолчанию включено).

**Ограничения:** движок знает только загруженные клиентом чанки, поэтому далёкий свет приближённый.

**Отключить:** `-Dcolorlight.noVoxy=true`; `-Dcolorlight.forceVoxy=true` применяет патч к любой версии Voxy.

</details>

---