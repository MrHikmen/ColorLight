# ColorLight: Lua in Resource Packs

Light propagation methods and default settings are defined through **Lua files in the resource pack**, while block colors are configured using standard **JSON** (colors in `hex` or `rgb` format). The mod provides `grid.lua` and `smooth.lua` by default — any resource pack can override them or add its own methods.

```text
assets/colorlight/
├── settings.lua            default settings
├── propagation/<name>.lua  light propagation method, id = colorlight:<name>
└── block/<name>.json       block colors / light level / propagation method (standard JSON)
```

Files are loaded every time resources are reloaded (`F3+T`, changing resource packs). A complete example is available in `examples/resourcepack/`. To see which files were loaded and whether any errors occurred, use the `/colorlight scripts` command.

## Security

A script is downloaded content, so the sandbox is strict: only `base` (without `dofile/loadfile/require`), `table`, `string`, `math`, `bit32`, and the `colorlight` table are available. There is no `io`, `os`, `debug`, or Java reflection. Infinite loops are interrupted by an instruction limit, and an error in a file only causes that file to be skipped (the error is recorded in the log and shown by `/colorlight scripts`).

---

## 1. Propagation Methods — `propagation/<name>.lua`

The file returns a table. There are two types.

### Table-based

Lua describes the *rules*, while the propagation loop itself is executed in Java. The `loss` function is called in advance (for each neighbor and each opacity value), so Lua is not executed during the actual light propagation — performance is the same as with the previous Java implementation.

```lua
return {
    name = "Vertical beam",       -- name displayed in the menu
    scale = 1,                    -- precision: 1 = whole units, 8 = 1/8 (1..64)
    preserve_hue = true,          -- color channels fade together (hue remains stable)
    max_opacity = 15,             -- blocks with this opacity or higher stop the light
    neighbors = colorlight.faces, -- where light can spread: list of {dx,dy,dz}, each from -1 to 1
    loss = function(dx, dy, dz, opacity, decay, range)
        return (1 + opacity) * decay   -- light loss (0..255) per step
    end,
}
```

| Field                                | Description                                                                                                                                                                                                                                                                             |
| ------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `neighbors`                          | List of `{dx,dy,dz}`; `colorlight.faces` — 6 faces, `colorlight.full` — all 26 neighbors. Offsets must be within ±1 (this is what allows light to fade when a block is removed). Can also be a function returning a list.                                                               |
| `loss(dx,dy,dz,opacity,decay,range)` | `opacity` — opacity of the block being entered, 0..15; `decay` = 255 / range — light loss per block in air; `range` — propagation distance in blocks. The result is measured in light units; the minimum after rounding is 1. If `loss` is not specified: `distance·(1+opacity)·decay`. |
| `scale`                              | Precision of intermediate value storage. `1` — fast (like a "diamond"), `8` — smooth (like a "circle").                                                                                                                                                                                 |

The built-in `grid.lua` (6 neighbors, `scale=1`) and `smooth.lua` (26 neighbors, `scale=8`, loss proportional to distance) fully reproduce the previous behavior — verified through bit-by-bit comparison with the old Java code.

### Fully scripted (flexible, slower)

```lua
return {
    name = "Cross (scripted)",
    propagate = function(field, queue, ctx)
        while true do
            local x, y, z = queue.poll()      -- nil when the queue is empty
            if not x then break end
            local r, g, b = field.get(x, y, z)
            -- ... field.set(nx, ny, nz, r, g, b); queue.add(nx, ny, nz)
        end
    end,
}
```

* `field.get(x,y,z) -> r,g,b`, `field.set(x,y,z,r,g,b)`, `field.opacity(x,y,z) -> 0..15`
* `queue.poll() -> x,y,z | nil`, `queue.add(x,y,z)`, `queue.empty()`
* `ctx.decay`, `ctx.range`, `ctx.max_opacity`

This runs in the interpreter, so it is noticeably slower than the table-based method — intended for small effects. This method cannot be used for moving light sources (see `dynamic_propagation`).

### Multiple methods in one file

```lua
colorlight.propagation("beam_short", { ... })   -- id colorlight:beam_short
```

### Selecting a method for a block

In the block JSON: `"propagation": "colorlight:beam"`; the global method is defined by the `propagation` setting.

---

## 2. Blocks — `block/<name>.json`

Standard JSON: the key selects the block(s), and the value describes the light. All `.json` files from `assets/colorlight/block/` across all enabled resource packs are loaded alphabetically (a file with the same name in a higher-priority pack overrides the one in a lower-priority pack; prefixes such as `10_`, `20_` define the order). Later entries override earlier ones **field by field**. Comments `//` and `/* */` are allowed; keys beginning with `_` are ignored (useful for notes).

