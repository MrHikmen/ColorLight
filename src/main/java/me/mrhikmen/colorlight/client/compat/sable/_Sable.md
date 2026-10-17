# Sable

**What it does:** with the GPU pipeline (Sodium) on, light on Sable sub-levels (ships, contraptions) is rendered per pixel, like the terrain. Without Sable nothing changes.

**How it works:**
- Sable draws sub-levels with vanilla core shaders, not Sodium's terrain program. `VanillaChunkedSubLevelRenderDataMixin` (`colorlight.sable.mixins.json`, only applied when Sable is installed) wraps `renderChunkedSubLevel` and swaps the core shader (`solid`, `cutout`, `cutout_mipped`, `translucent`) for ColorLight's copy: `assets/colorlight/shaders/core/sublevel*`.
- Sub-level blocks live in a plot ~20M blocks away, outside the camera-centred volume. `SableLightVolume` keeps one window per sub-level (31x31x31 sections around the middle of its bounding box, own page table, shared slot pool: 16 windows, 512 sections). The shader selects it with `u_CLAnchor`, `u_CLPageBase`, `u_CLCamOff` (`CL_PLOT` in `colorlight_data.glsl`).
- Sable draws `Position + ChunkOffset` = plot-space vertex minus plot-space camera, so `ColorLightSableCompat` only computes the camera in plot space (double precision, from the render pose) relative to the anchor.
- Sable classes are read by reflection (`SableAccess`); nothing is compiled against Sable.

**Limitations:** single-block sub-levels (Sable's batched path) and Sable's own directional/sky-light shading are not covered; more than 16 visible sub-levels or sections farther than 14 sections from a sub-level's centre get no coloured light. Untested in-game: written against Sable 2.0.6 sources / Minecraft 1.21.1.
