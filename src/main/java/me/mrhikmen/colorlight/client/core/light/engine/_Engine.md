# Light engine (`core/light`)

## English

### The idea
Light is not stored as three colour channels. Every light is **one brightness plus a palette id**:
the flood spreads a single number (the brightness of one colour), the colour is only a label and can never drift.

### Pieces
- `LightPalette` - up to 255 hues (brightest channel 255). Hues within 6 per channel share an id; an id never changes
  its colour. A copy is uploaded to the GPU (header ints `16 + id`).
- `LightCell` - one `long` per cell: two slots `(level, id)`, the two brightest colours, plus flags. Insertion is
  order independent. When a third colour had no room the cell gets `OVERFLOW`.
- `LightStorage` - sparse 16x16x16 sections of `long` cells, lock-free reads.
- `TablePropagator` - the flood kernel (label correcting, result = per-cell maximum of every seed alone, so the order
  does not matter). Neighbours / losses come from the propagation JSON files.
- `ColorLightEngine` - sources, removal, block changes, dynamic lights, dirty sections. One lock, a single apply thread.
- `DynamicLightLayer`, `DynamicFlood` - moving lights, kept apart from static light.
- GPU: `copySectionPacked` packs a cell into 32 bits. One light (nearly all cells): bits 0..23 are the finished colour,
  so the shader does no palette lookup. Two lights: bit 29 set, `I | id1 | id2 | ratio(5)`, and the shader
  (`colorlight_data.glsl`, `flw.glsl`, `cl_decodeCell`) takes the per-channel maximum of the two hues. Bit 30 = opaque.

### Removal and block changes
The cells lit by the removed light are darkened colour by colour, cells that are lit by something else are queued and
flood again. If the walk meets a cell with `OVERFLOW` (three or more colours met there) it cannot be followed exactly,
so the surroundings are emptied and every source that can reach them floods again (`recoverHiddenLight`).

### Loading pipeline (what a loaded chunk goes through)
1. `ColorLightChunkScanner` scans the chunk for light blocks (2 scanner threads, nearest chunk first).
2. The single "apply" thread collects the sources of **all chunks that are waiting** (`queueSources`) and floods them
   together (`addSources`, groups of 1024 sources, the columns nearest to the player first).
3. The sources of one colour **in one chunk column** are flooded by a single flood (`buildTasks`, at most
   `-Dcolorlight.flood.maxseeds` = 8192 seeds each). A flood costs about as much as the area it lights, however many
   lamps start it, so a chunk packed with lamps is one flood instead of thousands of overlapping ones (12x faster on a
   dense test). Lamps in different columns stay apart: far-apart seeds make a flood's scratch maps huge and slow.
4. The changed sections are uploaded to the graphics card by the render thread (`GpuLightVolume.uploadPending`): 48
   sections / 2 ms per tick.

`-Dcolorlight.batch.group=N` forces the group size of step 2.


### Checking that the new engine is the one running
`/colorlight cell` prints the two slots (id, hue, level), the `OVERFLOW` flag, `[source]`, the 32-bit word the GPU gets
and the palette size for the cell in front of the face you look at and for the block itself.

## Русский

### Идея
Свет хранится не тремя каналами цвета, а **яркостью и id цвета из палитры**. Заливка распространяет одно число
(яркость одного цвета), цвет - только метка, поэтому он не может «поплыть».

