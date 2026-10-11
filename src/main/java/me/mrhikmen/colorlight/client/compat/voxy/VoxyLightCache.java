package me.mrhikmen.colorlight.client.compat.voxy;

import me.mrhikmen.colorlight.client.ColorLightClient;
import me.mrhikmen.colorlight.client.config.BlockSettings;
import me.mrhikmen.colorlight.client.core.light.color.ColorLightUtil;
import me.mrhikmen.colorlight.client.core.light.registry.ColorLightBlockRegistry;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.IWorldGetIdentifier;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coloured light for Voxy's distant LODs, read straight from Voxy's own (already persistent) voxel storage
 * instead of a separate copy ColorLight builds and saves itself.
 * <p>
 * There is no "far light" file any more: everything here is a plain in-memory memo cache, good for one session.
 * Voxy already keeps its block data on disk forever (that is the whole point of Voxy), so re-deriving which
 * voxels are coloured light sources from it is cheap enough to simply redo next time a world is joined, and a
 * lot simpler than keeping our own save format in sync with Voxy's.
 * <p>
 * <b>Tiers</b> (cell width in blocks): {@link #CELL_BLOCKS}. The first four map 1:1 onto one of Voxy's own LOD
 * levels (0..4, i.e. 1/4/8/16-block voxels - Voxy's finest and its own coarsest). Voxy does not go coarser than
 * 16 blocks/voxel ({@code WorldEngine.MAX_LOD_LAYER}), so the remaining tiers (32/64/128) are built by grouping
 * several of Voxy's level-4 voxels per cell and keeping the brightest, the same "top of column wins" rule used
 * everywhere else here.
 * <p>
 * Only a column's topmost clearly-lit voxel is kept (same reasoning as the old {@code LodLightMap}: the visible
 * face of a LOD from a distance is its top), each channel the block's own colour - there is no flood-fill out
 * here, so a light's glow does not bleed into neighbouring cells the way it does up close.
 * <p>
 * All public methods are safe to call from any thread. Cell computation (which touches Voxy's section storage,
 * and so may block on IO the first time an area is visited) always happens on a single background worker;
 * {@link #fillGrid} only ever reads the already-memoised result and never blocks.
 */
public final class VoxyLightCache {

    /** Cell width in blocks, index = tier. Keep in sync with {@code colorlight_lod.glsl}. */
    public static final int[] CELL_BLOCKS = {1, 4, 8, 16, 32, 64, 128};
    public static final int TIERS = CELL_BLOCKS.length;

    /** Which Voxy LOD level a tier's voxels come from (clamped to Voxy's own max, level 4). */
    private static final int[] VOXY_LEVEL = {0, 2, 3, 4, 4, 4, 4};

    private static final int MIN_BRIGHTNESS = 8; // out of 255; fainter than this is not worth a colour
    // Generous world-height window (blocks) to scan a column through; covers vanilla dimensions and then some.
    private static final int SCAN_TOP_Y = 512;
    private static final int SCAN_BOTTOM_Y = -512;

    /** Cell key (x,z) -> (topBlockY + Y_BIAS) << 32 | brightness << 24 | 0xBBGGRR. 0 = "checked, nothing lit". */
    private static final long Y_BIAS = 1L << 20;

    private final ConcurrentHashMap<Long, Long>[] cache;
    private final AtomicLong[] versions;

    private static final int BUDGET_PER_TICK = 48; // new (uncached) cells computed per drain, across all tiers
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ColorLight Voxy Light Sampler");
        t.setDaemon(true);
        t.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        return t;
    });
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private volatile WorldEngine pendingEngine;
    private volatile int pendingCamX, pendingCamZ;
    private boolean loggedFailure;

    @SuppressWarnings("unchecked")
    public VoxyLightCache() {
        this.cache = new ConcurrentHashMap[TIERS];
        this.versions = new AtomicLong[TIERS];
        for (int t = 0; t < TIERS; t++) {
            this.cache[t] = new ConcurrentHashMap<>();
            this.versions[t] = new AtomicLong();
        }
    }

    /** Forgets everything. Cheap: it all still lives in Voxy and will simply be re-read as cells are needed again. */
    public void clear() {
        for (ConcurrentHashMap<Long, Long> m : cache)
            m.clear();
    }

    /** Changes whenever a cell of this tier changed (added, removed or re-coloured). */
    public long version(int tier) {
        return versions[tier].get();
    }

    /**
     * Called once a client tick (by {@link LodLight}, itself only reached when {@code ColorLightVoxyCompat} says
     * Voxy is present - this class, and everything it imports from Voxy, is never touched otherwise). Looks up
     * the current dimension's {@link WorldEngine} and asks the background worker to fill in any not-yet-known
     * cell around the camera, a little at a time. Entirely vanilla-typed signature on purpose: the caller needs
     * no Voxy imports of its own.
     */
    public void tick(ClientLevel level) {
        if (level == null || !(level instanceof IWorldGetIdentifier getter))
            return;
        WorldIdentifier id = getter.voxy$getIdentifier();
        if (id == null)
            return;
        var instance = VoxyCommon.getInstance();
        if (instance == null)
            return;
        WorldEngine engine = instance.getNullable(id);
        if (engine == null || !engine.isLive())
            return;

        var player = Minecraft.getInstance().player;
        if (player == null)
            return;
        requestRefresh(engine, player.getBlockX(), player.getBlockZ());
    }

    /**
     * Asks the background worker to fill in any not-yet-known cell around {@code (camBlockX, camBlockZ)}, a
     * little at a time. Safe to call every frame; a drain already running or pending is not duplicated.
     */
    private void requestRefresh(WorldEngine engine, int camBlockX, int camBlockZ) {
        pendingEngine = engine;
        pendingCamX = camBlockX;
        pendingCamZ = camBlockZ;
        if (scheduled.compareAndSet(false, true))
            worker.execute(this::drain);
    }

    /**
     * Writes the window of {@code size x size} cells around the camera cell into {@code out} as a toroidal grid
     * ({@code (x & (size-1)) | (z & (size-1)) << shift}); 0x00BBGGRR per cell, 0 for "no light" or "not computed
     * yet" (the background worker will have it shortly - the caller just redraws once {@link #version} changes).
     * Lock-free: only reads the memo map, never touches Voxy.
     */
    public void fillGrid(int tier, int camCellX, int camCellZ, int size, int[] out) {
        Arrays.fill(out, 0, size * size, 0);
        ConcurrentHashMap<Long, Long> map = cache[tier];
        int half = size / 2 - 1;
        int mask = size - 1;
        int shift = Integer.numberOfTrailingZeros(size);

        for (int dz = -half; dz <= half; dz++) {
            for (int dx = -half; dx <= half; dx++) {
                int cx = camCellX + dx;
                int cz = camCellZ + dz;
                Long v = map.get(cellKey(cx, cz));
                if (v != null && v != 0L)
                    out[(cx & mask) | ((cz & mask) << shift)] = (int) (v & 0xFFFFFFL);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // Background worker
    // ------------------------------------------------------------------------------------------------------

    private void drain() {
        scheduled.set(false);
        WorldEngine engine = pendingEngine;
        int camX = pendingCamX, camZ = pendingCamZ;
        if (engine == null || !engine.isLive())
            return;

        try {
            int spent = 0;
            // Nearest tier (finest, smallest window) first: it is what is most visible and cheapest to fill.
            outer:
            for (int tier = 0; tier < TIERS; tier++) {
                int shift = Integer.numberOfTrailingZeros(CELL_BLOCKS[tier]);
                int camCellX = camX >> shift;
                int camCellZ = camZ >> shift;
                ConcurrentHashMap<Long, Long> map = cache[tier];

                // A modest window per tier per drain: enough that a camera move keeps getting covered, without
                // ever scanning the whole toroidal grid (the GPU side only reads what is near the camera anyway).
                int r = 6;
                for (int dz = -r; dz <= r; dz++) {
                    for (int dx = -r; dx <= r; dx++) {
                        if (spent >= BUDGET_PER_TICK)
                            break outer;
                        int cx = camCellX + dx;
                        int cz = camCellZ + dz;
                        long key = cellKey(cx, cz);
                        if (map.containsKey(key))
                            continue;
                        long value = computeCell(engine, tier, cx, cz);
                        map.put(key, value);
                        versions[tier].incrementAndGet();
                        spent++;
                    }
                }
            }
        } catch (Throwable t) {
            if (!loggedFailure) {
                loggedFailure = true;
                ColorLightClient.LOGGER.warn("[ColorLight] Reading far light from Voxy failed (further failures are not logged)", t);
            }
        } finally {
            // more work may already be waiting (camera moved on, or this drain's budget ran out): go again
            if (scheduled.compareAndSet(false, true))
                worker.execute(this::drain);
            else
                scheduled.set(false);
        }
    }

    /** Scans one output cell's column straight from Voxy's own voxel storage: topmost clearly-lit block wins. */
    private long computeCell(WorldEngine engine, int tier, int cellX, int cellZ) {
        int cellBlocks = CELL_BLOCKS[tier];
        int level = VOXY_LEVEL[tier];
        int voxelBlocks = 1 << level;
        int group = Math.max(1, cellBlocks / voxelBlocks); // >1 only for the 32/64/128 tiers (coarser than Voxy itself)

        long voxelX0 = (long) cellX * group;
        long voxelZ0 = (long) cellZ * group;

        int bestBrightness = 0;
        long bestY = Long.MIN_VALUE;
        int bestRgb = 0;

        for (int gz = 0; gz < group; gz++) {
            for (int gx = 0; gx < group; gx++) {
                long vx = voxelX0 + gx;
                long vz = voxelZ0 + gz;
                long found = scanColumn(engine, level, vx, vz);
                if (found == 0L)
                    continue;
                long y = (found >>> 32) & 0xFFFFFL;
                int brightness = (int) ((found >>> 24) & 0xFF);
                if (y > bestY || (y == bestY && brightness > bestBrightness)) {
                    bestY = y;
                    bestBrightness = brightness;
                    bestRgb = (int) (found & 0xFFFFFFL);
                }
            }
        }

        if (bestBrightness < MIN_BRIGHTNESS)
            return 0L;
        return ((bestY) << 32) | ((long) bestBrightness << 24) | (bestRgb & 0xFFFFFFL);
    }

    /**
     * Scans one Voxy voxel column top to bottom for the first (topmost) block the registry knows as a coloured
     * light source. {@code vx}/{@code vz} are in level-{@code level} voxel units (Voxy's own section-local grid).
     *
     * @return {@code 0} for an empty column, otherwise {@code (worldY + Y_BIAS) << 32 | brightness << 24 | rgb}
     */
    private long scanColumn(WorldEngine engine, int level, long vx, long vz) {
        int sx = (int) (vx >> 5);
        int lx = (int) (vx & 31);
        int sz = (int) (vz >> 5);
        int lz = (int) (vz & 31);

        int sectionBlocks = 32 << level;
        int syTop = Math.floorDiv(SCAN_TOP_Y, sectionBlocks);
        int syBottom = Math.floorDiv(SCAN_BOTTOM_Y, sectionBlocks);

        for (int sy = syTop; sy >= syBottom; sy--) {
            WorldSection section = engine.acquireIfExists(level, sx, sy, sz);
            if (section == null)
                continue;
            try {
                long[] data = section._unsafeGetRawDataArray();
                for (int ly = 31; ly >= 0; ly--) {
                    long id = data[WorldSection.getIndex(lx, ly, lz)];
                    if (Mapper.isAir(id))
                        continue;

                    BlockState state = engine.getMapper().getBlockStateFromBlockId(Mapper.getBlockId(id));
                    if (state == null)
                        continue;

                    int emission = state.getLightEmission();
                    if (emission <= 0)
                        continue;

                    BlockSettings settings = ColorLightBlockRegistry.get(state.getBlock());
                    if (settings == null)
                        continue;

                    int strength = Math.min(settings.light, emission);
                    float scale = Math.max(0f, Math.min(1f, strength / 15f));
                    int rgb = ColorLightUtil.pack(Math.round(settings.r * scale), Math.round(settings.g * scale), Math.round(settings.b * scale));
                    int brightness = Math.max(ColorLightUtil.r(rgb), Math.max(ColorLightUtil.g(rgb), ColorLightUtil.b(rgb)));
                    if (brightness < MIN_BRIGHTNESS)
                        continue;

                    long worldY = ((long) sy * 32 + ly) << level;
                    return ((worldY + Y_BIAS) << 32) | ((long) brightness << 24) | (rgb & 0xFFFFFFL);
                }
            } finally {
                section.release();
            }
        }
        return 0L;
    }

    private static long cellKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public void shutdown() {
        worker.shutdownNow();
    }
}
