# ColorLight shader GPU pipeline — What Changed

A developer-focused breakdown of three commits in `ColorLight`: what changed technically and how to use/account for it.

---

## 1. Shader GPU pipeline

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

## 2. Voxy support again

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

## 3. Minor edits (refactor, no new behaviour)

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

## 4. Far light now reads Voxy directly, no more disk persistence

**Summary:** `LodLightMap` (ColorLight's own scanned-and-saved copy of distant light, `<game dir>/colorlight/lodlight/*.bin`) is gone. Far light is now read straight from Voxy's own, already-persistent voxel storage (`WorldEngine`/`WorldSection`/`Mapper`) every time it's needed, memoised only in memory for the session. Also reworked the distance/cell-size table: near field is now full 1-block resolution instead of 4-block, and the coarsening chain now goes out to 128-block cells instead of stopping at 32.

### New/changed classes — `client/compat/lod/`
- `VoxyLightCache.java` **(new)** — replaces `LodLightMap.java` (deleted). For each visible cell, walks straight down a Voxy voxel column (`WorldEngine.acquireIfExists(level, x, y, z)` → `WorldSection._unsafeGetRawDataArray()` → `Mapper.getBlockStateFromBlockId(...)`) and asks `ColorLightBlockRegistry` whether that block is a configured light source, same as a normal chunk scan. No own save format, no `engine.forEachLitSection` rescan, no autosave timer - the "file" is just Voxy's world, which already persists forever. Work that touches Voxy's section storage (and so can block on IO) runs on its own background thread, budgeted per tick; `fillGrid` (called from the render thread) only ever reads the already-filled, thread-safe in-memory maps.
- `LodLight.java` — same public shape (`cache()`/`markDirty()`/`tick()`/`shutdown()`, `markDirty` is now a no-op kept for source compatibility), but owns a `VoxyLightCache` instead of a `LodLightMap`, and has **zero Voxy imports of its own** on purpose: `ColorLightClient` ticks it unconditionally, Voxy or not, so everything Voxy-specific has to stay behind `ColorLightVoxyCompat.isPresent()` and inside `VoxyLightCache` (which `VoxyCommon`/`WorldEngine`/etc. is only ever loaded once that's confirmed true).
- `VoxyLightBridge.java` — `SECTIONS` 4 → 7 (one per `VoxyLightCache.CELL_BLOCKS` tier); reads `VoxyLightCache.fillGrid`/`version` instead of `LodLightMap.fillGrid`/`fillFullGrid`.
- `assets/colorlight/shaders/voxy/colorlight_lod.glsl` — new 7-way distance table.

### New distance/cell-size table (`colorlight_lod.glsl`, `VoxyLightCache.CELL_BLOCKS`)
| Distance from camera | Cell size | Voxy LOD level used |
|---|---|---|
| 0 - 700 blocks | 1×1×1 | 0 (Voxy's own finest) |
| 700 - 1300 | 4×4×4 | 2 |
| 1300 - 1600 | 8×8×8 | 3 |
| 1600 - 1900 | 16×16×16 | 4 (Voxy's own coarsest) |
| 1900 - 2200 | 32×32×32 | 4, 2×2×2 voxels grouped |
| 2200 - 2500 | 64×64×64 | 4, 4×4×4 voxels grouped |
| 2500+ | 128×128×128 (capped) | 4, 8×8×8 voxels grouped |

Voxy itself only stores voxels down to 16 blocks/voxel (`WorldEngine.MAX_LOD_LAYER = 4`), so the three coarsest tiers don't correspond to a real Voxy level - they group several of Voxy's level-4 voxels per output cell and keep the brightest, same "top of column wins" rule as before.

### What this means for developers
- `LodLightMap` no longer exists; nothing reads or writes `<game dir>/colorlight/lodlight/` any more. If you had tooling that parsed that `.bin` format, it has nothing to read now.
- Far light for a spot the player has never actually had loaded in Voxy (never imported/explored) is simply absent (same as before - this was never a prediction, only a memory of blocks Voxy actually has).
- A distant light source changing (e.g. a player breaks a torch 1000 blocks away, in an area Voxy keeps updated) is only reflected once its cell's memo entry is recomputed; this build does not hook Voxy's own dirty-section callback (that callback has exactly one slot and Voxy's own renderer already owns it - stealing it would break Voxy's rendering), so for now that happens on a world rejoin or `clear()`, not live. Flagged as a known limitation, not silently glossed over.
- `VoxyLightCache` directly imports Voxy's `WorldEngine`/`WorldSection`/`Mapper`/`VoxyCommon` - unlike `VoxyLightBridge`'s shader patching, this is a real compile-time API dependency (already declared `compileOnly` in `build.gradle`). Keep all Voxy-typed method signatures inside `VoxyLightCache` itself (or another class only reached behind `ColorLightVoxyCompat.isPresent()`); never add a Voxy-typed field or method signature to `LodLight` or anything else that's loaded unconditionally.

---

<details>
<summary>Rus</summary>

# ColorLight shader GPU pipeline — что изменилось

Разбор трёх коммитов в `ColorLight`: что поменялось технически и как это использовать/учитывать при разработке.

---

## 1. Shader GPU pipeline

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

## 2. Voxy support again

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

## 3. Minor edits (рефакторинг без новых фич)

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

## 4. Far light теперь читается напрямую из Voxy, без сохранения на диск

**Суть:** `LodLightMap` (собственная сканированная и сохраняемая копия дальнего света, `<game dir>/colorlight/lodlight/*.bin`) убрана. Дальний свет теперь каждый раз читается напрямую из собственного, уже персистентного вокселя-хранилища Voxy (`WorldEngine`/`WorldSection`/`Mapper`), с мемоизацией только в памяти на время сессии. Заодно переработана таблица дистанция/размер ячейки: ближняя зона теперь полное разрешение 1 блок вместо 4, а огрубление идёт до ячеек 128 блоков вместо остановки на 32.

### Новые/изменённые классы — `client/compat/lod/`
- `VoxyLightCache.java` **(новый)** — заменяет `LodLightMap.java` (удалён). Для каждой видимой ячейки идёт прямо вниз по вокселя-колонке Voxy (`WorldEngine.acquireIfExists(level, x, y, z)` → `WorldSection._unsafeGetRawDataArray()` → `Mapper.getBlockStateFromBlockId(...)`) и спрашивает у `ColorLightBlockRegistry`, является ли этот блок настроенным источником света — так же, как обычное сканирование чанка. Нет своего формата сохранения, нет пересканирования через `engine.forEachLitSection`, нет таймера автосохранения — "файлом" выступает сам мир Voxy, который и так хранится вечно. Работа, трогающая хранилище секций Voxy (а значит, потенциально блокирующая на IO), идёт в отдельном фоновом потоке с бюджетом на тик; `fillGrid` (вызывается из render-потока) только читает уже заполненные потокобезопасные карты в памяти.
- `LodLight.java` — та же публичная форма (`cache()`/`markDirty()`/`tick()`/`shutdown()`, `markDirty` теперь no-op, оставлен для совместимости исходников), но владеет `VoxyLightCache` вместо `LodLightMap`, и намеренно **не содержит ни одного импорта Voxy**: `ColorLightClient` тикает его безусловно, с Voxy или без, так что всё специфичное для Voxy должно оставаться за `ColorLightVoxyCompat.isPresent()` и внутри `VoxyLightCache` (куда `VoxyCommon`/`WorldEngine`/и т.д. подгружаются только после подтверждения этого условия).
- `VoxyLightBridge.java` — `SECTIONS` 4 → 7 (по одной на каждый уровень `VoxyLightCache.CELL_BLOCKS`); читает `VoxyLightCache.fillGrid`/`version` вместо `LodLightMap.fillGrid`/`fillFullGrid`.
- `assets/colorlight/shaders/voxy/colorlight_lod.glsl` — новая таблица дистанций на 7 уровней.

### Новая таблица дистанция/размер ячейки (`colorlight_lod.glsl`, `VoxyLightCache.CELL_BLOCKS`)
| Дистанция от камеры | Размер ячейки | Используемый LOD-уровень Voxy |
|---|---|---|
| 0 - 700 блоков | 1×1×1 | 0 (собственный самый точный уровень Voxy) |
| 700 - 1300 | 4×4×4 | 2 |
| 1300 - 1600 | 8×8×8 | 3 |
| 1600 - 1900 | 16×16×16 | 4 (собственный самый грубый уровень Voxy) |
| 1900 - 2200 | 32×32×32 | 4, группировка 2×2×2 вокселей |
| 2200 - 2500 | 64×64×64 | 4, группировка 4×4×4 вокселей |
| 2500+ | 128×128×128 (потолок) | 4, группировка 8×8×8 вокселей |

Сам Voxy хранит воксели только до 16 блоков/воксель (`WorldEngine.MAX_LOD_LAYER = 4`), поэтому три самых грубых уровня не соответствуют реальному уровню Voxy — они группируют несколько вокселей уровня 4 Voxy на одну выходную ячейку и берут самый яркий, то же правило "верх колонки побеждает", что и раньше.

### Как это использовать для разработчиков
- `LodLightMap` больше не существует; ничто не читает и не пишет `<game dir>/colorlight/lodlight/`. Если у вас были инструменты, парсящие этот `.bin`-формат — парсить больше нечего.
- Дальний свет для места, которое игрок никогда реально не прогружал в Voxy (не импортировано/не исследовано), просто отсутствует (как и раньше — это никогда не было предсказанием, только памятью о блоках, которые Voxy реально знает).
- Изменение удалённого источника света (например, игрок ломает факел в 1000 блоках, в области, которую Voxy продолжает обновлять) отражается только после пересчёта мемо-записи этой ячейки; эта сборка не цепляется за собственный dirty-колбэк Voxy (у него ровно один слот, и им уже владеет собственный рендерер Voxy — забрать его значило бы сломать рендер Voxy), так что пока это происходит при повторном входе в мир или `clear()`, а не "вживую". Это явное известное ограничение, а не замалчиваемое.
- `VoxyLightCache` напрямую импортирует `WorldEngine`/`WorldSection`/`Mapper`/`VoxyCommon` из Voxy — в отличие от патчинга шейдера в `VoxyLightBridge`, это настоящая compile-time зависимость от API (уже объявлена как `compileOnly` в `build.gradle`). Держите все Voxy-типизированные сигнатуры методов внутри самого `VoxyLightCache` (или другого класса, до которого можно дойти только за `ColorLightVoxyCompat.isPresent()`); никогда не добавляйте Voxy-типизированное поле или сигнатуру метода в `LodLight` или что-либо ещё, что грузится безусловно.

</details>

---