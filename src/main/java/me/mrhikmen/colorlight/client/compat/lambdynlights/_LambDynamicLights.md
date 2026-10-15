# LambDynamicLights

**What it does:** Provides dynamic colored lighting from entities that hold or are themselves associated with a light-emitting block (held items, dropped items, item frames, minecarts, and mobs).

**How it works:**
- The `ColorLightLambDynLightsInitializer` initializer is registered as `lambdynlights:initializer`.
- `ColorLightEntityLightTicker`: The game thread determines the desired light level for each entity, while a separate worker thread handles the expensive light propagation calculations in the engine's dynamic lighting layer. Requests are coalesced per entity, keeping only the most recent one, so frame rendering is not blocked. Lighting is recalculated when an entity moves by at least 1/8 of a block.
- `LdlItemLuminanceOverrides`: Overrides item luminance values.

**Requirements:** LambDynamicLights 4.x (soft dependency).

---

<details>
<summary>Rus</summary>

**Что даёт:** динамический цветной свет от сущностей, которые держат или являются светящимся блоком (предметы в руках, выброшенные предметы, рамки, вагонетки, мобы).

**Как устроено:**
- Инициализатор `ColorLightLambDynLightsInitializer` подключается как `lambdynlights:initializer`.
- `ColorLightEntityLightTicker`: игровой поток решает, каким должен быть свет каждой сущности, а тяжёлое распространение света считает отдельный рабочий поток в динамический слой движка. Запросы склеиваются по сущности (остаётся самый свежий), поэтому кадр не задерживается. Свет пересчитывается при смещении сущности минимум на 1/8 блока.
- `LdlItemLuminanceOverrides`: подмена яркости предметов.

**Требования:** LambDynamicLights 4.x (мягкая зависимость).

</details>

---