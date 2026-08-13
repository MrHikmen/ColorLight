# ColorLight

ColorLight is an attempt to implement automatic colored lighting by scanning textures.

This branch is designed to use the GPU for lighting calculations.

## GPU Lighting Computation

Starting with this version, colored light propagation can be computed on the GPU through
a compute shader instead of the sequential CPU BFS (`ColorLightEngine.propagateAdd`).

Algorithm: instead of BFS, iterative Jacobi relaxation is used — during each of the
`N = lightRangeBlocks` iterations, every voxel in the region in parallel takes the maximum
of (neighbor color − attenuation) across all 6 directions. Such a loop is equivalent to
BFS to depth `N`, but is fully parallelizable and has no dependencies between voxels
within a single iteration — exactly what compute shaders are designed for.

Code structure:

* `core/light/gpu/ILightComputeBackend` — abstraction of the computation backend.
* `core/light/gpu/GlComputeLightBackend` — implementation through an OpenGL compute shader
  (LWJGL, `GL_ARB_compute_shader` / OpenGL 4.3). Already works on any Minecraft client
  because it uses the active GL context directly, without depending on Blaze3D.
* `core/light/gpu/ColorLightGpuEngine` — subclass of `ColorLightEngine`. Accumulates
  changes ("dirty" AABB regions) and once per client tick sends a single
  batch dispatch to the GPU instead of BFS on every `addSource`/`onBlockChanged` call.
* Shader: `assets/colorlight/shaders/light_propagate.comp`, buffers are ordinary SSBOs
  (`Shader Storage Buffer Object`), without `image3D`/`imageLoad`/`imageStore`: on older
  and mobile GPUs (Fermi/Kepler and their drivers), integer image bindings on
  3D textures are a known source of native driver crashes (the issue was
  reproduced and fixed by this transition to SSBOs).
* Toggle `Compute lighting on GPU` in the settings (Sodium/ModMenu) and
  `USE_GPU_LIGHTING` in `colorlight.json` (enabled by default). On startup,
  `GlComputeLightBackend` checks the context itself (a real OpenGL 4.3 context is
  required) and additionally runs a small test computation through the entire pipeline
  (buffers → dispatch → readback) on known input data, verifying the result.
  If the test fails (compilation/linking error, GL error, or incorrect
  result), the mod automatically and silently falls back to the CPU engine; nothing
  needs to be manually enabled/disabled.

  ⚠️ Caveat: a genuine native driver crash (`EXCEPTION_ACCESS_VIOLATION`, etc.)
  occurs outside the JVM and fundamentally cannot be caught by either this
  test or `try/catch` — if a particular GPU/driver combination fails specifically on a
  large real region rather than on the small test case, the self-test will not catch it.
  This is protection against known non-working configurations, not a 100% guarantee.
* Toggle `Smooth colored lighting` / `SMOOTH_LIGHTING` is separate from
  the GPU/CPU engine selection and enables/disables color smoothing across faces.
  The smoothing logic (`sampleSmoothColor`) is now a method of `ColorLightEngine`,
  like everything else (`getColor`, `addSource`, etc.), rather than a separate
  static class:

    * in the base (CPU) engine, this is a direct port of the old `SmoothLightSampler`
      (4 map accesses per vertex, honestly and lazily performed during mesh construction);
    * `ColorLightGpuEngine` overrides the method and instead reads the ready
      value from `smoothData` — a second map populated by a separate
      compute pass `light_smooth.comp`, which runs immediately after `light_propagate.comp`
      in the same `processRegion()` (averaging over the voxel and its transparent axial
      neighbors, using the same `max(average, voxel color)` principle as the CPU version).
      If the smoothed value for a particular voxel is not ready yet (the boundary of a
      region that has not yet been processed), it honestly falls back to the CPU
      implementation from the base class — it never shows empty space instead of light.

      ⚠️ Important accuracy caveat: the GPU version averages using a 3D box filter
      (the voxel itself + up to 6 axial neighbors), whereas the CPU version averages
      over a 2D plane immediately behind the face (4 samples in the plane parallel to the face).
      These are different, although visually similar, smoothing algorithms — a deliberate
      compromise so that the result can be computed once per voxel in advance on the GPU,
      rather than recalculated on the fly for the specific orientation of each face.