Priority: **texture scanning < API from other mods < resource pack JSON < blocks manually edited by the player in the GUI.**

```json
{
  "minecraft:torch":      { "color": [255, 150, 60], "light": 14, "propagation": "colorlight:smooth" },
  "soul_torch":           { "color": "#3fd0ff" },
  "minecraft:lantern":    { "rgb": { "r": 255, "g": 200, "b": 120 } },
  "minecraft:candle":     { "hex": "ffcc66" },
  "#minecraft:candles":   { "light": 12 },
  "minecraft:*_lantern":  { "r": 255, "g": 190, "b": 110 },
  "minecraft:lava":       { "enabled": false }
}
```

**Key:** block ID (`minecraft:` can be omitted), block tag `#minecraft:candles`, or a pattern containing `*` and `?`.

**Color** — any one of the following formats, channels 0..255:

| Format                | Example                                                   |
| --------------------- | --------------------------------------------------------- |
| `color`: hex string   | `"color": "#ff8800"` (or `"#f80"`, `"ff8800"`)            |
| `color`: array        | `"color": [255, 136, 0]`                                  |
| `color`: object       | `"color": {"r": 255, "g": 136, "b": 0}`                   |
| `hex`                 | `"hex": "#ff8800"`                                        |
| `rgb`: array / object | `"rgb": [255, 136, 0]` · `"rgb": {"r":255,"g":136,"b":0}` |
| Separate fields       | `"r": 255, "g": 136, "b": 0`                              |

**Other fields** (all optional; omitted fields retain their previous values): `light` 1..15 — maximum emitted light level; `propagation` — propagation method ID; `enabled: false` — disables colored light for the block.

An invalid entry is skipped and reported by `/colorlight scripts`; the rest of the file is still applied. Block tags are provided by the server after resources are loaded, so tag-based rules are applied when entering a world.

---

## 3. Settings — `settings.lua`

```lua
return {
    light_range = 12,
    propagation = "colorlight:smooth",
    tint_gamma  = 0.7,
}
```

(Alternatively, `colorlight.settings{ ... }`.)

Values from the resource pack are **default values**: any setting the player has changed manually in the ColorLight menu remains unchanged; all other settings take their values from the pack (when multiple packs are enabled, the highest-priority pack takes precedence). Pack values are not written to `colorlight.json`, so the previous values are restored after the pack is disabled.

| Key                                                                                                                                                         | Type / Range                                                             |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------ |
| `enable`, `smooth_lighting`                                                                                                                                 | true/false                                                               |
| `light_range`                                                                                                                                               | 1..32 (blocks)                                                           |
| `propagation`                                                                                                                                               | Method ID for blocks, e.g. `"colorlight:grid"`                           |
| `dynamic_propagation`                                                                                                                                       | Method ID for moving light sources (LambDynamicLights); table-based only |
| `tint_gamma`                                                                                                                                                | 0..2                                                                     |
| `brightness_weight`, `local_weight`, `region_weight`, `alpha_weight`, `anomaly_weight`, `saturation_weight`, `glowcolorscore_weight`, `whitepenalty_weight` | 0..100 (texture scanner weights)                                         |
| `entity_tracking`, `entity_follow_render_distance`                                                                                                          | true/false                                                               |
| `entity_radius_chunks`                                                                                                                                      | 2..32                                                                    |

---

## 4. API for Other Mods (Java)

* `PropagationMethodRegistry.register(method)` — register a method from code; it persists across resource reloads. The IDs `colorlight:grid` / `colorlight:smooth` are always available (Lua versions override the built-in fallbacks).
* `ColorLightBlockAPI.register(...)` — default block colors (lower priority than resource pack JSON).
* `ColorLightLua.register(name, function)` — add a custom function to the `colorlight` table for scripts.

## 5. Limitations

* Light can spread only to the 26 neighboring blocks (offsets −1..1).
* Moving (entity) light sources use the table-based method (`dynamic_propagation`); the scripted `propagate` method is not suitable for them — `colorlight:smooth` is used.
* Script changes take effect after resources are reloaded; light in the loaded world is recalculated at that point.

<details>
<summary>Rus</summary>

# ColorLight: Lua в ресурспаках

Способ распространения света и настройки по умолчанию описываются **Lua-файлами в ресурспаке**, цвета блоков — обычным **JSON** (цвет в `hex` или `rgb`). Сам мод поставляет `grid.lua` и `smooth.lua` — любой ресурспак может их заменить или добавить свои.

