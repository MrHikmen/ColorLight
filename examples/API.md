# ColorLight: Data in Resource Packs

Light propagation methods, default settings and block colors are all defined by plain **JSON files in the resource pack** (block colors in `hex` or `rgb` format). The mod provides `grid.json` and `smooth.json` by default — any resource pack can override them or add its own methods.

```text
assets/colorlight/
├── settings.json            default settings
├── propagation/<name>.json  light propagation method, id = colorlight:<name>
└── block/<name>.json        block colors / light level / propagation method
```

Files are loaded every time resources are reloaded (`F3+T`, changing resource packs). A complete example is available in `examples/resourcepack/`. To see which files were loaded and whether any errors occurred, use the `/colorlight scripts` command.

## Safety

A resource pack is downloaded content, so it only ever supplies data: numbers and strings that the mod reads and validates, never code. An error in a file only causes that file to be skipped (the error is recorded in the log and shown by `/colorlight scripts`). Comments `//` and `/* */` and trailing commas are allowed in every file, and keys beginning with `_` are ignored (useful for notes).

---

## 1. Propagation Methods — `propagation/<name>.json`

```json
{
  "name": "Vertical beam",
  "scale": 1,
  "preserve_hue": true,
  "max_opacity": 15,
  "neighbors": [[0,1,0], [0,-1,0], [1,0,0], [-1,0,0], [0,0,1], [0,0,-1]],
  "loss": {
    "vertical_factor": 0.25,
    "horizontal_factor": 2.5
  }
}
```

| Field          | Description                                                                                                                                                                                                      |
| -------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `name`         | Name displayed in the menu (defaults to the method id).                                                                                                                                                          |
| `neighbors`    | **Required.** Where light can spread: `"faces"` — 6 faces, `"full"` — all 26 neighbors, or a list of `[dx,dy,dz]`. Offsets must be within ±1 (this is what allows light to fade when a block is removed).        |
| `scale`        | Precision of intermediate value storage, 1..64 (default `1`). `1` — fast (like a "diamond"), `8` — smooth (like a "circle").                                                                                     |
| `preserve_hue` | Color channels fade together so the hue remains stable (default `true`).                                                                                                                                         |
| `max_opacity`  | Blocks with this opacity or higher stop the light, 1..15 (default `15`).                                                                                                                                         |
| `loss`         | Light loss per step, see below. If omitted: `distance·(1+opacity)·decay`.                                                                                                                                        |

The cost of one step is a small formula whose weights come straight from the file:

```text
units = decay · multiplier · distance · (1 + opacity_factor · opacity) · axis_factor + flat · decay
```

`decay` = 255 / range — light loss per block in air; `opacity` — opacity of the block being entered, 0..15. The result is measured in light units; the minimum after rounding is 1. The formula is evaluated in advance (for each neighbor and each opacity value), so resolving it costs nothing while light is actually spreading.

| `loss` field        | Default | Description                                                     |
| ------------------- | ------- | --------------------------------------------------------------- |
| `distance`          | `false` | `true` — multiply by the real step length `√(dx²+dy²+dz²)`      |
| `opacity_factor`    | `1.0`   | Weight of opacity in `(1 + opacity_factor · opacity)`           |
| `vertical_factor`   | `1.0`   | Extra multiplier for steps with `dy ≠ 0`                        |
| `horizontal_factor` | `1.0`   | Extra multiplier for steps with `dy = 0`                        |
| `multiplier`        | `1.0`   | Overall scale of the whole formula                              |
| `flat`              | `0.0`   | Extra constant cost, in `decay` units                           |

The built-in `grid.json` (6 neighbors, `scale=1`) and `smooth.json` (26 neighbors, `scale=8`, loss proportional to distance) define the two default shapes.

### Selecting a method for a block

In the block JSON: `"propagation": "colorlight:beam"`; the global method is defined by the `propagation` setting.

---

## 2. Blocks — `block/<name>.json`

Standard JSON: the key selects the block(s), and the value describes the light. All `.json` files from `assets/colorlight/block/` across all enabled resource packs are loaded alphabetically (a file with the same name in a higher-priority pack overrides the one in a lower-priority pack; prefixes such as `10_`, `20_` define the order). Later entries override earlier ones **field by field**. Comments `//` and `/* */` are allowed; keys beginning with `_` are ignored (useful for notes).

Priority: **texture scanning < resource pack JSON < blocks manually edited by the player in the GUI.**

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

## 3. Settings — `settings.json`

```json
{
  "light_range": 12,
  "propagation": "colorlight:smooth",
  "tint_gamma": 0.7
}
```

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

## 4. Limitations

* Light can spread only to the 26 neighboring blocks (offsets −1..1).
* Moving (entity) light sources use `dynamic_propagation` (`colorlight:smooth` by default).
* Changes take effect after resources are reloaded; light in the loaded world is recalculated at that point.

<details>
<summary>Rus</summary>

# ColorLight: данные в ресурспаках

Способ распространения света, настройки по умолчанию и цвета блоков описываются обычными **JSON-файлами в ресурспаке** (цвет в `hex` или `rgb`). Сам мод поставляет `grid.json` и `smooth.json` — любой ресурспак может их заменить или добавить свои.