* **Unified GPU pipeline (`ILightComputeBackend.propagateAndSmooth`).** Initially,
  propagation and smoothing were two separate calls: CPU submits a task →
  GPU computes propagation → CPU retrieves the result → CPU submits a second task,
  passing the same result back → GPU computes smoothing → CPU retrieves
  the result again. Each such GPU→CPU transition is a pipeline synchronization
  (`glGetBufferSubData` waits for the GPU to finish) and an unnecessary copy of `voxelCount*4` bytes.
  Now it is a single call: CPU submits the task once → GPU computes propagation →
  the final relaxation buffer is passed to the smoothing stage **directly on the GPU**
  (it is simply rebound to another binding point of another compute program, without
  going through the CPU between stages) → GPU computes smoothing → CPU retrieves both ready
  results (`propagated` + `smoothed`) in a single pass. See
  `GlComputeLightBackend.runPropagateAndSmooth`.
* **Persistent buffers.** The 5 SSBOs (`bufOpacity`, `bufBase`, `bufA`, `bufB`,
  `bufSmooth`) are no longer created and deleted on every `processRegion()`
  (`glGenBuffers`/`glDeleteBuffers`), but are reused between calls: they grow
  (`glBufferData`) only when the current capacity is insufficient, while the current data
  on each call is simply overwritten (`glBufferSubData`). This reduces
  driver overhead from recreating buffers during frequent batches — but
  it does not remove the CPU from the loop entirely; see the next section.

### Why Completely "CPU-Free" GPU Computation Is Impossible, and What It Actually Means

Technically, the CPU must participate at two boundaries, and this is not an implementation bottleneck
but a fundamental property of the task:

1. **Input.** Information about "which block is where, what its opacity is,
   whether it contains a light source" exists only in Minecraft's CPU-side structures
   (`LevelChunkSection`, `BlockState` — ordinary Java objects). The GPU has no
   direct access to the game world — this data must be read by the CPU
   and uploaded to a GPU buffer. This is what `processRegion()` does before dispatch.
2. **Output.** The result must end up where it is used: in the `data`/`smoothData` maps
   read by `getColor`/`sampleSmoothColor`/etc. —
   and these methods are called from ordinary Java code (mesh construction, config,
   other parts of the mod). These are also CPU-side structures.

What was actually removed in this update is the **unnecessary** CPU barrier
**between** the two GPU stages (propagation and smoothing previously had separate
readbacks one after another, now there is one shared readback at the end) and the **overhead**
of recreating buffers on every call (they are now reused). Both CPU↔GPU
input/output boundaries (uploading the world at the beginning, reading the result at the end)
remain — they cannot be removed without changing the architecture used to store and consume lighting
(see the next section about `TintedBakedModel`).

### Can `TintedBakedModel` Itself Be Computed on the GPU?

In short: no, but indirectly it already consumes the GPU result rather than recalculating
it itself.

`TintedBakedModel` is code for chunk mesh construction: part of the Fabric Rendering API
(`FabricBakedModel.emitBlockQuads`), it runs on the CPU (in `ChunkBuilder` threads)
and produces a static vertex buffer with already "baked" colors, which is then uploaded
to VRAM in a single operation. This is CPU code by its nature — it does not run on the GPU
and cannot, because this is part of the process of preparing geometry for rendering,
not the rendering itself.

However, with the GPU engine active, `TintedBakedModel` no longer recalculates the light itself —
it calls `engine.sampleSmoothColor(...)`/`engine.sampleFlatColor(...)`, and
`ColorLightGpuEngine` overrides these methods so that they read the ready
value from `data`/`smoothData` — maps populated by the GPU computation in
`processRegion()`. In other words, the color itself really is computed on the GPU, `TintedBakedModel`
only reads the ready result once (`O(1)` from `ConcurrentHashMap`) instead of
recalculating the 4 samples of the CPU algorithm.

A fully "live" approach — where the mesh does not store baked color at all, but
the terrain fragment/vertex shader samples a 3D lighting texture itself
every frame directly on the GPU — is fundamentally possible, but this is a completely different
architecture: it would be necessary to stop baking color into vertices during mesh construction
and instead keep lighting as a permanently resident GPU texture connected to the
terrain rendering shader (a custom rendering pipeline on top of Sodium/Blaze3D, with
custom uniform/texture bindings per chunk). This is an order of magnitude larger
amount of work, tied to the internals of the rendering pipeline of a specific game build,
and I did not include it in this patch — if you want to go this route,
this is a separate major task, and it is better to discuss it separately rather than
as an "add-on" to the current architecture.

### Faster Chunk Scanning (`ColorLightChunkScanner`)

