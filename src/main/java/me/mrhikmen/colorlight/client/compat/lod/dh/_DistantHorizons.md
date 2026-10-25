# Distant Horizons

**What it does:** Enables colored lighting for Distant Horizons' distant LOD terrain, read from Distant Horizons'
own saved data through its public `DhApi`, not just the client's loaded chunks.

**How it works** (via `colorlight.mixins.json`, applied when Distant Horizons 3.x is installed):
- `GlShaderMixin` + `DhLightBridge.patch`: Patches the terrain shader source code DH compiles (`vert.vert` gets two
  small varyings added for the sky/block light levels it already computes, `frag.frag` gets `colorlight_dh.glsl`
  added and one line in `main()` changed). If any expected anchor is not found in either file, that file is left
  unchanged and far light colors stay off.
- `DhLightBridge.onFrame`: Updates and binds an SSBO containing a light grid once per frame (same binary layout and
  distance-based fade as the Voxy compat's `colorlight_lod.glsl`, independent buffer/binding).
- `DhLightCache` / `DhLodLight`: The light source list for distant terrain. Built on a background thread by reading
  `DhApi.Delayed.terrainRepo.getAllTerrainDataAtChunkPos` for the area around the camera (nearest first, 1024-block
  radius), which is DH's own stored LOD data - so it reaches as far as DH's data goes, not just the client's render
  distance. There is no "a chunk changed" event in the public API, so a finished sweep is simply repeated every
  20 seconds instead of being nudged by a hint. Saved to `<game dir>/colorlight/lodlight/<uuid>.bin (shared with Voxy)` every 30 seconds
  (a separate file per world and dimension).

**Configuration:** `DH_LIGHT` (enabled by default).

**Limitations:** A placed or removed light source can take up to ~20 seconds to show up or disappear on DH's LODs
(no incremental "changed chunk" hint exists through the public API, unlike the Voxy compat). The source's vertical
position is the middle of the LOD data point's run, which is an approximation at the detail levels DH uses far away.

**Disable:** `-Dcolorlight.noDistantHorizons=true`; `-Dcolorlight.forceDistantHorizons=true` applies the patch to
any Distant Horizons version.

---

<details>
<summary>Rus</summary>

**Что даёт:** цветной свет на дальнем LOD-террейне Distant Horizons, читается из собственных сохранённых данных DH
через публичный `DhApi`, а не только по загруженным клиентом чанкам.

**Как устроено** (`colorlight.mixins.json`, применяется при установленном Distant Horizons 3.x):
- `GlShaderMixin` + `DhLightBridge.patch`: правит текст шейдеров террейна, которые компилирует DH (`vert.vert`
  получает две небольшие varying-переменные с уже посчитанными уровнями sky/block light, `frag.frag` получает
  добавленный `colorlight_dh.glsl` и одну изменённую строку в `main()`). Если в любом из файлов не найден ожидаемый
  якорь, этот файл не меняется и цветной дальний свет остаётся выключенным.
- `DhLightBridge.onFrame`: раз в кадр обновляет и привязывает SSBO с сеткой света (тот же бинарный формат и
  линейное затухание по расстоянию, что и в `colorlight_lod.glsl` для Voxy, но отдельный буфер/binding).
- `DhLightCache` / `DhLodLight`: список источников света для дальнего террейна. Строится в фоновом потоке через
  `DhApi.Delayed.terrainRepo.getAllTerrainDataAtChunkPos` вокруг камеры (сначала ближние, радиус 1024 блока) - это
  собственные сохранённые LOD-данные DH, так что свет действует настолько далеко, насколько у DH есть данные, а не
  только в пределах дальности прорисовки клиента. В публичном API нет события «чанк изменился», поэтому вместо
  точечного обновления по подсказке законченный проход просто повторяется каждые 20 секунд. Сохраняется в
  `<game dir>/colorlight/lodlight/<uuid>.bin (общий файл с Voxy)` каждые 30 секунд (отдельный файл на мир и измерение).

**Настройка:** `DH_LIGHT` (по умолчанию включено).

**Ограничения:** появление или исчезновение источника света на LOD DH может занять до ~20 секунд (в отличие от
компата Voxy, точечной подсказки «чанк изменился» в публичном API нет). Высота источника - середина диапазона
точки LOD-данных, что на дальних уровнях детализации DH является приближением.

**Отключить:** `-Dcolorlight.noDistantHorizons=true`; `-Dcolorlight.forceDistantHorizons=true` применяет патч к
любой версии Distant Horizons.

</details>

---
