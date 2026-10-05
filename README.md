# ColorLight — What Changed (3 commits)

A developer-focused breakdown of three commits in `ColorLight`: what changed technically and how to use/account for it.

---

## 1. `57eee4a` — Shader GPU pipeline

**Summary:** adds an alternative way to light the terrain — instead of baking colour into the chunk mesh (the old way), a **GPU shader** now reads colour directly from a "light volume" on the GPU, per pixel.

### New classes
- `client/gpu/ColorLightGpu.java` — core of the GPU pipeline: tick, shutdown, active flag (`isActive`), reacting to changed sections (`onSectionsChanged`), mesh-rebuild-pending status (`meshRebuildPending` / `meshRebuildDone`).
- `client/gpu/GpuLightVolume.java` — the actual GPU-side light buffer.
- `client/gpu/SodiumHookMarkers.java` — integration hook points.
- `client/mixin/sodium/DefaultChunkRendererMixin.java`, `ShaderChunkRendererMixin.java`, `SodiumWorldRendererMixin.java` — mixins wiring the shader into Sodium's renderer.
- Shaders: `assets/colorlight/shaders/blocks/colored_terrain.fsh/.vsh`, `include/colorlight_data.glsl`.

### New config options (`ColorLightConfig.java`)
```java
public boolean GPU_PIPELINE = true;      // enables the shader path instead of mesh baking
public int GPU_LIGHT_SECTIONS = 4096;    // how many 16×16×16 light sections the GPU light volume holds (16 KiB each, range 64..4096)
```
Only works with the Sodium version the mod was built against; otherwise it falls back to vertex colours. Switchable at runtime, but ignored while a shader pack is active.

Also changed the `TINT_GAMMA` default: `0.55f → 0.0f`.

### API additions in `ColorLightEngine` / `LightStorage`
These were added for the GPU pipeline (and later reused by the Voxy commit below):

```java
// ColorLightEngine
void forEachLitSection(LongConsumer consumer);           // walk every lit section (static + dynamic light)
boolean copySectionColors(int sx, int sy, int sz, int[] out); // 4096 ints, index (y<<8)|(z<<4)|x
void markEverythingDirty();                                // flags every lit section + neighbours for a mesh rebuild

// LightStorage
boolean copySection(int sx, int sy, int sz, int[] out);
boolean mergeMaxInto(int sx, int sy, int sz, int[] out);    // per-channel max merge into an existing array
```

### What this means for developers
- If your code depends on "did the chunk mesh get rebuilt because light changed" — note that with `GPU_PIPELINE = true`, **meshes are no longer rebuilt** on ordinary light changes (`ColorLightDirtyFlusher` now skips queueing a mesh rebuild for most sections while the GPU pipeline is active, except right when the pipeline is toggled on/off).
- `ColorLightDaylightRefresher` is a no-op whenever `ColorLightGpu.isActive()` — daylight is mixed in inside the shader instead.
- If you need to read the current light colour of a section (e.g. for your own companion mod/integration), use `engine.copySectionColors(sx, sy, sz, out)` rather than reaching into `LightStorage` internals directly.

---

## 2. `d4bb46d` — Voxy support again

**Summary:** restores support for **Voxy** (a far-chunk LOD renderer), plus a new "far light" subsystem that keeps a simplified light map for chunks beyond normal render distance and survives game restarts.

### New classes — `client/compat/lod/`
- `ColorLightVoxyCompat.java` — just checks `FabricLoader.isModLoaded("voxy")`.
- `LodLight.java` — owner of the current dimension's light map:
    - creates a `LodLightMap` on world join / dimension change;
    - feeds it from the engine every tick (`tick`, budget 1.5 ms/tick);
    - does a full rescan via `engine.forEachLitSection(...)` the first time a new engine is seen (40-tick delay);
    - autosaves every 6000 ticks (5 min) to `<game dir>/colorlight/lodlight/<uuid>.bin`, where `uuid` is a hash of (world + dimension) — a separate file per world+dimension, singleplayer and server (by IP) kept apart.
- `LodLightMap.java`, `VoxyLightBridge.java` — the map itself and the bridge into Voxy.
- `client/mixin/ColorLightVoxyPlugin.java`, `client/mixin/voxy/ShaderLoaderMixin.java`, `colorlight.voxy.mixins.json` — integration glue.
- Shader `assets/colorlight/shaders/voxy/colorlight_lod.glsl`.