* **Fast palette check — restored.** It turned out that this was not the
  cause of the light disappearing (see below, the actual cause was an NPE in `setSectionRangeDirty`).
  The method `section.maybeHas(Predicate<BlockState>)` was confirmed against the decompiled
  `LevelChunkSection` sources — it simply delegates to
  `PalettedContainer.maybeHas(predicate)`, checking the section palette (usually
  single digits to tens of unique states) instead of scanning all 4096 positions. The vast majority
  of sections in a typical build contain no registered light-emitting blocks at all, so
  this remains a real and safe performance improvement.
* **Parallel scanning, sequential application.** The actual scanning of chunk blocks
  (read-only) runs in a thread pool (`SCAN_EXECUTOR`, up to 4 threads) —
  different chunks never overlap in `BlockPos`, so this is safe.
  However, the `engine.addSource(...)` calls themselves intentionally remain on one thread
  (`APPLY_EXECUTOR`): the CPU engine performs sequential BFS over shared maps here,
  and `ConcurrentHashMap` guarantees thread-safety of individual operations, but not
  atomicity of the fill algorithm itself — parallel `addSource` calls from different threads
  could cause races and lose updates. The separation of "scan in parallel →
  apply sequentially" provides the main performance gain without risking correctness.
* **A related bug found and fixed:** `ColorLightBlockRegistry.byBlock`
  was a normal (non-`volatile`) static `HashMap` field. While scanning
  was single-threaded, visibility issues almost never manifested; after
  parallelizing the scanner, multiple threads could fail to see the
  reassigned reference promptly after `load()` (saving block settings on the main
  thread) — without a Java Memory Model guarantee. The field was made `volatile`.

### The Actual Cause of "All Light Disappears After Changing Settings" (NPE, Unrelated to the Scanner)

The real cause turned out to be elsewhere: `ClientLevel.setSectionRangeDirty(...)`
(through `SodiumWorldRenderer.scheduleRebuildForChunk`) throws a `NullPointerException`
if called during a narrow window immediately after entering a world/reconnecting — Sodium
has not yet created `renderSectionManager` for the level. This exception is either quietly
swallowed by the `Error executing task on Client` log (if the call comes from
`Minecraft.execute()`, as in the scanner and `markWholeRenderDistanceDirty`) — and then
the mesh rebuild for that call simply **does not happen at all**, so the light
is not updated; or (worse) it propagates directly in the middle of `Level.setBlock`
in `LevelMixin` — potentially breaking the code that called `setBlock` (for example, network packet handling).

Fixed: `ColorLightRenderUtil.setBlocksDirtySafe(...)` — a wrapper that
catches exactly this `NullPointerException` and moves the attempt to the next
client tick through `Minecraft.execute()` (up to ~20 attempts), instead of
silently/loudly failing once and never rebuilding the chunk. It is used in
all three places where there was previously a direct call: `ColorLightChunkScanner`,
`ColorLightSodiumConfig.markWholeRenderDistanceDirty`, and `LevelMixin`.

### The Real, Real Cause of "All Light Disappears" (Thread Race, Not NPE)

After the NPE fix above, the problem remained: light still disappeared from all
sources when changing settings OR when simply placing a block — without a single
error in the log. The cause was a data race between two threads mutating the same
engine simultaneously:

* `LevelMixin` (hook on `Level.setBlock`, reacts to any live block change
  — placement, redstone, switching `lit`) calls
  `engine.addSource/removeSource/onBlockChanged` **synchronously on the render thread**.
* `ColorLightChunkScanner` previously applied sources found during scanning
  through a separate single-threaded `APPLY_EXECUTOR` — **a separate background thread**.

Both paths mutate the shared `ConcurrentHashMap`s (`data`/`sources`) through
`ColorLightEngine.propagateAdd`/`darkenAndCollectSeeds` — BFS algorithms
using the pattern "read current value → decide whether to update →
write". Individual `get`/`put` operations on `ConcurrentHashMap` are atomic,
but the sequence as a whole is not. If the render thread (through `LevelMixin`)
and the background thread (through the scanner) simultaneously execute `propagateAdd`/
`darkenAndCollectSeeds` over the same positions, they can overwrite
each other's results — including in a sequence of "darken an area,
then light it again", where another write interleaved in the middle leaves the area
permanently dark. The result feels exactly like it did in the bug report:
light disappears from all sources at once, without a single error in the log — because
formally nothing crashed, the data simply ended up in an incorrect state.

