package me.mrhikmen.colorlight.client.compat.lod;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The coloured light of the far world as a sparse block-resolution volume: the colour (0..255 per channel, already
 * faded, the same value the near-field engine would give) of every block that a light source reaches.
 * <p>
 * Why: the LOD shader used to add up the sources around every quad itself, and Voxy's quads had to be cut into single
 * blocks around every light so that each block could get its own colour. That is a lot of quads, each running a long
 * loop. Here the sum is made once on the CPU, only where something changes, and the shader does one lookup per pixel.
 * <p>
 * Layout: the world is cut into tiles of 8x8x8 <i>cells</i>. A cell is one block on level 0 and 2, 4, 8 blocks wide on
 * levels 1..3: far away, where Voxy draws big quads anyway, the light is kept coarser, so a lit area far from the player
 * costs 8, 64, 512 times less memory. A tile exists only when at least one of its cells is lit. Tiles live in a pool of
 * {@link #TILE_VOXELS}-int slots (every level has a share of it, see {@link #quotaOf}); an open-addressing hash table maps
 * a tile's level and coordinates to its slot. Both are mirrored into one GPU buffer ({@link #totalInts()} ints):
 * <pre>
 *   [0 .. HEADER_INTS)                       header (written by the bridge)
 *   [HASH_OFFSET_INTS ..)                    HASH_ENTRIES x 4 ints: tileX, tileY | level &lt;&lt; 24, tileZ, slot (-1 empty, -2 deleted)
 *   [POOL_OFFSET_INTS ..)                    maxTiles x 512 ints: cell (x&7, y&7, z&7) at (x&lt;&lt;6)|(y&lt;&lt;3)|z, 0x00BBGGRR
 * </pre>
 * Tile coordinates of level L are block coordinates shifted right by 3 + L.
 * The numbers and the hash function are repeated in colorlight_vol_frag.glsl: keep them in sync.
 * <p>
 * Thread-safe: a builder thread fills it ({@link #setTile}, {@link #evictOutside}), the render thread drains the
 * pending GPU writes ({@link #drain}). Contains no Minecraft or GL class.
 */
public final class LodLightVolume {

    public static final int TILE_SHIFT = 3;
    public static final int TILE_SIZE = 1 << TILE_SHIFT;
    public static final int TILE_VOXELS = TILE_SIZE * TILE_SIZE * TILE_SIZE;

    public static final int HASH_BITS = 18;
    public static final int HASH_ENTRIES = 1 << HASH_BITS;
    /** The most tiles one volume can hold: the hash table is never fuller than half. */
    public static final int MAX_TILES = HASH_ENTRIES / 2;
    /** The least. */
    public static final int MIN_TILES = 4096;
    public static final int HASH_MASK = HASH_ENTRIES - 1;
    /** Most probes a lookup makes (the shader gives up after the same number). */
    public static final int MAX_PROBES = 16;

    public static final int HEADER_INTS = 16;
    public static final int HASH_OFFSET_INTS = HEADER_INTS;
    public static final int POOL_OFFSET_INTS = HEADER_INTS + HASH_ENTRIES * 4;

    /** Levels of detail: cells of 1, 2, 4 and 8 blocks. */
    public static final int LEVELS = 4;
    public static final int MAX_LEVEL = LEVELS - 1;

    public static final int SLOT_EMPTY = -1;
    public static final int SLOT_DELETED = -2;

    /** Radius of the distance tables (offsets -16..16): the same number as {@code LodMethodTables.RADIUS}. */
    public static final int RADIUS = 16;
    private static final int SIDE = 2 * RADIUS + 1;

    private static final int TOMBSTONE_REBUILD = 8192;

    /** What turns sources into colours; replaced as a whole when any of it changes. */
    public static final class Params {
        /** Distance (in blocks of walking) per propagation table: {@code [table][(x+16)*33*33 + (y+16)*33 + (z+16)]}. */
        public final float[][] dist;
        /** Light lost per block of distance, in 0..255 units (255 / reach). */
        public final float lossPerBlock;
        /** Per-channel fading with the hue frozen at the end (the "additive" setting) instead of the hue-keeping fade. */
        public final boolean additive;
        /** A coloured light is laid over a white one where they meet (the engine's "white gives way to colour" rule). */
        public final boolean whiteOverlay;
        /** A hue counts as white when its weakest channel is within this many of its brightest. */
        public final int whiteSpread;
        /** A colour covers white completely once it is this percent as bright as the white (10..100). */
        public final int whiteCoverPercent;
        /** Identifies the parameters: the volume is thrown away and rebuilt when it changes. */
        public final long key;

        public Params(float[][] dist, float lossPerBlock, boolean additive, long key) {
            this(dist, lossPerBlock, additive, false, 20, 50, key);
        }

        public Params(float[][] dist, float lossPerBlock, boolean additive, boolean whiteOverlay, int whiteSpread, int whiteCoverPercent, long key) {
            this.dist = dist;
            this.lossPerBlock = lossPerBlock;
            this.additive = additive;
            this.whiteOverlay = whiteOverlay;
            this.whiteSpread = whiteSpread;
            this.whiteCoverPercent = whiteCoverPercent;
            this.key = key;
        }
    }

    /** Receives the sources that can touch a box. */
    public interface SourceVisitor {
        void accept(int x, int y, int z, int rgb);
    }

    public interface SourceSet {
        /** Calls the visitor for (at least) every source inside the box; sources outside it may be passed too. */
        void forEachNear(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, SourceVisitor visitor);
    }

    /** Receives the GPU writes. Called with the volume's lock held, on the render thread. */
    public interface GpuSink {
        /** The GPU table must be emptied (every entry -1): called before anything else after {@link #clear}. */
        void reset();

        void tile(int slot, int[] voxels);

        void entry(int index, int a, int b, int c, int d);
    }

    private static final class Tile {
        final int level, tx, ty, tz;
        int slot;
        final int[] data = new int[TILE_VOXELS];
        boolean uploaded;
        boolean queued;
        boolean removed;

        Tile(int level, int tx, int ty, int tz) {
            this.level = level;
            this.tx = tx;
            this.ty = ty;
            this.tz = tz;
        }
    }

    public final int maxTiles;
    /** Share of the pool each level may always use (a level may borrow what the others do not use yet). */
    private final int[] quota = new int[LEVELS];
    private final int[] levelCount = new int[LEVELS];

    private final Object lock = new Object();
    private final HashMap<Long, Tile> tiles = new HashMap<>();
    private final Tile[] bySlot;
    private final ArrayDeque<Integer> freeSlots = new ArrayDeque<>();
    private int[] hash = newHash();
    private int tombstones;
    private final ArrayDeque<Tile> dirtyTiles = new ArrayDeque<>();
    private final boolean[] entryDirty = new boolean[HASH_ENTRIES];
    private int[] dirtyEntryList = new int[1024];
    private int dirtyEntryCount;
    private long overflow;
    private long lastOverflowReported;
    /** Bumped by {@link #clear}: the GPU side starts from an empty table when it sees a new value. */
    private volatile int epoch;
    private int drainedEpoch;

    public LodLightVolume(int maxTiles) {
        this.maxTiles = Math.max(64, maxTiles);
        this.bySlot = new Tile[this.maxTiles];
        final double[] share = {0.40, 0.25, 0.20, 0.15};
        for (int l = 0; l < LEVELS; l++)
            quota[l] = (int) (this.maxTiles * share[l]);
        for (int i = 0; i < this.maxTiles; i++)
            freeSlots.add(i);
    }

    public static int totalInts(int maxTiles) {
        return POOL_OFFSET_INTS + maxTiles * TILE_VOXELS;
    }

    public int totalInts() {
        return totalInts(maxTiles);
    }

    // ------------------------------------------------------------------------------------------------------
    // Keys and the hash (the shader has the same hash)
    // ------------------------------------------------------------------------------------------------------

    /** Level in the lowest 2 bits, tile y in 12 bits above, then z and x in 21 bits each. */
    public static long tileKey(int level, int tx, int ty, int tz) {
        return ((long) (tx & 0x1FFFFF) << 35) | ((long) (tz & 0x1FFFFF) << 14) | ((long) (ty & 0xFFF) << 2) | (level & 3);
    }

    public static long tileKey(int tx, int ty, int tz) {
        return tileKey(0, tx, ty, tz);
    }

    public static int keyLevel(long key) {
        return (int) key & 3;
    }

    public static int keyTx(long key) {
        return (int) (key >>> 35) << 11 >> 11;
    }

    public static int keyTz(long key) {
        return ((int) (key >>> 14) & 0x1FFFFF) << 11 >> 11;
    }

    public static int keyTy(long key) {
        return ((int) (key >>> 2) & 0xFFF) << 20 >> 20;
    }

    /** The second int of a hash entry: tile y (24 bits) and the level (above). The shader builds the same value. */
    public static int entryB(int level, int ty) {
        return (ty & 0xFFFFFF) | (level << 24);
    }

    /** {@code b} is {@link #entryB}. */
    public static int hashPacked(int tx, int b, int tz) {
        int h = tx * 0x8DA6B343 ^ b * 0xD8163841 ^ tz * 0xCB1AB31F;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        return h;
    }

    public static int hash(int level, int tx, int ty, int tz) {
        return hashPacked(tx, entryB(level, ty), tz);
    }

    /** Level 0. */
    public static int hash(int tx, int ty, int tz) {
        return hash(0, tx, ty, tz);
    }

    /** Tiles the level may always use. */
    public int quotaOf(int level) {
        return quota[level];
    }

    private static int[] newHash() {
        int[] h = new int[HASH_ENTRIES * 4];
        Arrays.fill(h, SLOT_EMPTY);
        return h;
    }

    // ------------------------------------------------------------------------------------------------------
    // Reading (also what the shader does; used by the tests)
    // ------------------------------------------------------------------------------------------------------

    /** The colour of one block as the shader reads it from the mirrored buffer: 0x00BBGGRR, 0 = no light. */
    public int lookup(int level, int x, int y, int z) {
        int sh = TILE_SHIFT + level;
        int tx = x >> sh, ty = y >> sh, tz = z >> sh;
        int b = entryB(level, ty);
        synchronized (lock) {
            int h = hashPacked(tx, b, tz) & HASH_MASK;
            for (int i = 0; i < MAX_PROBES; i++) {
                int e = h * 4;
                int slot = hash[e + 3];
                if (slot == SLOT_EMPTY)
                    return 0;
                if (slot >= 0 && hash[e] == tx && hash[e + 1] == b && hash[e + 2] == tz)
                    return bySlot[slot].data[(((x >> level) & 7) << 6) | (((y >> level) & 7) << 3) | ((z >> level) & 7)];
                h = (h + 1) & HASH_MASK;
            }
            return 0;
        }
    }

    /** Level 0. */
    public int lookup(int x, int y, int z) {
        return lookup(0, x, y, z);
    }

    /** Whether the tile exists (it has light). */
    public boolean contains(int level, int tx, int ty, int tz) {
        synchronized (lock) {
            return tiles.containsKey(tileKey(level, tx, ty, tz));
        }
    }

    /** Tiles of one level. */
    public int tileCount(int level) {
        synchronized (lock) {
            return levelCount[level];
        }
    }

    public int tileCount() {
        synchronized (lock) {
            return tiles.size();
        }
    }

    public int epoch() {
        return epoch;
    }

    /** How many tiles could not be stored because the pool was full since the last call. */
    public long takeOverflow() {
        synchronized (lock) {
            long d = overflow - lastOverflowReported;
            lastOverflowReported = overflow;
            return d;
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // Writing (builder thread)
    // ------------------------------------------------------------------------------------------------------

    /**
     * Stores the colours of one tile (or, with {@code anyLight == false}, removes the tile).
     *
     * @return false if the tile could not be stored (no free slot or the hash table is crowded there)
     */
    public boolean setTile(int tx, int ty, int tz, int[] voxels, boolean anyLight) {
        return setTile(0, tx, ty, tz, voxels, anyLight);
    }

    /** Whether level {@code level} may take one more slot: inside its quota, or from what the other levels have not used yet. */
    private boolean mayTakeLocked(int level) {
        if (levelCount[level] < quota[level])
            return true;
        int reserved = 0;
        for (int l = 0; l < LEVELS; l++)
            if (l != level)
                reserved += Math.max(0, quota[l] - levelCount[l]);
        return freeSlots.size() > reserved;
    }

    public boolean setTile(int level, int tx, int ty, int tz, int[] voxels, boolean anyLight) {
        long key = tileKey(level, tx, ty, tz);
        synchronized (lock) {
            Tile t = tiles.get(key);
            if (!anyLight) {
                if (t != null)
                    removeLocked(key, t);
                return true;
            }
            if (t != null) {
                if (Arrays.equals(t.data, voxels))
                    return true;
                System.arraycopy(voxels, 0, t.data, 0, TILE_VOXELS);
                queueTile(t);
                return true;
            }
            if (freeSlots.isEmpty() || !mayTakeLocked(level)) {
                overflow++;
                return false;
            }
            t = new Tile(level, tx, ty, tz);
            int slot = freeSlots.poll();
            t.slot = slot;
            System.arraycopy(voxels, 0, t.data, 0, TILE_VOXELS);
            if (!insertLocked(t)) {
                freeSlots.add(slot);
                overflow++;
                return false;
            }
            levelCount[level]++;
            tiles.put(key, t);
            bySlot[slot] = t;
            queueTile(t);
            return true;
        }
    }

    /** Decides which tiles go. Called with the volume's lock held: keep it short and do not call the volume from it. */
    public interface TileTest {
        boolean drop(int level, int tx, int ty, int tz);
    }

    /** Removes every tile of level 0 whose tile coordinates are outside the given inclusive ranges (y is not limited). */
    public int evictOutside(int minTx, int maxTx, int minTz, int maxTz) {
        return evictWhere((l, tx, ty, tz) -> tx < minTx || tx > maxTx || tz < minTz || tz > maxTz);
    }

    /** Removes every tile the test selects. */
    public int evictWhere(TileTest test) {
        int n = 0;
        synchronized (lock) {
            Iterator<Map.Entry<Long, Tile>> it = tiles.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, Tile> e = it.next();
                Tile t = e.getValue();
                if (test.drop(t.level, t.tx, t.ty, t.tz)) {
                    it.remove();
                    detachLocked(t);
                    n++;
                }
            }
            if (tombstones > TOMBSTONE_REBUILD)
                rebuildHashLocked();
        }
        return n;
    }

    /**
     * Makes room: removes up to {@code count} tiles that are farther from tile column ({@code ctx}, {@code ctz}) than
     * {@code minDist2} (squared distance in tiles, x and z only), the farthest first.
     *
     * @return how many were removed
     */
    public int evictFarthest(int ctx, int ctz, long minDist2, int count) {
        return evictFarthest(0, ctx, ctz, minDist2, count);
    }

    /** The same for the tiles of one level, in tiles of that level around tile column ({@code ctx}, {@code ctz}) of that level. */
    public int evictFarthest(int level, int ctx, int ctz, long minDist2, int count) {
        synchronized (lock) {
            int n = tiles.size();
            long[] keyed = new long[n];
            long[] dists = new long[n];
            int m = 0;
            for (Map.Entry<Long, Tile> e : tiles.entrySet()) {
                Tile t = e.getValue();
                if (t.level != level)
                    continue;
                long dx = t.tx - ctx, dz = t.tz - ctz;
                long d = dx * dx + dz * dz;
                if (d > minDist2) {
                    keyed[m] = e.getKey();
                    dists[m] = d;
                    m++;
                }
            }
            if (m == 0)
                return 0;
            Integer[] order = new Integer[m];
            for (int i = 0; i < m; i++)
                order[i] = i;
            Arrays.sort(order, (a, b) -> Long.compare(dists[b], dists[a]));
            int removed = Math.min(count, m);
            for (int i = 0; i < removed; i++) {
                Tile t = tiles.remove(keyed[order[i]]);
                if (t != null)
                    detachLocked(t);
            }
            if (tombstones > TOMBSTONE_REBUILD)
                rebuildHashLocked();
            return removed;
        }
    }

    /**
     * Queues everything for the GPU again: for a new, empty GPU buffer (the old one was lost) whose hash table the caller has
     * set to all -1. Deleted entries are written too: a chain of probes must not have a hole where the shader stops looking.
     */
    public void resendAll() {
        synchronized (lock) {
            dirtyTiles.clear();
            for (Tile t : tiles.values()) {
                t.queued = false;
                t.uploaded = false;
                queueTile(t);
            }
            for (int i = 0; i < HASH_ENTRIES; i++)
                if (hash[i * 4 + 3] != SLOT_EMPTY)
                    markEntry(i);
        }
    }

    /** Forgets everything; the GPU side restarts from an empty table. */
    public void clear() {
        synchronized (lock) {
            tiles.clear();
            Arrays.fill(bySlot, null);
            Arrays.fill(levelCount, 0);
            freeSlots.clear();
            for (int i = 0; i < maxTiles; i++)
                freeSlots.add(i);
            hash = newHash();
            tombstones = 0;
            dirtyTiles.clear();
            Arrays.fill(entryDirty, false);
            dirtyEntryCount = 0;
            epoch++;
        }
    }

    private void removeLocked(long key, Tile t) {
        tiles.remove(key);
        detachLocked(t);
        if (tombstones > TOMBSTONE_REBUILD)
            rebuildHashLocked();
    }

    /** Takes a tile (already out of {@link #tiles}) out of the hash and the pool. */
    private void detachLocked(Tile t) {
        t.removed = true;
        levelCount[t.level]--;
        int b = entryB(t.level, t.ty);
        int h = hashPacked(t.tx, b, t.tz) & HASH_MASK;
        for (int i = 0; i < MAX_PROBES; i++) {
            int e = h * 4;
            int slot = hash[e + 3];
            if (slot == SLOT_EMPTY)
                break;
            if (slot == t.slot && hash[e] == t.tx && hash[e + 1] == b && hash[e + 2] == t.tz) {
                hash[e + 3] = SLOT_DELETED;
                tombstones++;
                markEntry(h);
                break;
            }
            h = (h + 1) & HASH_MASK;
        }
        bySlot[t.slot] = null;
        freeSlots.add(t.slot); // FIFO: a slot is reused as late as possible
        t.uploaded = false;
    }

    private boolean insertLocked(Tile t) {
        int b = entryB(t.level, t.ty);
        int h = hashPacked(t.tx, b, t.tz) & HASH_MASK;
        for (int i = 0; i < MAX_PROBES; i++) {
            int e = h * 4;
            int slot = hash[e + 3];
            if (slot == SLOT_EMPTY || slot == SLOT_DELETED) {
                if (slot == SLOT_DELETED)
                    tombstones--;
                hash[e] = t.tx;
                hash[e + 1] = b;
                hash[e + 2] = t.tz;
                hash[e + 3] = t.slot;
                markEntry(h);
                return true;
            }
            h = (h + 1) & HASH_MASK;
        }
        return false;
    }

    private void rebuildHashLocked() {
        int[] old = hash;
        hash = newHash();
        tombstones = 0;
        for (Tile t : tiles.values()) {
            if (!insertLocked(t)) {
                // cannot happen with a table this empty; drop the tile rather than leave a hole the shader cannot find
                t.removed = true;
                levelCount[t.level]--;
            }
        }
        for (int i = 0; i < HASH_ENTRIES; i++) {
            int e = i * 4;
            if (old[e] != hash[e] || old[e + 1] != hash[e + 1] || old[e + 2] != hash[e + 2] || old[e + 3] != hash[e + 3])
                markEntry(i);
        }
    }

    private void queueTile(Tile t) {
        if (!t.queued) {
            t.queued = true;
            dirtyTiles.add(t);
        }
    }

    private void markEntry(int index) {
        if (entryDirty[index])
            return;
        entryDirty[index] = true;
        if (dirtyEntryCount == dirtyEntryList.length)
            dirtyEntryList = Arrays.copyOf(dirtyEntryList, dirtyEntryCount * 2);
        dirtyEntryList[dirtyEntryCount++] = index;
    }

    // ------------------------------------------------------------------------------------------------------
    // GPU mirror (render thread)
    // ------------------------------------------------------------------------------------------------------

    /**
     * Hands over at most {@code maxTiles} changed tiles and {@code maxEntries} changed hash entries. Tile data always
     * goes first, and an entry that points to a tile whose data has not been handed over yet waits, so the shader never
     * finds a tile whose contents are not there.
     *
     * @return the number of tiles and entries that are still waiting
     */
    public int drain(GpuSink sink, int maxTiles, int maxEntries) {
        synchronized (lock) {
            if (drainedEpoch != epoch) {
                sink.reset();
                drainedEpoch = epoch;
            }
            int n = 0;
            while (n < maxTiles && !dirtyTiles.isEmpty()) {
                Tile t = dirtyTiles.poll();
                t.queued = false;
                if (t.removed)
                    continue;
                sink.tile(t.slot, t.data);
                t.uploaded = true;
                n++;
            }

            int written = 0;
            int keep = 0;
            for (int i = 0; i < dirtyEntryCount; i++) {
                int idx = dirtyEntryList[i];
                int e = idx * 4;
                int slot = hash[e + 3];
                boolean ready = slot < 0 || (bySlot[slot] != null && bySlot[slot].uploaded);
                if (written < maxEntries && ready) {
                    sink.entry(idx, hash[e], hash[e + 1], hash[e + 2], slot);
                    entryDirty[idx] = false;
                    written++;
                } else {
                    dirtyEntryList[keep++] = idx;
                }
            }
            dirtyEntryCount = keep;
            return dirtyTiles.size() + dirtyEntryCount;
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // The light itself (the same arithmetic as the old LOD shader's cl_lightAt)
    // ------------------------------------------------------------------------------------------------------

    /**
     * Works out the colour of the 8x8x8 blocks of one tile (level 0) from the sources around it.
     *
     * @param out receives {@link #TILE_VOXELS} values, 0x00BBGGRR
     * @return true if at least one block has light
     */
    public static boolean computeTile(int[] out, int tx, int ty, int tz, SourceSet sources, Params p) {
        return computeTile(out, 0, tx, ty, tz, sources, p);
    }

    /** Whether a light colour (0x00BBGGRR, any strength) is white by the engine's rule: its weakest channel is within the spread of its brightest. */
    public static boolean isWhite(int rgb, int spread) {
        int r = rgb & 255, g = (rgb >> 8) & 255, b = (rgb >> 16) & 255;
        int top = Math.max(r, Math.max(g, b));
        if (top <= 0)
            return false;
        int min = Math.min(r, Math.min(g, b));
        // the hue scaled to a brightest channel of 255: min * 255 / top >= 255 - spread
        return min * 255 >= (255 - spread) * top;
    }

    /**
     * Works out the colour of the 8x8x8 cells of one tile of {@code level} from the sources around it. A cell is
     * {@code 1 << level} blocks wide and is lit as its centre block is.
     *
     * @param out receives {@link #TILE_VOXELS} values, 0x00BBGGRR
     * @return true if at least one cell has light
     */
    public static boolean computeTile(int[] out, int level, int tx, int ty, int tz, SourceSet sources, Params p) {
        Arrays.fill(out, 0);
        final int cell = 1 << level;
        final int half = cell >> 1;
        final int span = TILE_SIZE << level;
        final int minX = tx * span, minY = ty * span, minZ = tz * span;
        final int maxX = minX + span - 1, maxY = minY + span - 1, maxZ = minZ + span - 1;
        // white lights and coloured ones are kept apart so a colour can be laid over a white (see combine)
        final boolean split = p.whiteOverlay;
        final int[] white = split ? new int[TILE_VOXELS] : null;
        final boolean[] any = {false};
        sources.forEachNear(minX - RADIUS, minY - RADIUS, minZ - RADIUS, maxX + RADIUS, maxY + RADIUS, maxZ + RADIUS, (sx, sy, sz, rgb) -> {
            int r = rgb & 255, g = (rgb >> 8) & 255, b = (rgb >> 16) & 255;
            float top = Math.max(r, Math.max(g, b));
            if (top <= 0f)
                return;
            int xi0 = Math.max(0, Math.floorDiv(sx - RADIUS - minX - half + cell - 1, cell)), xi1 = Math.min(TILE_SIZE - 1, Math.floorDiv(sx + RADIUS - minX - half, cell));
            int yi0 = Math.max(0, Math.floorDiv(sy - RADIUS - minY - half + cell - 1, cell)), yi1 = Math.min(TILE_SIZE - 1, Math.floorDiv(sy + RADIUS - minY - half, cell));
            int zi0 = Math.max(0, Math.floorDiv(sz - RADIUS - minZ - half + cell - 1, cell)), zi1 = Math.min(TILE_SIZE - 1, Math.floorDiv(sz + RADIUS - minZ - half, cell));
            if (xi0 > xi1 || yi0 > yi1 || zi0 > zi1)
                return;
            float[] d = p.dist[(rgb >>> 29) & 7];
            int[] into = (split && isWhite(rgb, p.whiteSpread)) ? white : out;
            for (int xi = xi0; xi <= xi1; xi++) {
                int bx = minX + xi * cell + half;
                for (int yi = yi0; yi <= yi1; yi++) {
                    int by = minY + yi * cell + half;
                    int row = ((bx - sx + RADIUS) * SIDE + (by - sy + RADIUS)) * SIDE + RADIUS - sz;
                    int outRow = (xi << 6) + (yi << 3);
                    for (int zi = zi0; zi <= zi1; zi++) {
                        int bz = minZ + zi * cell + half;
                        float loss = d[row + bz] * p.lossPerBlock;
                        float left = top - loss;
                        if (left <= 0f)
                            continue;
                        float vr, vg, vb;
                        if (p.additive) {
                            if (left > 170f) {
                                vr = Math.max(r - loss, 0f);
                                vg = Math.max(g - loss, 0f);
                                vb = Math.max(b - loss, 0f);
                            } else {
                                float freeze = Math.max(top - 170f, 0f);
                                float fr = Math.max(r - freeze, 0f), fg = Math.max(g - freeze, 0f), fb = Math.max(b - freeze, 0f);
                                float k = left / Math.max(Math.max(fr, Math.max(fg, fb)), 1f);
                                vr = fr * k;
                                vg = fg * k;
                                vb = fb * k;
                            }
                        } else {
                            float k = left / top;
                            vr = r * k;
                            vg = g * k;
                            vb = b * k;
                        }
                        int ir = Math.min(255, Math.round(vr)), ig = Math.min(255, Math.round(vg)), ib = Math.min(255, Math.round(vb));
                        if ((ir | ig | ib) == 0)
                            continue;
                        int at = outRow + zi;
                        int cur = into[at];
                        int nr = Math.max(cur & 255, ir), ng = Math.max((cur >> 8) & 255, ig), nb = Math.max((cur >> 16) & 255, ib);
                        into[at] = nr | (ng << 8) | (nb << 16);
                        any[0] = true;
                    }
                }
            }
        });
        if (split && any[0]) {
            for (int i = 0; i < TILE_VOXELS; i++) {
                if (white[i] != 0)
                    out[i] = combine(white[i], out[i], p.whiteCoverPercent);
            }
        }
        return any[0];
    }

    private static int peak(int rgb) {
        return Math.max(rgb & 255, Math.max((rgb >> 8) & 255, (rgb >> 16) & 255));
    }

    /**
     * What a cell shows that holds a white light and a coloured one: the coloured light laid over the white, never darker
     * than the brighter of the two (the same arithmetic as the engine's {@code LightCell.whiteLoses}). With no colour the
     * white is shown as it is.
     */
    static int combine(int white, int colour, int coverPercent) {
        if (colour == 0)
            return white;
        int w = peak(white), c = peak(colour);
        int top = Math.max(w, c);
        int cover = c >= w ? 256 : Math.min(256, c * 25600 / (w * Math.max(1, coverPercent)));
        int under = w; // white: the same level in every channel
        int r = (under * (256 - cover) + ((colour & 255) * top / c) * cover + 128) >> 8;
        int g = (under * (256 - cover) + (((colour >> 8) & 255) * top / c) * cover + 128) >> 8;
        int b = (under * (256 - cover) + (((colour >> 16) & 255) * top / c) * cover + 128) >> 8;
        return Math.min(255, r) | (Math.min(255, g) << 8) | (Math.min(255, b) << 16);
    }
}
