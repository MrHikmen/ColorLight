# Voxy

**What it does:** Enables colored lighting for Voxy's distant LODs, beyond the normal render distance.

**How it works** (via `colorlight.mixins.json`, applied when Voxy 0.2.x is installed):
- `ShaderLoaderMixin` + `VoxyLightBridge.patch`: Patches the LOD vertex shader source code by adding `colorlight_lod.glsl`. If the expected anchors are not found, the shader is left unchanged.
- `VoxyLightBridge.onFrame`: Updates and binds an SSBO containing light grids once per frame (`VoxyLightCache`, with 7 cell levels ranging from 1 to 128 blocks, selected based on distance from the camera).
- `LodLight` / `LodLightMap`: A simplified lighting map for distant chunks. It is populated by the engine (with a budget of 1.5 ms per tick), rescanned when the engine is first initialized, and automatically saved every 5 minutes to `<game dir>/colorlight/lodlight/<uuid>.bin` (a separate file for each world and dimension).

**Vulkan backend** (Voxy on Vulkan has no GL context, so the GL buffer path is never touched there; detected via `VulkanBackend.shouldUseVulkan`):
- `VoxyLightBridge` keeps a CPU mirror of the same buffer; `VoxyVkLight` copies it into a host-visible Voxy `VkBuffer` (3 slots, rotated by Voxy's frame index) at the start of `renderTerrain`.
- `VkTerrainRendererMixin` adds SSBO binding 11 to the terrain pipelines' binding table, `VkBinderMixin` adds it to the push descriptors of the terrain draws.

**Per-block reach and propagation method** Every light source found on the LODs carries its block's own reach (the lesser of its light level and the light range setting) and the propagation method that block uses (`propagation` of the block setting, else the default method of the settings). Any method works, the built-in `grid` / `smooth` and those of resource packs: each method's open-air path cost is turned into a 33x33x33 distance table (`LodMethodTables`) that the shader looks up (the default plus the first seven methods get a table). Voxy's own block light is not used on the LODs: only ColorLight's sources light them. After changing a block's method or the packs, press Import again to re-read sources.

**Quad splitting around lights** Voxy merges faces into quads (up to 16x16) when block and *vanilla* light are equal, and the shader colours a quad once. Outside the diamond of the vanilla light level the ground was a few huge quads, so a light was cut off along that diamond whatever its propagation method. `MesherMixin` + `RenderDataFactoryMixin` + `LodSplit` split every quad that a source's light reaches (levels 0..2) into single cells while the section is meshed, so every block is coloured on its own. `VoxyRemesh` asks Voxy to rebuild the sections around a source when it is found, changed or removed (and, once, around the sources of the save file). The shader samples the cell in front of the face, like the near-field renderer.

**Configuration:** `VOXY_LIGHT` (enabled by default).

**Limitations:** The engine only knows about chunks loaded by the client, so lighting at great distances is approximate.

**Disable:** `-Dcolorlight.noVoxy=true`; `-Dcolorlight.forceVoxy=true` applies the patch to any Voxy version.

---

<details>
<summary>Rus</summary>

**Что даёт:** цветной свет на дальних LOD Voxy, за пределами обычной дальности прорисовки.

**Как устроено** (`colorlight.mixins.json`, применяется при установленном Voxy 0.2.x):
- `ShaderLoaderMixin` + `VoxyLightBridge.patch`: правит текст вершинного шейдера LOD (добавляет `colorlight_lod.glsl`). Если якоря не найдены, ничего не меняется.
- `VoxyLightBridge.onFrame`: раз в кадр обновляет и привязывает SSBO с сетками света (`VoxyLightCache`, 7 уровней ячеек от 1 до 128 блоков, выбор по расстоянию до камеры).
- `LodLight` / `LodLightMap`: упрощённая карта света для дальних чанков. Питается от движка (бюджет 1.5 мс/тик), пересканируется при первом появлении движка и автосохраняется каждые 5 минут в `<game dir>/colorlight/lodlight/<uuid>.bin` (отдельный файл на мир и измерение).

**Vulkan-бэкенд** (у Voxy на Vulkan нет GL-контекста, поэтому GL-путь с буфером там не трогается; определяется через `VulkanBackend.shouldUseVulkan`):
- `VoxyLightBridge` ведёт CPU-копию того же буфера; `VoxyVkLight` в начале `renderTerrain` копирует её в host-visible `VkBuffer` Voxy (3 слота по индексу кадра Voxy).
- `VkTerrainRendererMixin` добавляет SSBO-биндинг 11 в таблицу биндингов террейн-пайплайнов, `VkBinderMixin` — в push-дескрипторы террейн-draw'ов.

**Свой радиус и способ распространения у блока** Каждый найденный на LOD источник несёт свою длину распространения (меньшее из уровня света блока и настройки дальности) и способ распространения блока (поле `propagation` в настройках блока, иначе способ по умолчанию). Работает любой способ, встроенные `grid` / `smooth` и из ресурспаков: стоимость пути в воздухе превращается в таблицу расстояний 33x33x33 (`LodMethodTables`), которую читает шейдер (таблица есть у способа по умолчанию и у первых семи). После смены способа блока или ресурспаков нажмите «Импорт» ещё раз.

**Разбиение квадов вокруг источников** Voxy склеивает грани в квады (до 16x16), если блок и *ванильный* свет одинаковы, а шейдер красит квад один раз. Вне ромба ванильного света земля была несколькими огромными квадами, поэтому свет обрезался по этому ромбу, каким бы ни был способ распространения. `MesherMixin` + `RenderDataFactoryMixin` + `LodSplit` при построении секции режут каждый квад, до которого доходит свет источника (уровни 0..2), на отдельные блоки, и каждый блок красится сам. `VoxyRemesh` просит Voxy пересобрать секции вокруг источника, когда он найден, изменён или убран (и один раз вокруг источников из файла сохранения). Шейдер берёт клетку перед гранью, как это делает ближний рендер.

**Настройка:** `VOXY_LIGHT` (по умолчанию включено).

**Ограничения:** движок знает только загруженные клиентом чанки, поэтому далёкий свет приближённый.

**Отключить:** `-Dcolorlight.noVoxy=true`; `-Dcolorlight.forceVoxy=true` применяет патч к любой версии Voxy.

</details>

---