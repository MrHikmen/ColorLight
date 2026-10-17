# Sable

**What it does:** with the GPU pipeline (Sodium) on, light on Sable sub-levels (ships, contraptions) is rendered per pixel, like the terrain, and light crosses between the world and sub-levels. Without Sable nothing changes.

**How it works:**
- Sable draws sub-levels with vanilla core shaders, not Sodium's terrain program. `VanillaChunkedSubLevelRenderDataMixin` (`colorlight.sable.mixins.json`, only applied when Sable is installed) wraps `renderChunkedSubLevel` and swaps the core shader (`solid`, `cutout`, `cutout_mipped`, `translucent`) for ColorLight's copy: `assets/colorlight/shaders/core/sublevel*`.
- Sub-level blocks live in a plot ~20M blocks away, outside the camera-centred volume. `SableLightVolume` keeps one window per sub-level (31x31x31 sections around the middle of its bounding box, own page table, shared slot pool: 16 windows, 512 sections). The shader selects it with `u_CLAnchor`, `u_CLPageBase`, `u_CLCamOff` (`CL_PLOT` in `colorlight_data.glsl`).
- Sable draws `Position + ChunkOffset` = plot-space vertex minus plot-space camera, so `ColorLightSableCompat` only computes the camera in plot space (double precision, from the render pose) relative to the anchor.
- Sable classes are read by reflection (`SableAccess`, `SableBodies`); nothing is compiled against Sable.
- `SablePlotScanner`: Sable loads plot chunks through its own path, so Fabric's chunk-load event never fires for them, and lights that were already on a ship when the world was entered were never found. Plot chunks are scanned when a sub-level is first seen, when its chunk set or bounds change, and again after 20, 100, 300 and 800 ticks.
- `LightStorage` keeps chunk columns in a 128x128 ring indexed by the low 7 bits of the chunk coordinates. A plot ~20M blocks away aliases a terrain column, and whichever wrote light last deleted the other's column (the engine still listed the source, but its cell was empty). Colliding columns now go to an overflow map instead of evicting each other.

**Light between the world and sub-levels** (`SableLightLinker`): the static light field cannot cross the plot/world boundary, so the dynamic layer is used (same worker and coalescing as entity lights; ids are negative so they never meet entity ids).
- *Sub-level -> world:* the strongest 16 lights of each nearby sub-level become dynamic lights in world space at their current world position (pose from `logicalPose()`: `world = R * (S * (plot - rotationPoint)) + position`).
- *World -> sub-level* (GPU pipeline only, because the footprint changes every tick the ship moves): the 16 nearest terrain lights whose reach touches a sub-level become dynamic lights in that sub-level's plot space (inverse pose), so the ship's own blocks occlude them.
- *Optimisation:* no sub-level near the player -> nothing is computed and every link is removed; a terrain light with no sub-level within `lightRangeBlocks + 1` of it gets no dynamic light.
- Lights are re-collected as soon as the engine's source set changes (and every 10 ticks to follow moving sub-levels), positions are followed every tick in 1/4-block steps, at most 8 floods per tick.

**Configuration** (Compatibility page, only shown with Sable):
- `SABLE_CROSS_LIGHT` (enabled by default): turns the light between the world and sub-levels on or off.
- `SABLE_LIGHT_RANGE_BLOCKS` (1-32, default 15): how far the light of a sub-level reaches into the world. Sub-level -> world lights are dimmed by `range / lightRangeBlocks` (capped at 1), which shortens their reach; it cannot exceed the general light range.

