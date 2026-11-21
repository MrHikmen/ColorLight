package me.mrhikmen.colorlight.client.compat.lod;

import me.mrhikmen.colorlight.client.ColorLightClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps a {@link LodLightVolume} up to date from the light sources of a {@link LodLightStore}.
 * <p>
 * Everything runs on one low-priority background thread, in short slices: tiles near the camera are made first, a
 * column of sources that changed (a light placed or broken) is handled before the rest, and tiles that fall out of the
 * window around the camera are dropped. Nothing here touches OpenGL or the game; the render thread only calls
 * {@link #tick} (cheap) and takes the finished tiles from the volume.
 * <p>
 * <b>Levels of detail:</b> the light is made at four resolutions (cells of 1, 2, 4, 8 blocks, see {@link LodLightVolume}).
 * Level {@code L} covers the columns within {@code FINE_BLOCKS * 2^L} blocks of the camera (the last one the whole
 * window), so the finest light exists only near the player, where Voxy draws block-sized quads, and a lit area far
 * away costs a fraction of the memory. The shader picks the level of the quad it draws and falls back to the next
 * coarser one where the finer tile is missing (the pool of a level is full).
 */
public final class LodVolumeBuilder {

    /** Radius, in 32-block columns, around the camera that gets light (192 = about 6000 blocks, further than Voxy draws). */
    public static final int WINDOW_COLS = Math.max(8, Math.min(512, Integer.getInteger("colorlight.lod.windowCols", 192)));
    /** Radius, in blocks, of the finest level; every next level reaches twice as far. */
    public static final int FINE_BLOCKS = Math.max(128, Math.min(4096, Integer.getInteger("colorlight.lod.fineBlocks", 512)));
    private static final int EVICT_MARGIN_COLS = 6;
    /** Tiles pushed out at once when a level's share of the pool is full. */
    private static final int EVICT_BATCH = 512;
    private static final long WARN_EVERY_MS = 30_000L;

    private static final long SLICE_NANOS = 4_000_000L;
    private static final long SLICE_GAP_MS = 3L;
    private static final int[] NO_SOURCES = new int[0];

    /** Radius, in columns, that each level covers. */
    private static final int[] LEVEL_COLS = new int[LodLightVolume.LEVELS];

    static {
        for (int l = 0; l < LodLightVolume.LEVELS; l++) {
            long blocks = (long) FINE_BLOCKS << l;
            LEVEL_COLS[l] = l == LodLightVolume.MAX_LEVEL ? WINDOW_COLS : (int) Math.min(WINDOW_COLS, (blocks + 31) >> 5);
        }
    }

    /** Column offsets inside the window, nearest first. Made on first use, on the worker thread (a few hundred thousand entries). */
    private static final class Ring {
        static final int[] DX;
        static final int[] DZ;

        static {
            int r = WINDOW_COLS;
            int side = 2 * r + 1;
            long[] all = new long[side * side];
            int n = 0;
            for (int dz = -r; dz <= r; dz++)
                for (int dx = -r; dx <= r; dx++)
                    all[n++] = (((long) dx * dx + (long) dz * dz) << 40) | ((long) (dx + 1024) << 20) | (dz + 1024);
            Arrays.sort(all);
            DX = new int[n];
            DZ = new int[n];
            for (int i = 0; i < n; i++) {
                DX[i] = (int) ((all[i] >> 20) & 0xFFFFF) - 1024;
                DZ[i] = (int) (all[i] & 0xFFFFF) - 1024;
            }
        }
    }

    private final LodLightStore store;
    private final LodLightVolume volume;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ColorLight LOD Light Volume");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final AtomicBoolean scheduled = new AtomicBoolean();

    private volatile LodLightVolume.Params params;
    private volatile int camX, camZ;
    private volatile boolean camKnown;

    // worker thread only
    @SuppressWarnings("unchecked")
    private final HashMap<Long, int[]>[] applied = new HashMap[LodLightVolume.LEVELS];
    private final LinkedHashSet<Long> urgent = new LinkedHashSet<>();
    private final LinkedHashSet<Long> bulk = new LinkedHashSet<>();
    private volatile long lastParamsKey = Long.MIN_VALUE;
    private volatile boolean sweepActive;
    private int sweepIdx;
    private volatile int sweepCx = Integer.MIN_VALUE, sweepCz;
    private int evictCx = Integer.MIN_VALUE, evictCz;
    private boolean overflowSeen;
    private boolean loggedFailure;
    private long lastWarn;
    /** Per level: tiles at least this far (squared, in tiles of the level) from the camera did not fit; none is tried until the camera moves. */
    private final long[] failDist2 = new long[LodLightVolume.LEVELS];
    private final int[] scratch = new int[LodLightVolume.TILE_VOXELS];
    private final Sources sources = new Sources();

    public LodVolumeBuilder(LodLightStore store, LodLightVolume volume) {
        this.store = store;
        this.volume = volume;
        for (int l = 0; l < LodLightVolume.LEVELS; l++) {
            applied[l] = new HashMap<>();
            failDist2[l] = Long.MAX_VALUE;
        }
        store.changedColumns.clear();
        store.trackChanges = true;
    }

    /** What turns sources into light; null while the far light is off. A new value (another key) restarts everything. */
    public void setParams(LodLightVolume.Params p) {
        this.params = p;
    }

    /** Called every frame on the render thread: remembers the camera and wakes the worker when there is something to do. */
    public void tick(int camBlockX, int camBlockZ) {
        camX = camBlockX;
        camZ = camBlockZ;
        camKnown = true;
        LodLightVolume.Params p = params;
        if (p == null || !store.loaded())
            return;
        int ccx = camBlockX >> 5, ccz = camBlockZ >> 5;
        if (p.key != lastParamsKey || sweepCx == Integer.MIN_VALUE || sweepActive
                || Math.abs(ccx - sweepCx) >= 2 || Math.abs(ccz - sweepCz) >= 2
                || !store.changedColumns.isEmpty())
            ensureScheduled(0L);
    }

    public void shutdown() {
        store.trackChanges = false;
        store.changedColumns.clear();
        worker.shutdownNow();
    }

    private void ensureScheduled(long delayMs) {
        if (scheduled.compareAndSet(false, true)) {
            try {
                worker.schedule(this::slice, delayMs, TimeUnit.MILLISECONDS);
            } catch (RuntimeException shutDown) {
                scheduled.set(false);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // Worker
    // ------------------------------------------------------------------------------------------------------

    private void resetApplied() {
        for (HashMap<Long, int[]> m : applied)
            m.clear();
        Arrays.fill(failDist2, Long.MAX_VALUE);
    }

    private void slice() {
        scheduled.set(false);
        LodLightVolume.Params p = params;
        if (p == null || !camKnown || !store.loaded())
            return;
        boolean more = false;
        try {
            long deadline = System.nanoTime() + SLICE_NANOS;
            int ccx = camX >> 5, ccz = camZ >> 5;

            if (p.key != lastParamsKey) {
                lastParamsKey = p.key;
                volume.clear();
                resetApplied();
                urgent.clear();
                bulk.clear();
                sweepCx = Integer.MIN_VALUE;
                evictCx = Integer.MIN_VALUE;
            }

            // columns whose sources changed: first
            Long changed;
            while ((changed = store.changedColumns.poll()) != null) {
                int cx = (int) (changed >> 32), cz = (int) (long) changed;
                int[] cur = store.columns.get(changed);
                for (int l = 0; l < LodLightVolume.LEVELS; l++)
                    if (Math.abs(cx - ccx) <= LEVEL_COLS[l] + 2 && Math.abs(cz - ccz) <= LEVEL_COLS[l] + 2)
                        markColumn(l, changed, cur, urgent);
            }

            if (sweepCx == Integer.MIN_VALUE || Math.abs(ccx - sweepCx) >= 2 || Math.abs(ccz - sweepCz) >= 2) {
                sweepCx = ccx;
                sweepCz = ccz;
                sweepIdx = 0;
                sweepActive = true;
            }

            if (evictCx == Integer.MIN_VALUE || Math.abs(ccx - evictCx) >= 4 || Math.abs(ccz - evictCz) >= 4) {
                evictCx = ccx;
                evictCz = ccz;
                evict(ccx, ccz);
            }

            // first pass over the window, nearest columns first, every level that reaches the column; only columns not yet taken into account
            while (sweepActive && sweepIdx < Ring.DX.length) {
                int dx = Ring.DX[sweepIdx], dz = Ring.DZ[sweepIdx];
                int ax = Math.abs(dx), az = Math.abs(dz);
                long key = LodLightStore.colKey(sweepCx + dx, sweepCz + dz);
                sweepIdx++;
                int[] cur = null;
                boolean looked = false;
                for (int l = 0; l < LodLightVolume.LEVELS; l++) {
                    if (ax > LEVEL_COLS[l] || az > LEVEL_COLS[l] || applied[l].containsKey(key))
                        continue;
                    if (!looked) {
                        cur = store.columns.get(key);
                        looked = true;
                    }
                    if (cur == null)
                        break;
                    markColumn(l, key, cur, bulk);
                }
                if ((sweepIdx & 255) == 0 && System.nanoTime() > deadline)
                    break;
            }
            if (sweepIdx >= Ring.DX.length)
                sweepActive = false;

            // the tiles
            while (System.nanoTime() < deadline) {
                Long key = pop(urgent);
                if (key == null)
                    key = pop(bulk);
                if (key == null)
                    break;
                buildTile(key, p);
            }
            more = sweepActive || !urgent.isEmpty() || !bulk.isEmpty() || !store.changedColumns.isEmpty();
        } catch (Throwable t) {
            if (!loggedFailure) {
                loggedFailure = true;
                ColorLightClient.LOGGER.warn("[ColorLight] Building the far light volume failed (further failures are not logged)", t);
            }
        }
        if (more)
            ensureScheduled(SLICE_GAP_MS);
    }

    private static Long pop(LinkedHashSet<Long> set) {
        Iterator<Long> it = set.iterator();
        if (!it.hasNext())
            return null;
        Long k = it.next();
        it.remove();
        return k;
    }

    /** Remembers the column's sources as taken into account on this level and queues every tile that any of its (old or new) sources reach. */
    private void markColumn(int level, long key, int[] cur, LinkedHashSet<Long> into) {
        if (cur == null)
            cur = NO_SOURCES;
        int[] old = applied[level].put(key, cur);
        if (old == cur)
            return;
        markSources(level, cur, into);
        if (old != null && old.length > 0 && !Arrays.equals(old, cur))
            markSources(level, old, into);
    }

    /** Queues the tiles that the sources reach, each once: a flag per tile of the box around the column, not one queue entry per source. */
    private static void markSources(int level, int[] src, LinkedHashSet<Long> into) {
        if (src.length < 4)
            return;
        final int sh = LodLightVolume.TILE_SHIFT + level;
        final int r = LodLightVolume.RADIUS;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int i = 0; i + 3 < src.length; i += 4) {
            minX = Math.min(minX, src[i]);
            maxX = Math.max(maxX, src[i]);
            minY = Math.min(minY, src[i + 1]);
            maxY = Math.max(maxY, src[i + 1]);
            minZ = Math.min(minZ, src[i + 2]);
            maxZ = Math.max(maxZ, src[i + 2]);
        }
        int tx0 = (minX - r) >> sh, tx1 = (maxX + r) >> sh;
        int ty0 = (minY - r) >> sh, ty1 = (maxY + r) >> sh;
        int tz0 = (minZ - r) >> sh, tz1 = (maxZ + r) >> sh;
        int nx = tx1 - tx0 + 1, ny = ty1 - ty0 + 1, nz = tz1 - tz0 + 1;
        long cells = (long) nx * ny * nz;
        if (cells > 4_000_000L) { // a freak column (sources a thousand blocks apart): the plain way
            for (int i = 0; i + 3 < src.length; i += 4)
                for (int tx = (src[i] - r) >> sh; tx <= (src[i] + r) >> sh; tx++)
                    for (int tz = (src[i + 2] - r) >> sh; tz <= (src[i + 2] + r) >> sh; tz++)
                        for (int ty = (src[i + 1] - r) >> sh; ty <= (src[i + 1] + r) >> sh; ty++)
                            into.add(LodLightVolume.tileKey(level, tx, ty, tz));
            return;
        }
        boolean[] flag = new boolean[(int) cells];
        for (int i = 0; i + 3 < src.length; i += 4) {
            int a0 = ((src[i] - r) >> sh) - tx0, a1 = ((src[i] + r) >> sh) - tx0;
            int b0 = ((src[i + 1] - r) >> sh) - ty0, b1 = ((src[i + 1] + r) >> sh) - ty0;
            int c0 = ((src[i + 2] - r) >> sh) - tz0, c1 = ((src[i + 2] + r) >> sh) - tz0;
            for (int a = a0; a <= a1; a++)
                for (int c = c0; c <= c1; c++) {
                    int base = (a * nz + c) * ny;
                    for (int b = b0; b <= b1; b++)
                        flag[base + b] = true;
                }
        }
        for (int a = 0; a < nx; a++)
            for (int c = 0; c < nz; c++)
                for (int b = 0; b < ny; b++)
                    if (flag[(a * nz + c) * ny + b])
                        into.add(LodLightVolume.tileKey(level, tx0 + a, ty0 + b, tz0 + c));
    }

    private void buildTile(long key, LodLightVolume.Params p) {
        int level = LodLightVolume.keyLevel(key);
        int tx = LodLightVolume.keyTx(key), ty = LodLightVolume.keyTy(key), tz = LodLightVolume.keyTz(key);
        int sh = LodLightVolume.TILE_SHIFT + level;
        int ccx = camX >> 5, ccz = camZ >> 5;
        // out of the level's window by now (the camera moved on): not worth making
        int cx = (tx << sh) >> 5, cz = (tz << sh) >> 5;
        int reach = LEVEL_COLS[level] + EVICT_MARGIN_COLS;
        if (Math.abs(cx - ccx) > reach || Math.abs(cz - ccz) > reach)
            return;
        int ctx = camX >> sh, ctz = camZ >> sh;
        long ddx = tx - ctx, ddz = tz - ctz;
        long d2 = ddx * ddx + ddz * ddz;
        // a tile this far did not fit a moment ago and nothing has changed since: do not make it again
        if (d2 >= failDist2[level] && !volume.contains(level, tx, ty, tz))
            return;
        long t0 = System.nanoTime();
        boolean any = LodLightVolume.computeTile(scratch, level, tx, ty, tz, sources, p);
        LodDiag.volTileComputes.incrementAndGet();
        LodDiag.volComputeNanos.addAndGet(System.nanoTime() - t0);
        if (!volume.setTile(level, tx, ty, tz, scratch, any)) {
            // no room: tiles of this level farther from the camera than this one make way for it
            int freed = volume.evictFarthest(level, ctx, ctz, d2, EVICT_BATCH);
            overflowSeen = true; // what was pushed out or never fitted has to be made again when the camera comes closer
            if (freed > 0 && volume.setTile(level, tx, ty, tz, scratch, any))
                return;
            failDist2[level] = Math.min(failDist2[level], d2);
            warnFull(level);
        }
    }

    private void warnFull(int level) {
        long now = System.currentTimeMillis();
        if (now - lastWarn < WARN_EVERY_MS)
            return;
        lastWarn = now;
        ColorLightClient.LOGGER.warn("[ColorLight] The far light pool is full on level {} ({} of {} tiles used by it, {} in all): light farther than the lit tiles falls back to a coarser level or is missing. the \"Far light memory\" setting (ColorLight, Voxy group) raises the pool (2 KB of video memory per tile)",
                level, volume.tileCount(level), volume.quotaOf(level), volume.tileCount());
    }

    private void evict(int ccx, int ccz) {
        // tiles outside their level's window go; the work lists forget them too
        final int[] reach = new int[LodLightVolume.LEVELS];
        for (int l = 0; l < LodLightVolume.LEVELS; l++)
            reach[l] = LEVEL_COLS[l] + EVICT_MARGIN_COLS;
        volume.evictWhere((l, tx, ty, tz) -> tileOutside(l, tx, tz, ccx, ccz, reach[l]));
        for (int l = 0; l < LodLightVolume.LEVELS; l++) {
            final int r = reach[l];
            applied[l].keySet().removeIf(k -> Math.abs((int) (k >> 32) - ccx) > r || Math.abs((int) (long) k - ccz) > r);
        }
        urgent.removeIf(k -> tileOutside(LodLightVolume.keyLevel(k), LodLightVolume.keyTx(k), LodLightVolume.keyTz(k), ccx, ccz, reach[LodLightVolume.keyLevel(k)]));
        bulk.removeIf(k -> tileOutside(LodLightVolume.keyLevel(k), LodLightVolume.keyTx(k), LodLightVolume.keyTz(k), ccx, ccz, reach[LodLightVolume.keyLevel(k)]));
        Arrays.fill(failDist2, Long.MAX_VALUE);
        if (overflowSeen) {
            // a pool was full: tiles were pushed out or never fitted; the camera has moved, take the columns into account again
            overflowSeen = false;
            for (HashMap<Long, int[]> m : applied)
                m.clear();
            sweepCx = Integer.MIN_VALUE;
        }
    }

    /** Whether a tile's footprint lies outside the square of {@code reach} columns around the camera's column. */
    private static boolean tileOutside(int level, int tx, int tz, int ccx, int ccz, int reach) {
        int sh = LodLightVolume.TILE_SHIFT + level;
        int minCx = (tx << sh) >> 5, maxCx = (((tx + 1) << sh) - 1) >> 5;
        int minCz = (tz << sh) >> 5, maxCz = (((tz + 1) << sh) - 1) >> 5;
        return maxCx < ccx - reach || minCx > ccx + reach || maxCz < ccz - reach || minCz > ccz + reach;
    }

    /** The store's columns as a {@link LodLightVolume.SourceSet}. */
    private final class Sources implements LodLightVolume.SourceSet {
        @Override
        public void forEachNear(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, LodLightVolume.SourceVisitor visitor) {
            for (int cz = minZ >> 5; cz <= maxZ >> 5; cz++) {
                for (int cx = minX >> 5; cx <= maxX >> 5; cx++) {
                    int[] a = store.columns.get(LodLightStore.colKey(cx, cz));
                    if (a == null)
                        continue;
                    for (int i = 0; i + 3 < a.length; i += 4) {
                        int x = a[i], y = a[i + 1], z = a[i + 2];
                        if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ)
                            continue;
                        visitor.accept(x, y, z, a[i + 3]);
                    }
                }
            }
        }
    }
}