```
assets/colorlight/
├── settings.lua            настройки по умолчанию
├── propagation/<имя>.lua   способ распространения света, id = colorlight:<имя>
└── block/<имя>.json        цвета / сила / способ для блоков (обычный JSON)
```

Файлы читаются при каждой загрузке ресурсов (`F3+T`, смена паков). Готовый пример — `examples/resourcepack/`. Посмотреть, что загрузилось и какие ошибки: команда `/colorlight scripts`.

## Безопасность

Скрипт — это скачанный контент, поэтому песочница жёсткая: доступны только `base` (без `dofile/loadfile/require`), `table`, `string`, `math`, `bit32` и таблица `colorlight`. Нет `io`, `os`, `debug`, Java-рефлексии. Бесконечный цикл прерывается по лимиту инструкций, ошибка в файле лишь пропускает этот файл (в лог и в `/colorlight scripts`).

---

## 1. Способы распространения — `propagation/<имя>.lua`

Файл возвращает таблицу. Есть два вида.

### Табличный

Lua описывает *правила*, а цикл заливки выполняет Java. Функция `loss` вызывается заранее (для каждого соседа и каждой непрозрачности), поэтому внутри самой заливки Lua не исполняется — скорость такая же, как у прежнего Java-кода.

```lua
return {
    name = "Vertical beam",       -- название в меню
    scale = 1,                    -- точность: 1 = целые единицы, 8 = 1/8 (1..64)
    preserve_hue = true,          -- каналы цвета гаснут вместе (оттенок не плывёт)
    max_opacity = 15,             -- блок с такой непрозрачностью и выше останавливает свет
    neighbors = colorlight.faces, -- куда свет прыгает: список {dx,dy,dz}, каждое -1..1
    loss = function(dx, dy, dz, opacity, decay, range)
        return (1 + opacity) * decay   -- потеря света (0..255) за один прыжок
    end,
}
```

| Поле                                 | Значение                                                                                                                                                                                                                                          |
|--------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `neighbors`                          | список `{dx,dy,dz}`; `colorlight.faces` — 6 граней, `colorlight.full` — все 26 соседей. Смещения только в пределах ±1 (на этом держится «гашение» света при удалении блока). Можно функцией, возвращающей список.                                 |
| `loss(dx,dy,dz,opacity,decay,range)` | `opacity` — затемнение входимого блока 0..15; `decay` = 255 / дальность — потеря на блок в воздухе; `range` — дальность в блоках. Результат в единицах света; минимум после округления — 1. Если `loss` не задан: `расстояние·(1+opacity)·decay`. |
| `scale`                              | точность хранения промежуточного значения. `1` — быстро (как «ромб»), `8` — гладко (как «круг»).                                                                                                                                                  |

Встроенные `grid.lua` (6 соседей, `scale=1`) и `smooth.lua` (26 соседей, `scale=8`, потеря ∝ расстоянию) полностью воспроизводят прежнее поведение — проверено побитовым сравнением со старым Java-кодом.

### Полностью скриптовый (гибкий, медленнее)

```lua
return {
    name = "Cross (scripted)",
    propagate = function(field, queue, ctx)
        while true do
            local x, y, z = queue.poll()      -- nil, когда очередь пуста
            if not x then break end
            local r, g, b = field.get(x, y, z)
            -- ... field.set(nx, ny, nz, r, g, b); queue.add(nx, ny, nz)
        end
    end,
}
```

* `field.get(x,y,z) -> r,g,b`, `field.set(x,y,z,r,g,b)`, `field.opacity(x,y,z) -> 0..15`
* `queue.poll() -> x,y,z | nil`, `queue.add(x,y,z)`, `queue.empty()`
* `ctx.decay`, `ctx.range`, `ctx.max_opacity`

Работает в интерпретаторе, поэтому заметно медленнее табличного — для небольших эффектов. Такой способ нельзя использовать для движущихся источников (см. `dynamic_propagation`).

### Несколько методов в одном файле

```lua
colorlight.propagation("beam_short", { ... })   -- id colorlight:beam_short
```

### Выбор способа для блока

В JSON блоков: `"propagation": "colorlight:beam"`; общий способ — настройка `propagation`.

---

## 2. Блоки — `block/<имя>.json`

Обычный JSON: ключ выбирает блок(и), значение описывает свет. Читаются все `.json` из `assets/colorlight/block/` всех включённых паков, по алфавиту (файл с тем же именем в паке выше заменяет файл нижнего; префиксы `10_`, `20_` задают порядок). Более поздние записи переопределяют ранние **по полям**. Комментарии `//` и `/* */` терпятся; ключи, начинающиеся с `_`, игнорируются (удобно для заметок).