### Lifecycle wiring (`ColorLightClient.java`)
```java
ClientTickEvents.END_CLIENT_TICK.register(LodLight::tick);
ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
    ColorLightGpu.shutdown();
    LodLight.shutdown(); // saves the map before exit
});
```

### What this means for developers
- The feature is only active when Voxy is present — gated through `ColorLightVoxyCompat.isPresent()`, so without Voxy on the classpath/runtime this code path simply never engages.
- `LodLight.markDirty(long[] sectionKeys)` is the extension point if you need to tell the LOD-light subsystem that specific sections changed (already wired into the general dirty-section pipeline).
- If you're building a mod that needs to work both with and without Voxy, don't touch `LodLightMap` directly — use `LodLight.map()` (can be `null` outside a world) and `LodLight`'s public methods.
- The map file format is a custom binary (`.bin`) — version it carefully if you intend to parse it externally.

---

## 3. `409d22d` — minor edits (refactor, no new behaviour)

Purely internal cleanup; behaviour is unchanged, but relevant if you're contributing to this codebase.

### Colour-picker widget refactor (`config/gui/screen/`)
A shared abstract class was introduced:
```java
abstract class ColorPickerWidget extends AbstractWidget {
    protected final ColorPickerMath color;
    protected final Runnable onChange;
    protected abstract void applyFromMouse(MouseButtonEvent event);
    // onClick/onDrag/narration are implemented here once
}
```
`HueBarWidget` and `ColorSquareWidget` now extend it instead of duplicating `onClick`/`onDrag`/`updateWidgetNarration`. **If you have third-party code extending `HueBarWidget`/`ColorSquareWidget` directly, or calling their former private methods — check compatibility.** Public constructors are preserved, but the class hierarchy changed.

### Blockstate model-parsing refactor
A new `ModelListJson.collectModels(JsonElement applyOrVariant, List<Identifier> out)` extracts the shared logic for parsing `variants`/`multipart.apply` (a single object or an array of objects → a list of `model` identifiers), which was previously duplicated in `VariantParser` and `MultipartParser`. Both now just delegate to `ModelListJson`.

### New `AbstractGateMixinPlugin`
```java
abstract class AbstractGateMixinPlugin implements IMixinConfigPlugin {
    protected abstract boolean decide(); // whether to enable the whole mixin set
    // the rest of IMixinConfigPlugin is a no-op, implemented once and for all
}
```
`ColorLightMixinPlugin` and `ColorLightVoxyPlugin` (from the Voxy commit above) now both extend this class and implement only `decide()`, instead of each implementing the full `IMixinConfigPlugin` interface separately.

### Misc
- `mod_test`: `2 → 3` (internal test-build counter, not part of the public API).
- Blank-line/comment cleanup in `ColorLightConfig.java`.
- `Translatable.pluralValue` got an explanatory javadoc about Russian plural forms (`.1`, the `-5` suffix form, etc.) with a fallback to the base key.

### What this means for developers
- If you're writing your own `IMixinConfigPlugin` for ColorLight compatibility, `AbstractGateMixinPlugin` is a good pattern to follow: "mixins are toggled by a single flag decided at `onLoad`, no per-mixin filtering."
- If you parse blockstate JSON for your own purposes, `ModelListJson.collectModels` is now the single place implementing "object or array of objects → list of model ids" — useful if you're extending `VariantParser`/`MultipartParser`.

---

## Summary table

| Commit | Adds | Breaking changes |
|---|---|---|
| `57eee4a` | GPU shader lighting pipeline, new config options `GPU_PIPELINE`/`GPU_LIGHT_SECTIONS` | None, but changes mesh-rebuild behaviour when enabled |
| `d4bb46d` | Voxy support + "far light" subsystem with disk persistence | None, purely additive, gated by Voxy's presence |
| `409d22d` | Refactor (shared base class for colour-picker widgets, shared blockstate model parser, shared base class for mixin plugins) | Yes, to the class hierarchy of `HueBarWidget`/`ColorSquareWidget` and the contract of custom `IMixinConfigPlugin`s if anyone extended them directly |
---

<details>
<summary>Rus</summary>

# ColorLight — что изменилось (3 коммита)

Разбор трёх коммитов в `ColorLight`: что поменялось технически и как это использовать/учитывать при разработке.

---

## 1. `57eee4a` — Shader GPU pipeline

**Суть:** добавлен альтернативный способ освещения террейна — не через запекание цвета в меш чанка (как раньше), а через **GPU-шейдер**, который читает цвет напрямую из "светового тома" (light volume) на GPU, попиксельно.

