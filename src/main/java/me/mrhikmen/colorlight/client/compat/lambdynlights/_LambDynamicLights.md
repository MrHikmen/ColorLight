# LambDynamicLights

**What it does:** Provides dynamic colored lighting from entities that hold or are themselves associated with a light-emitting block (held items, dropped items, item frames, minecarts, and mobs).

**How it works:**
- The `ColorLightLambDynLightsInitializer` initializer is registered as `lambdynlights:initializer`.
- `ColorLightEntityLightTicker`: The game thread determines the desired light level for each entity, while a separate worker thread handles the expensive light propagation calculations in the engine's dynamic lighting layer. Requests are coalesced per entity, keeping only the most recent one, so frame rendering is not blocked. Lighting is recalculated when an entity moves by at least 1/8 of a block.
- `LdlItemLuminanceOverrides`: Overrides item luminance values.
- Illuminated flashlight: its light is a LambDynamicLights behaviour (a cone) that ColorLight never reads. While the flashlight is switched on in either hand, `ColorLightEntityLightTicker` lights a white cone from eye height along the holder's view, with Illuminated's shape and brightness formula (radius 8, depth 20, light `15 * 20 / t^1.5`). The cone is recomputed when the holder moves or turns.
- GPU pipeline: while ColorLight draws the light itself (`ColorLightGpu.isActive()`), LambDynamicLights' mode is forced to `OFF` through reflection, so its ticking, spatial lookup and lightmap injection stop. The player's saved mode is not changed, and it is restored when the GPU pipeline stops. Side effect: entity glows that only LambDynamicLights knows (glow squid, burning mobs) lose their white light while it is off.
- Settings: `DISABLE_LDL_ON_GPU` (default on) switches the GPU-path reflection above off; `FLASHLIGHT_RANGE_PERCENT` (default 100, 25-400) scales the flashlight cone's radius, depth and offset together.
- Directional API: `ColorLightEngine.DynamicLightWorker.update(id, x, y, z, r, g, b, strength, LightBeam beam)`. `LightBeam` holds a view direction (`LightBeam.fromView(yaw, pitch, radius, depth, offset)`), the radius, depth and tip offset in blocks. With `beam == null` it is the usual point light.

**Requirements:** LambDynamicLights 4.x (soft dependency).

---

<details>
<summary>Rus</summary>

**Что даёт:** динамический цветной свет от сущностей, которые держат или являются светящимся блоком (предметы в руках, выброшенные предметы, рамки, вагонетки, мобы).

**Как устроено:**
- Инициализатор `ColorLightLambDynLightsInitializer` подключается как `lambdynlights:initializer`.
- `ColorLightEntityLightTicker`: игровой поток решает, каким должен быть свет каждой сущности, а тяжёлое распространение света считает отдельный рабочий поток в динамический слой движка. Запросы склеиваются по сущности (остаётся самый свежий), поэтому кадр не задерживается. Свет пересчитывается при смещении сущности минимум на 1/8 блока.
- `LdlItemLuminanceOverrides`: подмена яркости предметов.
- Фонарик Illuminated: его свет — поведение LambDynamicLights (конус), который ColorLight не читает. Пока фонарик включён в любой руке, `ColorLightEntityLightTicker` зажигает белый конус от уровня глаз вдоль взгляда владельца, с формой и формулой яркости Illuminated (радиус 8, глубина 20, свет `15 * 20 / t^1.5`). Конус пересчитывается, когда владелец двигается или поворачивается.
- GPU-путь: пока ColorLight сам рисует свет (`ColorLightGpu.isActive()`), режим LambDynamicLights принудительно ставится в `OFF` через reflection: его тики, spatial lookup и инъекция в lightmap останавливаются. Сохранённый режим игрока не меняется и возвращается, когда GPU-путь выключается. Побочный эффект: свечение сущностей, которое знает только LambDynamicLights (светящийся спрут, горящие мобы), пропадает, пока он выключен.
- Настройки: `DISABLE_LDL_ON_GPU` (по умолчанию включено) отключает принудительное выключение LDL на GPU-пути; `FLASHLIGHT_RANGE_PERCENT` (по умолчанию 100, 25-400) масштабирует радиус, глубину и сдвиг конуса фонарика вместе.
- API направленного света: `ColorLightEngine.DynamicLightWorker.update(id, x, y, z, r, g, b, strength, LightBeam beam)`. `LightBeam` задаёт направление взгляда (`LightBeam.fromView(yaw, pitch, radius, depth, offset)`), радиус, глубину и сдвиг вершины конуса в блоках. При `beam == null` это обычный точечный свет.

**Требования:** LambDynamicLights 4.x (мягкая зависимость).

</details>

---