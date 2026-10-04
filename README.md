# ColorLight — GPU Lighting Mode

ColorLight's colored light is applied on the graphics card: a Sodium shader looks up the light for every pixel instead of baking colour into chunk vertices. The light is smoother, and chunks are not rebuilt when it changes.

Light propagation is still computed on the CPU (the Lua methods `grid` and `smooth`). Only the application of light to pixels has been moved to the GPU.

---

## How it works

**Light volume on the GPU.** The light lives in a sparse volume around the camera, stored in two texel buffers (`GpuLightVolume`):

- **data**: a header (camera, flags, tint settings) and a list of sections, each holding a slot number or `-1`;
- **pool**: slots of 4096 cells (16 KiB each); only a section that holds light gets a slot. A cell stores the light colour `0x00BBGGRR` and a "solid block" bit.

Sections are addressed in a ring, so the window follows the camera without moving any data. The shader window is 31 sections horizontally and 15 vertically. A light change rewrites only a few KiB of video memory.

**Shader.** A copy of Sodium's terrain shader (`colored_terrain.vsh/.fsh`) with a shared include, `colorlight_data.glsl`. For every pixel it:

1. determines the face normal from screen-space derivatives;
2. reads the light colour and level from the volume (with smooth interpolation);
3. builds the final colour: vanilla lighting in which the part added by block light is recoloured to the source's colour.

Sky light is left untouched, so the tint washes out by day and is fully visible at night. Fully emissive faces (glowing models) stay vanilla.

**Sodium integration.** Three small mixins (`mixin/sodium`):

| Mixin | What it does |
|---|---|
| `ShaderChunkRendererMixin` | Adds the volume buffers and swaps the terrain shaders for ours |
| `SodiumWorldRendererMixin` | Passes the camera position to the volume once per frame |
| `DefaultChunkRendererMixin` | Binds the buffers for every terrain draw |

**Fallback.** The GPU mode turns on only if all three mixins applied (they are skipped for unverified Sodium versions). If anything goes wrong, the mod switches back to the vertex-colour mode on its own and re-meshes. The GPU mode is not used with Iris shader packs.

---

## Settings

| Setting | What it does |
|---|---|
| Shader lighting (GPU) | Turns the mode on; applies immediately; unavailable while a shader pack is enabled |
| GPU light memory | How many 16×16×16 sections of light are kept on the graphics card (default 768, from 64 to 4096) |
| Tint vividness | How early the colour shows up and how far from the source it lasts |

**Tint vividness** (`tint_gamma`, 0–200%):

| Value | Behaviour |
|---|---|
| 0–20% | The colour is vivid and saturated all the way to the edge of the light; only the brightness falls off |
| 55% (default) | The colour lasts noticeably farther and weakens toward the edge |
| 100% and above | The colour fades together with the light; above 100% it stays subtle until a block is almost fully lit |

If the colour lasts too far or not far enough by default, change the line `float retention = clamp(1.0 - tintGamma, 0.0, 1.0);` in `colored_terrain.fsh`. The steepness of the whole slider is set by the `* 3.0` factor in the `exponent` line.

---

<details>
<summary>Rus</summary>

---

# ColorLight — GPU-режим освещения

Цветной свет ColorLight применяется на видеокарте: шейдер Sodium ищет свет для каждого пикселя, а не запекает цвет в вершины чанка. Свет получается плавнее, а при его изменении чанки не пересобираются.

Распространение света по-прежнему считается на CPU (Lua-методы `grid` и `smooth`). На GPU вынесено только применение света к пикселям.

---

## Как это работает

**Объём света на GPU.** Свет лежит в разреженном объёме вокруг камеры, в двух texel-буферах (`GpuLightVolume`):

- **data** — заголовок (камера, флаги, настройки тинта) и список секций, у каждой номер слота или `-1`;
- **pool** — слоты по 4096 ячеек (16 КиБ), слот есть только у секции со светом. Ячейка хранит цвет света `0x00BBGGRR` и бит «твёрдый блок».

Секции адресуются по кругу, поэтому окно следует за камерой, а данные не переносятся. Окно в шейдере: 31 секция по горизонтали и 15 по вертикали. Изменение света переписывает несколько КиБ видеопамяти.

**Шейдер.** Копия терраин-шейдера Sodium (`colored_terrain.vsh/.fsh`) с общим инклюдом `colorlight_data.glsl`. Для каждого пикселя он:

1. определяет нормаль грани по производным экранных координат;
2. берёт цвет и уровень света из объёма (с плавной интерполяцией);
3. строит итоговый цвет: ванильное освещение, в котором часть, добавленная блочным светом, перекрашена в цвет источника.

Небесный свет не трогается, поэтому днём тинт смывается, а ночью виден полностью. Полностью светящиеся грани (эмиссивные модели) остаются ванильными.

**Подключение к Sodium.** Три небольших миксина (`mixin/sodium`):

| Миксин | Что делает |
|---|---|
| `ShaderChunkRendererMixin` | Добавляет буферы объёма и подменяет терраин-шейдеры на наши |
| `SodiumWorldRendererMixin` | Раз за кадр передаёт позицию камеры в объём |
| `DefaultChunkRendererMixin` | Привязывает буферы на каждый draw терраина |

**Запасной вариант.** GPU-режим включается, только если применились все три миксина (для непроверенных версий Sodium они пропускаются). При любой неполадке мод сам возвращается к режиму вершинных цветов и пересобирает меши. С шейдерпаками Iris GPU-режим не используется.

---

## Настройки

| Настройка | Что делает |
|---|---|
| Шейдерное освещение (GPU) | Включает режим; применяется сразу; недоступна при включённом шейдерпаке |
| Память света GPU | Сколько секций 16×16×16 света хранится на видеокарте (по умолчанию 768, от 64 до 4096) |
| Насыщенность оттенка | Как рано проявляется цвет и как далеко он держится от источника |

**Насыщенность оттенка** (`tint_gamma`, 0–200%):

| Значение | Поведение |
|---|---|
| 0–20% | Цвет яркий и насыщенный до самого края света, затухает только яркость |
| 55% (по умолчанию) | Цвет держится заметно дальше, к краю слабеет |
| 100% и выше | Цвет угасает вместе со светом; выше 100% он неброский, пока блок почти не освещён полностью |

Если по умолчанию цвет держится слишком далеко или недостаточно, меняется строка `float retention = clamp(1.0 - tintGamma, 0.0, 1.0);` в `colored_terrain.fsh`, а крутизну всего слайдера задаёт коэффициент `* 3.0` в строке `exponent`.

</details>

---