### Составные части
- `LightPalette` - до 255 оттенков (самый яркий канал = 255). Оттенки, отличающиеся не более чем на 6 по каналу, делят
  один id; цвет id не меняется. Копия палитры уходит на GPU (int'ы заголовка `16 + id`).
- `LightCell` - один `long` на ячейку: два слота `(уровень, id)` - два самых ярких цвета - и флаги. Вставка не зависит
  от порядка. Если третьему цвету не хватило места, ячейка получает `OVERFLOW`.
- `LightStorage` - разреженные секции 16x16x16 из `long`, чтение без блокировок.
- `TablePropagator` - ядро заливки (результат = максимум по ячейке от каждого источника по отдельности, поэтому порядок
  не важен). Соседи и потери берутся из JSON методов распространения.
- `ColorLightEngine` - источники, удаление, смена блоков, динамические источники, «грязные» секции. Одна блокировка и
  один поток применения.
- `DynamicLightLayer`, `DynamicFlood` - движущиеся источники, отдельно от статического света.
- GPU: `copySectionPacked` пакует ячейку в 32 бита. Один свет (почти все ячейки): биты 0..23 — готовый цвет,
  шейдеру не нужно читать палитру. Два света: бит 29 установлен, `I | id1 | id2 | ratio(5)`, и шейдер
  (`colorlight_data.glsl`, `flw.glsl`, `cl_decodeCell`) берёт максимум по каналам из двух оттенков. Бит 30 — непрозрачный.

### Удаление и смена блоков
Ячейки, освещённые удалённым светом, гасятся по цветам; ячейки, которые освещены чем-то ещё, ставятся в очередь и
заливаются заново. Если обход встречает ячейку с `OVERFLOW` (там сошлись три цвета и больше), её нельзя проследить
точно: окрестность очищается, и все источники, способные до неё дотянуться, заливаются заново (`recoverHiddenLight`).

### Конвейер загрузки (что проходит загруженный чанк)
1. `ColorLightChunkScanner` ищет в чанке источники света (2 потока сканера, сначала ближайшие чанки).
2. Единственный поток «apply» собирает источники **всех ожидающих чанков** (`queueSources`) и заливает их вместе
   (`addSources`, группы по 1024 источника, сначала колонки, ближайшие к игроку).
3. Источники одного цвета **в одной колонке чанка** заливаются одной заливкой (`buildTasks`, не более
   `-Dcolorlight.flood.maxseeds` = 8192 источников). Заливка стоит примерно как площадь, которую она освещает, сколько бы
   ламп её ни начинало, поэтому чанк, плотно набитый лампами, — одна заливка вместо тысяч перекрывающихся (на плотном
   тесте в 12 раз быстрее). Лампы из разных колонок остаются раздельно: далёкие друг от друга источники раздувают
   рабочие таблицы заливки и замедляют её.
4. Изменённые секции загружает на видеокарту поток рендера (`GpuLightVolume.uploadPending`): 48 секций / 2 мс за тик.

`-Dcolorlight.batch.group=N` задаёт размер группы из шага 2.


### Как убедиться, что работает новый движок
`/colorlight cell` показывает два слота (id, оттенок, уровень), флаг `OVERFLOW`, `[source]`, 32-битное слово для GPU и
размер палитры для ячейки перед гранью, на которую вы смотрите, и для самого блока.

### Flood speed (properties)
- `-Dcolorlight.flood.threads=N` - flood threads in total, apply thread included. Default: a quarter of the CPU's threads (at least 1).
- `-Dcolorlight.flood.band=N` - a chunk column taller than this (default 64, also capped so the scratch box fits the dense kernel) is flooded in
  height bands; 0 = off. The light is identical, only the work changes.
- The dense kernel writes a whole flood into the storage in one batch (`LightField.raiseBatch` -> `LightStorage.raiseAll`).

### Скорость заливки (параметры)
- `-Dcolorlight.flood.threads=N` - всего потоков заливки, включая поток применения. По умолчанию четверть потоков процессора (минимум 1).
- `-Dcolorlight.flood.band=N` - колонка чанка выше этого значения (по умолчанию 64, и не больше, чем помещается в плотное ядро) заливается
  по высотным полосам; 0 - выключено. Свет тот же, меняется только объём работы.
- Плотное ядро записывает всю заливку в хранилище за один проход (`LightField.raiseBatch` -> `LightStorage.raiseAll`).

### Readers and the lock
The render thread copies sections (`copySectionPacked`) without taking the engine lock: a seqlock (`tearEpoch`) is odd while a change takes light away
(block edit, removal, moving light) and the copy waits for it; floods only add light, so a copy in the middle of one is valid. Taking the lock for every
copy starved the render thread for a whole chunk load (the apply thread re-takes the lock between groups), so light only appeared when the load was over.

### Читатели и блокировка
Поток рендера копирует секции (`copySectionPacked`) без блокировки движка: seqlock (`tearEpoch`) нечётный, пока изменение отнимает свет (правка блока,
удаление, движущийся свет), и копия ждёт его конца; заливки только добавляют свет, поэтому копия посреди заливки корректна. Блокировка на каждую копию
морила поток рендера голодом на всю загрузку чанков (поток применения берёт блокировку снова между группами), и свет появлялся только в конце.

### White gives way to colour
White hues (weakest channel within `-Dcolorlight.white.spread`=20 of 255) get palette ids from 224 up, colours from 1..223, so `LightCell` knows from the id alone
whether a light is white. A cell with one white and one coloured light shows the colour laid over the white: it covers it more the stronger it is next to
it (fully at 1/`-Dcolorlight.white.cover`=2 of the white's brightness) and the brightness never falls below the brighter of the two, so a colour fades
into white without a dark edge. Display only (`LightCell.toRgb`/`pack`, the GPU word is a plain colour): stored levels, flood and removal are untouched.
`-Dcolorlight.white.loses=false` turns it off; `-Dcolorlight.white.evicts=true` also makes white the first light a full cell leaves out.

### Белый уступает цветному
Белые оттенки (слабейший канал не дальше `-Dcolorlight.white.spread`=20 от 255) получают id палитры от 224, цвета - 1..223, поэтому `LightCell` понимает по одному
id, белый ли свет. Ячейка с одним белым и одним цветным светом показывает цвет, наложенный на белый: чем он сильнее относительно белого, тем полнее перекрывает
(полностью при 1/`-Dcolorlight.white.cover`=2 яркости белого), а яркость не опускается ниже более яркого из двух, поэтому цвет переходит в белый без тёмной
каймы. Только отображение (`LightCell.toRgb`/`pack`, слово для GPU - обычный цвет): сохранённые уровни, заливка и удаление не затронуты.
`-Dcolorlight.white.loses=false` выключает; `-Dcolorlight.white.evicts=true` дополнительно делает белый первым, что вытесняется из полной ячейки.

## Debug page / Страница «Debug»

All the knobs above are also settings on the **Debug** page of ColorLight's Sodium settings (`LightTuning` holds the live values,
`ColorLightConfig.applyTuning()` copies them from `config/colorlight.json` on load and on every apply). Groups: *White light*
(colour over white, what counts as white, cover strength, drop white first) and *Light engine* (propagation threads, source search
threads, band height, sources per task, max seeds, parallel threshold, source flush size). 0 threads = Auto (a quarter of the CPU).
The `-Dcolorlight.*` properties only set the defaults. Scanner threads are resized live; the rest is read on the next flood.

Все настройки выше есть и на странице **Debug** в настройках ColorLight (Sodium). Живые значения лежат в `LightTuning`,
`ColorLightConfig.applyTuning()` копирует их из `config/colorlight.json` при загрузке и при каждом применении. Группы: «Белый свет»
(цвет поверх белого, что считается белым, сила перекрытия, белый вытесняется первым) и «Движок света» (потоки распространения,
потоки поиска источников, высота полосы, источников на задачу, макс. затравок, порог параллельности, размер пачки источников).
0 потоков = Авто (четверть процессора). Свойства `-Dcolorlight.*` задают только значения по умолчанию. Потоки поиска меняются на
лету, остальное читается при следующей заливке.
