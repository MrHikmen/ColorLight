# Sodium

**What it does:** Enables the GPU lighting pipeline (`GPU_PIPELINE`): terrain is lit per pixel in a shader instead of baking colors into chunk meshes. Light is smoother, and a light change only rewrites a few KiB of GPU memory instead of rebuilding chunks. Without it, ColorLight falls back to vertex colors, which work on any Sodium version.

**How it works** (via `colorlight.sodium.mixins.json`, applied only for Sodium 0.9.x on Minecraft 26.3, see `ColorLightMixinPlugin`):
- `ShaderChunkRendererMixin`: Extends Sodium's terrain bind group (`LIGHT_GROUP`) with three texel buffers (`u_CLHeader`, `u_CLData`, `u_CLPool`), and points the terrain pipelines (`createShader` and `createOITShader`, so the order-independent-transparency stage too) from `sodium:blocks/block_layer_opaque` to `colorlight:blocks/colored_terrain`. Only the two `main()` functions in the shader are copies; globals, fog, and the vertex format are still `#include`d from Sodium.
- `SodiumWorldRendererMixin`: Hooks `setupTerrain`, which runs once per frame before any chunk layer is drawn. The camera is split exactly like Sodium splits it (`CameraTransform`) and written to the volume's header.
- `DefaultChunkRendererMixin`: Binds the three buffers for every terrain draw, right after Sodium sets up the pipeline.
- `GpuLightVolume`: A sparse, camera-centered voxel volume. Header buffer (16 ints: camera, flags, tint settings); data buffer (one int per section page, addressed toroidally so the window follows the camera without moving anything); pool buffer (slots of 4096 cells = 16 KiB, only for sections that hold light; a cell is `0x00BBGGRR`, bit 24 marks solid blocks). The shader window is 31 sections horizontally and 15 vertically around the camera. Uploads are limited to 2 ms and 48 sections per tick.
- `SodiumHookMarkers`: Empty interfaces the mixins add to Sodium's classes. `ColorLightGpu` checks them by reflection to learn whether all three mixins really applied, without naming a Sodium class itself.
- `ColorLightSodiumConfig` (`sodium:config_api_user`): Adds ColorLight's pages to Sodium's video settings (including the "GPU experiments" group); the Mod Menu button opens them.

**Configuration:** `GPU_PIPELINE` (enabled by default, can be switched while playing, locked while a shader pack is on); `GPU_LIGHT_SECTIONS` (64..4096, default 768 = 12 MiB). Raise it if light is missing far away in a big lit area, lower it to save video memory.

**Limitations:**
- Only Sodium versions the shader copy was written against get the pipeline; any other version silently uses vertex colors.
- Iris shader packs replace Sodium's terrain renderer, so while a pack is on, vertex colors are used (polled every 4 ticks, meshes are rebuilt on the switch).
- Every injector is `require = 0`. If a hook did not apply or the light buffers are missing, the pipeline is switched off for the rest of the session (`ColorLightGpu.fail`), vertex colors take over, and chunks are rebuilt; the game still starts and keeps working.
- Whether the pipeline is installed is decided once per session, because Sodium compiles its pipelines once. With Nvidium in charge of the terrain, see `_NVIDIUM.md`.

**Disable:** `-Dcolorlight.noGpu=true` skips the mixins entirely (vertex colors); `-Dcolorlight.forceGpu=true` applies them to any Sodium version. Sodium itself is only touched by the three mixins above.

---

<details>
<summary>Rus</summary>

**Что даёт:** включает GPU-пайплайн освещения (`GPU_PIPELINE`): террейн освещается попиксельно в шейдере вместо запекания цвета в меши чанков. Свет плавнее, а его изменение переписывает лишь несколько КиБ видеопамяти и не перестраивает чанки. Без него ColorLight откатывается на вершинные цвета, которые работают на любой версии Sodium.

**Как устроено** (`colorlight.sodium.mixins.json`, применяется только для Sodium 0.9.x на Minecraft 26.3, см. `ColorLightMixinPlugin`):
- `ShaderChunkRendererMixin`: расширяет terrain bind group Sodium (`LIGHT_GROUP`) тремя texel buffer (`u_CLHeader`, `u_CLData`, `u_CLPool`) и перенаправляет terrain-пайплайны (`createShader` и `createOITShader`, то есть и стадию order-independent transparency) с `sodium:blocks/block_layer_opaque` на `colorlight:blocks/colored_terrain`. Копиями являются только две функции `main()` шейдера; globals, fog и формат вершин по-прежнему подключаются из Sodium через `#include`.
- `SodiumWorldRendererMixin`: хук на `setupTerrain`, который выполняется раз в кадр до отрисовки любого слоя чанков. Камера делится точно так же, как у Sodium (`CameraTransform`), и записывается в заголовок объёма.
- `DefaultChunkRendererMixin`: привязывает три буфера для каждой отрисовки террейна, сразу после настройки пайплайна Sodium.
- `GpuLightVolume`: разреженный воксельный объём вокруг камеры. Буфер заголовка (16 int: камера, флаги, настройки тинта); буфер данных (один int на страницу секции, адресация тороидальная, поэтому окно следует за камерой без перемещения данных); буфер пула (слоты по 4096 ячеек = 16 КиБ, только для секций со светом; ячейка `0x00BBGGRR`, бит 24 помечает твёрдые блоки). Окно шейдера — 31 секция по горизонтали и 15 по вертикали вокруг камеры. Загрузка ограничена 2 мс и 48 секциями за тик.
- `SodiumHookMarkers`: пустые интерфейсы, которые миксины добавляют классам Sodium. `ColorLightGpu` проверяет их через рефлексию, чтобы понять, применились ли все три миксина, не упоминая классы Sodium напрямую.
- `ColorLightSodiumConfig` (`sodium:config_api_user`): добавляет страницы ColorLight в настройки видео Sodium (включая группу «Эксперименты с GPU»); кнопка в Mod Menu открывает их.

**Настройка:** `GPU_PIPELINE` (по умолчанию включено, переключается во время игры, заблокировано при включённом шейдерпаке); `GPU_LIGHT_SECTIONS` (64..4096, по умолчанию 768 = 12 МиБ). Увеличьте, если вдали в большой освещённой области пропадает свет; уменьшите, чтобы сэкономить видеопамять.

**Ограничения:**
- Пайплайн получают только версии Sodium, под которые написана копия шейдера; на остальных молча используются вершинные цвета.
- Шейдерпаки Iris заменяют terrain-рендерер Sodium, поэтому при включённом паке используются вершинные цвета (опрос раз в 4 тика, при переключении меши перестраиваются).
- Все инжекторы с `require = 0`. Если хук не применился или буферы света отсутствуют, пайплайн отключается до конца сессии (`ColorLightGpu.fail`), включаются вершинные цвета и чанки перестраиваются; игра при этом запускается и продолжает работать.
- Установлен пайплайн или нет, решается один раз за сессию, потому что Sodium компилирует свои пайплайны один раз. Если террейном управляет Nvidium, см. `_NVIDIUM.md`.

**Отключить:** `-Dcolorlight.noGpu=true` полностью пропускает миксины (вершинные цвета); `-Dcolorlight.forceGpu=true` применяет их к любой версии Sodium. Sodium изменяется только тремя миксинами выше.

</details>

---