```
assets/colorlight/
├── settings.json            настройки по умолчанию
├── propagation/<имя>.json   способ распространения света, id = colorlight:<имя>
└── block/<имя>.json         цвета / сила / способ для блоков
```

Файлы читаются при каждой загрузке ресурсов (`F3+T`, смена паков). Готовый пример — `examples/resourcepack/`. Посмотреть, что загрузилось и какие ошибки: команда `/colorlight scripts`.

## Безопасность

Ресурспак — это скачанный контент, поэтому он поставляет только данные: числа и строки, которые мод читает и проверяет, но никогда не код. Ошибка в файле лишь пропускает этот файл (в лог и в `/colorlight scripts`). Во всех файлах разрешены комментарии `//` и `/* */` и висячие запятые, а ключи, начинающиеся с `_`, игнорируются (удобно для заметок).

---

## 1. Способы распространения — `propagation/<имя>.json`

```json
{
  "name": "Vertical beam",
  "scale": 1,
  "preserve_hue": true,
  "max_opacity": 15,
  "neighbors": [[0,1,0], [0,-1,0], [1,0,0], [-1,0,0], [0,0,1], [0,0,-1]],
  "loss": {
    "vertical_factor": 0.25,
    "horizontal_factor": 2.5
  }
}
```

| Поле           | Значение                                                                                                                                                                                                   |
| -------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `name`         | Название в меню (по умолчанию — id способа).                                                                                                                                                               |
| `neighbors`    | **Обязательно.** Куда свет прыгает: `"faces"` — 6 граней, `"full"` — все 26 соседей, либо список `[dx,dy,dz]`. Смещения только в пределах ±1 (на этом держится «гашение» света при удалении блока).         |
| `scale`        | Точность хранения промежуточного значения, 1..64 (по умолчанию `1`). `1` — быстро (как «ромб»), `8` — гладко (как «круг»).                                                                                  |
| `preserve_hue` | Каналы цвета гаснут вместе, оттенок не плывёт (по умолчанию `true`).                                                                                                                                       |
| `max_opacity`  | Блок с такой непрозрачностью и выше останавливает свет, 1..15 (по умолчанию `15`).                                                                                                                         |
| `loss`         | Потеря света за один прыжок, см. ниже. Если не задан: `расстояние·(1+opacity)·decay`.                                                                                                                      |

Цена одного прыжка — небольшая формула, веса которой берутся прямо из файла:

```text
units = decay · multiplier · distance · (1 + opacity_factor · opacity) · axis_factor + flat · decay
```

`decay` = 255 / дальность — потеря на блок в воздухе; `opacity` — затемнение входимого блока 0..15. Результат в единицах света; минимум после округления — 1. Формула считается заранее (для каждого соседа и каждой непрозрачности), поэтому во время самой заливки света она ничего не стоит.

| Поле `loss`         | По умолчанию | Значение                                                      |
| ------------------- | ------------ | ------------------------------------------------------------- |
| `distance`          | `false`      | `true` — умножать на реальную длину прыжка `√(dx²+dy²+dz²)`   |
| `opacity_factor`    | `1.0`        | Вес непрозрачности в `(1 + opacity_factor · opacity)`         |
| `vertical_factor`   | `1.0`        | Доп. множитель для прыжков с `dy ≠ 0`                         |
| `horizontal_factor` | `1.0`        | Доп. множитель для прыжков с `dy = 0`                         |
| `multiplier`        | `1.0`        | Общий масштаб всей формулы                                    |
| `flat`              | `0.0`        | Дополнительная постоянная цена, в единицах `decay`            |

Встроенные `grid.json` (6 соседей, `scale=1`) и `smooth.json` (26 соседей, `scale=8`, потеря пропорциональна расстоянию) задают две формы по умолчанию.

### Выбор способа для блока

В JSON блока: `"propagation": "colorlight:beam"`; глобальный способ задаётся настройкой `propagation`.

---

## 2. Блоки — `block/<имя>.json`

Обычный JSON: ключ выбирает блок(и), значение описывает свет. Читаются все `.json` из `assets/colorlight/block/` всех включённых паков, по алфавиту (файл с тем же именем в паке выше заменяет файл нижнего; префиксы `10_`, `20_` задают порядок). Более поздние записи переопределяют ранние **по полям**. Комментарии `//` и `/* */` терпятся; ключи, начинающиеся с `_`, игнорируются (удобно для заметок).

Приоритет: **текстурный скан < JSON из ресурспака < блоки, отредактированные игроком вручную в GUI.**

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

## 3. Настройки — `settings.json`

```json
{
  "light_range": 12,
  "propagation": "colorlight:smooth",
  "tint_gamma": 0.7
}
```

Значения из ресурспака — **значения по умолчанию**: настройка, которую игрок изменил вручную в меню ColorLight, остаётся как есть; все остальные берутся из пака (при нескольких включённых паках побеждает пак с большим приоритетом). Значения пака не записываются в `colorlight.json`, поэтому после отключения пака прежние значения возвращаются.

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

## 4. Ограничения

* Свет прыгает только на 26 соседних блоков (смещения −1..1).
* Движущиеся (entity) источники используют `dynamic_propagation` (по умолчанию `colorlight:smooth`).
* Изменения применяются при перезагрузке ресурсов; при этом свет в загруженном мире пересчитывается.

</details>