Приоритет: **текстурный скан < API других модов < JSON из ресурспака < блоки, отредактированные игроком вручную в GUI.**

```json
{
  "minecraft:torch":      { "color": [255, 150, 60], "light": 14, "propagation": "colorlight:smooth" },
  "soul_torch":           { "color": "#3fd0ff" },
  "minecraft:lantern":    { "rgb": { "r": 255, "g": 200, "b": 120 } },
  "minecraft:candle":     { "hex": "ffcc66" },
  "#minecraft:candles":   { "light": 12 },
  "minecraft:*_lantern":  { "r": 255, "g": 190, "b": 110 },
  "minecraft:lava":       { "enabled": false }
}
```

**Ключ:** id блока (`minecraft:` можно опустить), тег блоков `#minecraft:candles`, либо шаблон с `*` и `?`.

**Цвет** — любой один вариант, каналы 0..255:

| Формат                 | Пример                                                    |
|------------------------|-----------------------------------------------------------|
| `color`: hex-строка    | `"color": "#ff8800"` (или `"#f80"`, `"ff8800"`)           |
| `color`: массив        | `"color": [255, 136, 0]`                                  |
| `color`: объект        | `"color": {"r": 255, "g": 136, "b": 0}`                   |
| `hex`                  | `"hex": "#ff8800"`                                        |
| `rgb`: массив / объект | `"rgb": [255, 136, 0]` · `"rgb": {"r":255,"g":136,"b":0}` |
| отдельные поля         | `"r": 255, "g": 136, "b": 0`                              |

**Остальные поля** (все необязательны, пропущенное остаётся прежним): `light` 1..15 — потолок излучаемого уровня; `propagation` — id способа распространения; `enabled: false` — выключить цветной свет у блока.

Ошибочная запись пропускается и попадает в `/colorlight scripts`, остальной файл применяется. Теги блоков приходят от сервера после загрузки ресурсов, поэтому правила по тегам применяются при входе в мир.

---

## 3. Настройки — `settings.lua`

```lua
return {
    light_range = 12,
    propagation = "colorlight:smooth",
    tint_gamma  = 0.7,
}
```
(или `colorlight.settings{ ... }`). Значения из пака — **значения по умолчанию**: настройка, которую игрок изменил сам в меню ColorLight, остаётся его; остальные берут значение из пака (при нескольких паках — из верхнего). В файл `colorlight.json` значения пака не записываются, поэтому после отключения пака возвращаются прежние.

| Ключ                                                                                                                                                        | Тип / диапазон                                                             |
|-------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------|
| `enable`, `smooth_lighting`                                                                                                                                 | true/false                                                                 |
| `light_range`                                                                                                                                               | 1..32 (блоков)                                                             |
| `propagation`                                                                                                                                               | id способа для блоков, напр. `"colorlight:grid"`                           |
| `dynamic_propagation`                                                                                                                                       | id способа для движущихся источников (LambDynamicLights); только табличный |
| `tint_gamma`                                                                                                                                                | 0..2                                                                       |
| `brightness_weight`, `local_weight`, `region_weight`, `alpha_weight`, `anomaly_weight`, `saturation_weight`, `glowcolorscore_weight`, `whitepenalty_weight` | 0..100 (веса текстурного сканера)                                          |
| `entity_tracking`, `entity_follow_render_distance`                                                                                                          | true/false                                                                 |
| `entity_radius_chunks`                                                                                                                                      | 2..32                                                                      |

---

## 4. API для других модов (Java)

* `PropagationMethodRegistry.register(method)` — метод из кода; живёт между перезагрузками ресурсов. Идентификаторы `colorlight:grid` / `colorlight:smooth` всегда доступны (Lua-версии подменяют встроенные запасные).
* `ColorLightBlockAPI.register(...)` — цвета блоков по умолчанию (приоритет ниже JSON ресурспака).
* `ColorLightLua.register(name, function)` — добавить свою функцию в таблицу `colorlight` для скриптов.

## 5. Ограничения

* Свет прыгает только на 26 соседних блоков (смещения −1..1).
* Движущиеся (entity) источники используют табличный способ (`dynamic_propagation`); скриптовый `propagate` для них не подходит — используется `colorlight:smooth`.
* Изменение скриптов применяется при перезагрузке ресурсов; при этом свет в загруженном мире пересчитывается.

</details>