### Новые классы
- `client/gpu/ColorLightGpu.java` — ядро GPU-пайплайна: тик, выключение (`shutdown`), флаг активности (`isActive`), реакция на изменившиеся секции (`onSectionsChanged`), статус ожидания ребилда мешей (`meshRebuildPending` / `meshRebuildDone`).
- `client/gpu/GpuLightVolume.java` — сам буфер освещения на GPU.
- `client/gpu/SodiumHookMarkers.java` — точки интеграции с Sodium.
- `client/mixin/sodium/DefaultChunkRendererMixin.java`, `ShaderChunkRendererMixin.java`, `SodiumWorldRendererMixin.java` — миксины, подключающие шейдер к рендереру Sodium.
- Шейдеры: `assets/colorlight/shaders/blocks/colored_terrain.fsh/.vsh`, `include/colorlight_data.glsl`.

### Новые настройки (`ColorLightConfig.java`)
```java
public boolean GPU_PIPELINE = true;      // включает шейдерный путь вместо запекания в меш
public int GPU_LIGHT_SECTIONS = 4096;    // сколько секций 16×16×16 влезает в GPU light volume (16 KiB на секцию, диапазон 64..4096)
```
Работает только с той версией Sodium, под которую писали мод; иначе — fallback на вершинные цвета. Переключается на лету, но игнорируется при активном шейдерпаке.

Заодно поменяли дефолт `TINT_GAMMA`: `0.55f → 0.0f`.

