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