**Limitations:** single-block sub-levels (Sable's batched path) and Sable's own directional/sky-light shading are not covered; more than 16 visible sub-levels or sections farther than 14 sections from a sub-level's centre get no coloured light. Light does not go from one sub-level to another; the world -> sub-level light is not blocked by terrain, and the sub-level -> world light is not blocked by the ship's hull (each flood only sees the blocks of the space it floods in). Written against Sable 2.0.6 sources / Minecraft 1.21.1.

---

<details>
<summary>Rus</summary>

**Что даёт:** при включённом GPU-пайплайне (Sodium) свет на sublevel Sable (корабли, механизмы) рисуется попиксельно, как на террейне, а свет проходит между миром и sublevel. Без Sable ничего не меняется.

**Как устроено:**
- Sable рисует sublevel ванильными core-шейдерами, а не террейн-программой Sodium. `VanillaChunkedSubLevelRenderDataMixin` (`colorlight.sable.mixins.json`, применяется только при установленном Sable) оборачивает `renderChunkedSubLevel` и подменяет core-шейдер (`solid`, `cutout`, `cutout_mipped`, `translucent`) копией ColorLight: `assets/colorlight/shaders/core/sublevel*`.
- Блоки sublevel лежат в plot примерно в 20 млн блоков от игрока, за пределами объёма вокруг камеры. `SableLightVolume` держит по одному окну на sublevel (31x31x31 секций вокруг середины его ограничивающего бокса, своя таблица страниц, общий пул слотов: 16 окон, 512 секций). Шейдер выбирает окно через `u_CLAnchor`, `u_CLPageBase`, `u_CLCamOff` (`CL_PLOT` в `colorlight_data.glsl`).
- Sable рисует `Position + ChunkOffset` = вершина в plot-пространстве минус камера в plot-пространстве, поэтому `ColorLightSableCompat` считает только камеру в plot-пространстве (двойная точность, по позе отрисовки) относительно якоря.
- Классы Sable читаются через рефлексию (`SableAccess`, `SableBodies`); компиляции против Sable нет.
- `SablePlotScanner`: Sable загружает чанки plot своим путём, поэтому событие загрузки чанка Fabric для них не срабатывает, и источники света, уже стоявшие на корабле при входе в мир, не находились. Чанки plot сканируются при первом появлении sublevel, при изменении набора чанков или границ, а также ещё раз через 20, 100, 300 и 800 тиков.
- `LightStorage` хранит колонки чанков в кольце 128x128 с индексом по младшим 7 битам координат чанка. Plot в ~20 млн блоков совпадал по индексу с колонкой террейна, и тот, кто записывал свет последним, удалял колонку другого (движок всё ещё считал источник живым, но ячейка была пустой). Теперь колонки с совпавшим индексом уходят в отдельную таблицу переполнения и не вытесняют друг друга.

**Свет между миром и sublevel** (`SableLightLinker`): статическое поле света не пересекает границу plot и мира, поэтому используется динамический слой (тот же рабочий поток и склейка запросов, что у света сущностей; id отрицательные, поэтому не пересекаются с id сущностей).
- *Sublevel -> мир:* 16 самых ярких источников каждого ближнего sublevel становятся динамическими источниками в мировых координатах в их текущей позиции (поза из `logicalPose()`: `world = R * (S * (plot - rotationPoint)) + position`).
- *Мир -> sublevel* (только GPU-пайплайн, потому что отпечаток света меняется каждый тик движения корабля): 16 ближайших источников террейна, до которых дотягивается sublevel, становятся динамическими источниками в plot-пространстве этого sublevel (обратная поза), поэтому блоки самого корабля их затеняют.
- *Оптимизация:* если рядом с игроком нет sublevel, ничего не считается и все связи удаляются; источник террейна, рядом с которым (в пределах `lightRangeBlocks + 1`) нет sublevel, динамического света не получает.
- Источники пересобираются сразу, как только меняется набор источников в движке (и каждые 10 тиков, чтобы следовать за движущимися sublevel), позиции отслеживаются каждый тик с шагом 1/4 блока, не больше 8 пересчётов за тик.

**Настройки** (страница «Совместимость», показывается только при установленном Sable):
- `SABLE_CROSS_LIGHT` (по умолчанию включено): включает и выключает свет между миром и sublevel.
- `SABLE_LIGHT_RANGE_BLOCKS` (1-32, по умолчанию 15): на сколько блоков свет sublevel распространяется в мир. Свет sublevel -> мир приглушается в `range / lightRangeBlocks` раз (максимум 1), из-за чего сокращается его дальность; значение не может превышать общую дальность света.

**Ограничения:** одноблочные sublevel (пакетный путь Sable) и собственное направленное/небесное освещение Sable не охвачены; больше 16 видимых sublevel или секции дальше 14 секций от центра sublevel остаются без цветного света. Свет не идёт от одного sublevel к другому; свет мир -> sublevel не блокируется террейном, а свет sublevel -> мир не блокируется корпусом корабля (каждый расчёт видит только блоки того пространства, где он считается). Написано по исходникам Sable 2.0.6 / Minecraft 1.21.1.

</details>

---