### API в `ColorLightEngine` / `LightStorage`
Добавлены методы, нужные GPU-пайплайну (и переиспользованные позже в коммите #2 для Voxy):

```java
// ColorLightEngine
void forEachLitSection(LongConsumer consumer);           // пройтись по всем освещённым секциям (статика + динамика)
boolean copySectionColors(int sx, int sy, int sz, int[] out); // 4096 int'ов, индекс (y<<8)|(z<<4)|x
void markEverythingDirty();                                // пометить все секции + соседей на ребилд меша

// LightStorage
boolean copySection(int sx, int sy, int sz, int[] out);
boolean mergeMaxInto(int sx, int sy, int sz, int[] out);    // покомпонентный max в уже существующий массив
```

### Как это использовать для разработчиков
- Если пишете код, который зависит от того, "перестроен ли меш чанка из-за света" — учитывайте, что при `GPU_PIPELINE = true` **мешы вообще не ребилдятся** при обычных изменениях освещения (`ColorLightDirtyFlusher` теперь при активном GPU-пайплайне не кладёт секции в очередь ребилда, кроме момента включения/выключения пайплайна).
- `ColorLightDaylightRefresher` при `ColorLightGpu.isActive()` просто ничего не делает (дневной свет подмешивается в шейдере).
- Если нужно прочитать актуальный цвет освещения секции (для своей интеграции/мода-компаньона) — используйте `engine.copySectionColors(sx, sy, sz, out)`, а не лезьте во внутренности `LightStorage` напрямую.

---

## 2. `d4bb46d` — Voxy support again

**Суть:** возвращена поддержка мода **Voxy** (LOD-рендерер дальних чанков) — плюс новая подсистема "far light" (LOD-освещение), которая хранит упрощённую карту освещения для чанков вне обычной дальности прогрузки и переживает перезапуск игры.

### Новые классы — `client/compat/lod/`
- `ColorLightVoxyCompat.java` — просто проверка `FabricLoader.isModLoaded("voxy")`.
- `LodLight.java` — владелец карты освещения текущего измерения:
    - создаёт `LodLightMap` при входе в мир/смене измерения;
    - раз в тик (`tick`, бюджет 1.5 мс/тик) подпитывает карту данными из движка;
    - при первом тике нового engine делает полный пересчёт через `engine.forEachLitSection(...)` (задержка 40 тиков);
    - автосохранение каждые 6000 тиков (5 мин) в `<game dir>/colorlight/lodlight/<uuid>.bin`, где `uuid` — хеш от (мир + измерение), т.е. отдельный файл на мир+дименшен, для одиночной игры и для сервера (по IP) — раздельно.
- `LodLightMap.java`, `VoxyLightBridge.java` — сама карта и мост к Voxy.
- `client/mixin/ColorLightVoxyPlugin.java`, `client/mixin/voxy/ShaderLoaderMixin.java`, `colorlight.voxy.mixins.json` — интеграция.
- Шейдер `assets/colorlight/shaders/voxy/colorlight_lod.glsl`.

### Интеграция в жизненный цикл (`ColorLightClient.java`)
```java
ClientTickEvents.END_CLIENT_TICK.register(LodLight::tick);
ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
    ColorLightGpu.shutdown();
    LodLight.shutdown(); // сохраняет карту перед выходом
});
```

### Как это использовать для разработчиков
- Фича активна только при наличии Voxy — гейтится через `ColorLightVoxyCompat.isPresent()`, так что без Voxy в classpath/рантайме код просто не включается.
- `LodLight.markDirty(long[] sectionKeys)` — точка расширения, если нужно сообщить подсистеме LOD-света, что конкретные секции изменились (уже вызывается из общего пайплайна грязных секций).
- Если собираете мод, совместимый и с обычным режимом, и с Voxy — не трогайте `LodLightMap` напрямую, используйте `LodLight.map()` (может быть `null` вне мира) и публичные методы `LodLight`.
- Формат файла карты — бинарный, свой (`.bin`), версионируйте осторожно, если будете его парсить снаружи.

---

## 3. `409d22d` — minor edits (рефакторинг без новых фич)

Чисто внутренняя чистка, поведение не меняется, но важно знать, если делаете PR в этот код.

### Рефакторинг виджетов цвета (`config/gui/screen/`)
Добавлен общий абстрактный класс:
```java
abstract class ColorPickerWidget extends AbstractWidget {
    protected final ColorPickerMath color;
    protected final Runnable onChange;
    protected abstract void applyFromMouse(MouseButtonEvent event);
    // onClick/onDrag/narration реализованы здесь один раз
}
```
`HueBarWidget` и `ColorSquareWidget` теперь наследуются от него вместо дублирования `onClick`/`onDrag`/`updateWidgetNarration`. **Если у вас есть сторонний код, наследующийся напрямую от старых `HueBarWidget`/`ColorSquareWidget` или вызывающий их приватные методы — проверьте совместимость**, публичные конструкторы сохранены, но иерархия изменилась.

### Рефакторинг парсинга blockstate-моделей
Новый класс `ModelListJson.collectModels(JsonElement applyOrVariant, List<Identifier> out)` — вынесена общая логика разбора `variants`/`multipart.apply` (объект или массив объектов → список `model`-идентификаторов), которая раньше была продублирована в `VariantParser` и `MultipartParser`. Теперь оба просто делегируют в `ModelListJson`.

### Новый `AbstractGateMixinPlugin`
```java
abstract class AbstractGateMixinPlugin implements IMixinConfigPlugin {
    protected abstract boolean decide(); // включать ли весь набор миксинов целиком
    // остальные методы IMixinConfigPlugin — no-op, реализованы раз и навсегда
}
```
`ColorLightMixinPlugin` и `ColorLightVoxyPlugin` (из коммита #2) теперь оба должны наследоваться от этого класса и реализовывать только `decide()`, вместо реализации всего интерфейса `IMixinConfigPlugin` по отдельности.

### Прочее
- `mod_test`: `2 → 3` (внутренний счётчик версии для тестовой сборки, не для API).
- Чистка пустых строк/комментариев в `ColorLightConfig.java`.
- `Translatable.pluralValue` — добавлен поясняющий javadoc про русские формы множественного числа (`.1`, суффикс `-5` и т.д.) с фолбэком на базовый ключ.

### Как это использовать для разработчиков
- Если пишете свой `IMixinConfigPlugin` для совместимости с ColorLight — паттерн `AbstractGateMixinPlugin` можно взять за образец "миксины включаются одним флагом на этапе `onLoad`, без per-mixin фильтрации".
- При парсинге blockstate JSON для своих нужд — `ModelListJson.collectModels` теперь единая точка для логики "объект или массив объектов → список моделей", полезно, если расширяете `VariantParser`/`MultipartParser`.

---

## Итоговая сводка по порядку коммитов

| Коммит | Что добавляет | Breaking changes |
|---|---|---|
| `57eee4a` | GPU-шейдерный пайплайн освещения террейна, новые опции конфига `GPU_PIPELINE`/`GPU_LIGHT_SECTIONS` | Нет, но меняет логику ребилда мешей при включённой опции |
| `d4bb46d` | Поддержка Voxy + подсистема "far light" с сохранением на диск | Нет, чисто аддитивно, гейтится наличием Voxy |
| `409d22d` | Рефакторинг (общий базовый класс для color-picker виджетов, общий парсер blockstate-моделей, общий базовый класс для mixin-плагинов) | Да, по иерархии классов `HueBarWidget`/`ColorSquareWidget` и контракту кастомных `IMixinConfigPlugin`, если кто-то их наследовал напрямую |

</details>

---