The longer the background scan runs (for example, `rescanAll()` over the entire render
distance after changing block settings), the larger the window for this race — which
also explains why the problem reproduced so consistently specifically after changing
settings or placing a block, but not immediately after entering a world (while scanning
had not yet started or had already completely finished).

Fixed: `APPLY_EXECUTOR` was removed completely. Sources found by the scanner
are applied through `Minecraft.getInstance().execute(...)` — that is, **on the
render thread**, the same thread where `LevelMixin` runs. The scanning itself (reading
blocks, the most expensive part) remains parallelized on `SCAN_EXECUTOR` —
this is still safe because reading does not mutate the engine. Now both sources
of engine mutations are guaranteed to run on the same thread, making concurrent
access from two different threads structurally impossible.

At the same time, two more places without `volatile` were found on fields that are written on
the render thread and read from background scanner threads — without a Java Memory
Model guarantee of timely visibility of updates:

* `ColorLightEngineHolder.engine`/`maxRangeBlocks`/`useGpu`
* (already mentioned above) `ColorLightBlockRegistry.byBlock`

Both were made `volatile`.

### Lighting Now Follows Block State (`lit`/`powered`/etc.)

`LevelMixin` (hook on `Level.setBlock`, triggered by any block change,
including changes to state properties only — for example `lit` on campfire/furnace/redstone-
lamp/candle) previously added a light source at the FULL brightness from the settings
(`entry.light`) on every trigger, without looking at whether the block was emitting light
in its current state — meaning `lit=false` was ignored, and an extinguished
campfire/furnace would still continue to emit colored light at full strength.

Fixed: `state.getLightEmission()` is used — the emission of the CURRENT `BlockState`
itself (the same value already used by the initial chunk scan). For vanilla blocks with
state-dependent emission, this value itself becomes `0` when the block is not emitting
(`lit=false`, `powered=false`, etc.), and `>0` when it is emitting — meaning colored light
automatically follows the block state without needing to hardcode the specific property name "lit"
(it works the same way for campfires, furnaces, redstone lamps, candles, and any other
block with similar mechanics). If `getLightEmission() == 0` and the source had already
been added earlier, it is removed (`engine.removeSource`) instead of continuing to emit
at full power.

* Mixin was deliberately not used here: the only remaining optimization
  (parallel scanning) is achieved through a stable public API, without depending on
  internal classes/mappings of a specific game build.

### Known Reason Why Toggles Might Not Apply Immediately

`ColorLightChunkScanner.rescanAll()` marks a chunk "dirty" (triggers mesh rebuild)
only when it finds **new** light sources (`foundAny` in `scanChunk`).
When rescanning already-scanned chunks (for example, after saving settings), there are
no new sources — meaning `foundAny` is always `false`, and the meshes are not
rebuilt. Because of this, purely visual options (`SMOOTH_LIGHTING`,
`USE_GPU_LIGHTING`, etc.) that do not change the list of sources did not physically
reach already-built chunks. Fixed — `ColorLightSodiumConfig.save()` now
unconditionally forces a rebuild of the entire render distance
(`markWholeRenderDistanceDirty`) after saving, rather than only when
`ENABLE = false`.

### About Vulkan / Blaze3D

The technically correct long-term approach is to use not "raw" OpenGL directly,
but a compute pipeline through `com.mojang.blaze3d.systems.GpuDevice`, so that the same
logic works identically on both the OpenGL and Vulkan backends of the engine (when the game
is rendered through Vulkan). At the time of writing this code, the public Blaze3D API in
the available documentation (`RenderPipeline`, `GpuDevice`, `GpuBuffer`, `GpuTexture`)
is explicitly confirmed only for the render pipeline (vertex/fragment pipeline), while
the existence and signature of a separate compute pipeline API (`GpuDevice.createComputePipeline`
or equivalent) have not been confirmed — this should be checked directly against the decompiled
classes of your game version (Yarn/Loom mappings for 26.2) before writing code for them.

Therefore, the current implementation provides a working OpenGL path (`GlComputeLightBackend`) plus
an `ILightComputeBackend` interface specifically designed so that a second
backend on top of Blaze3D can be connected as a new class, without changes to
`ColorLightGpuEngine` — as soon as the compute pipeline API in Blaze3D is confirmed.

Note that this is not the same as the **VulkanMod** mod (a third-party replacement
render backend, see the compatibility table above — it is officially unsupported).
The discussion here is about the native Vulkan backend of Blaze3D/Minecraft itself, if/when it becomes
the primary one.