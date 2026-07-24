package me.mrhikmen.colorlight.light;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Независимый от рендера движок распространения цветного света.
 * Хранит данные ОТДЕЛЬНО от ванильного skylight/blocklight — их трогать не нужно.
 *
 * Каждый канал (R,G,B) распространяется собственным BFS, по той же механике,
 * что и ванильный blocklight, только параллельно по трём каналам.
 *
 * ВАЖНО про потоки: чтение (getColor/hasSource) происходит из фоновых потоков
 * построения мешей чанков (Sodium строит их асинхронно), а запись (addSource/
 * removeSource/onBlockChanged) — с основного клиентского потока (mixin/команды).
 * Поэтому обе карты — ConcurrentHashMap, а не обычные HashMap/fastutil-карты:
 * без этого чтение во время записи может упасть с ArrayIndexOutOfBoundsException
 * прямо посреди ресайза карты.
 */
public class ColorLightEngine {

    public static final int MAX_RANGE_BLOCKS = 15;

    private static final int VANILLA_MAX_OPACITY = 15;

    private static final float DECAY_PER_OPACITY_UNIT = ColorLightUtil.MAX / (float) MAX_RANGE_BLOCKS;

    private final ConcurrentHashMap<Long, Integer> data = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Integer> sources = new ConcurrentHashMap<>();

    private final LevelAccessor level;

    public ColorLightEngine(LevelAccessor level) {
        this.level = level;
    }

    public int getColor(BlockPos pos) {
        return getRaw(pos.asLong());
    }

    public boolean hasSource(BlockPos pos) {
        return sources.containsKey(pos.asLong());
    }

    public void clearAll() {
        data.clear();
        sources.clear();
    }

    private int getRaw(long key) {
        Integer v = data.get(key);
        return v != null ? v : ColorLightUtil.EMPTY;
    }

    public void addSource(BlockPos pos, int r, int g, int b) {
        int packed = ColorLightUtil.pack(r, g, b);
        long key = pos.asLong();

        sources.put(key, packed);
        data.put(key, packed);

        ArrayDeque<Long> queue = new ArrayDeque<>();
        queue.add(key);
        propagateAdd(queue);
    }

    private void propagateAdd(ArrayDeque<Long> queue) {
        while (!queue.isEmpty()) {
            long key = queue.poll();
            BlockPos pos = BlockPos.of(key);
            int current = getRaw(key);

            int r = ColorLightUtil.r(current);
            int g = ColorLightUtil.g(current);
            int b = ColorLightUtil.b(current);

            if (r == 0 && g == 0 && b == 0)
                continue;

            for (Direction dir : Direction.values()) {

                BlockPos neighborPos = pos.relative(dir);
                long neighborKey = neighborPos.asLong();

                int opacity = getOpacity(neighborPos);
                if (opacity >= VANILLA_MAX_OPACITY)
                    continue;

                int decay = Math.round((1 + opacity) * DECAY_PER_OPACITY_UNIT);

                int nr = Math.max(0, r - decay);
                int ng = Math.max(0, g - decay);
                int nb = Math.max(0, b - decay);

                if (nr == 0 && ng == 0 && nb == 0)
                    continue;

                int neighborCurrent = getRaw(neighborKey);
                int cr = ColorLightUtil.r(neighborCurrent);
                int cg = ColorLightUtil.g(neighborCurrent);
                int cb = ColorLightUtil.b(neighborCurrent);

                boolean changed = false;
                int fr = cr, fg = cg, fb = cb;

                if (nr > cr) { fr = nr; changed = true; }
                if (ng > cg) { fg = ng; changed = true; }
                if (nb > cb) { fb = nb; changed = true; }

                if (changed) {
                    data.put(neighborKey, ColorLightUtil.pack(fr, fg, fb));
                    queue.add(neighborKey);
                }
            }
        }
    }

    public void removeSource(BlockPos pos) {
        long key = pos.asLong();
        Integer sourceColor = sources.remove(key);
        if (sourceColor == null)
            return; // тут не было зарегистрированного источника

        int old = getRaw(key);
        data.put(key, ColorLightUtil.EMPTY);

        ArrayDeque<Long> relightSeeds = darkenAndCollectSeeds(key, old);
        propagateAdd(relightSeeds);
    }

    private ArrayDeque<Long> darkenAndCollectSeeds(long startKey, int oldColorAtStart) {

        ArrayDeque<long[]> darkenQueue = new ArrayDeque<>();
        ArrayDeque<Long> relightSeeds = new ArrayDeque<>();

        if (!ColorLightUtil.isEmpty(oldColorAtStart)) {
            darkenQueue.add(new long[]{
                    startKey,
                    ColorLightUtil.r(oldColorAtStart),
                    ColorLightUtil.g(oldColorAtStart),
                    ColorLightUtil.b(oldColorAtStart)
            });
        }

        while (!darkenQueue.isEmpty()) {

            long[] entry = darkenQueue.poll();
            long curKey = entry[0];
            int r = (int) entry[1];
            int g = (int) entry[2];
            int b = (int) entry[3];

            BlockPos curPos = BlockPos.of(curKey);

            for (Direction dir : Direction.values()) {

                BlockPos neighborPos = curPos.relative(dir);
                long neighborKey = neighborPos.asLong();

                if (sources.containsKey(neighborKey)) {
                    relightSeeds.add(neighborKey);
                    continue;
                }

                int neighborPacked = getRaw(neighborKey);
                int nr = ColorLightUtil.r(neighborPacked);
                int ng = ColorLightUtil.g(neighborPacked);
                int nb = ColorLightUtil.b(neighborPacked);

                if (nr == 0 && ng == 0 && nb == 0)
                    continue;

                boolean darkened = false;
                int fr = nr, fg = ng, fb = nb;

                if (nr != 0 && nr < r) { fr = 0; darkened = true; }
                if (ng != 0 && ng < g) { fg = 0; darkened = true; }
                if (nb != 0 && nb < b) { fb = 0; darkened = true; }

                if (darkened) {
                    data.put(neighborKey, ColorLightUtil.pack(fr, fg, fb));
                    darkenQueue.add(new long[]{neighborKey, nr, ng, nb});
                }

                if (nr >= r || ng >= g || nb >= b) {
                    relightSeeds.add(neighborKey);
                }
            }
        }

        return relightSeeds;
    }

    public void onBlockChanged(BlockPos pos) {
        long key = pos.asLong();

        if (sources.containsKey(key))
            return;

        int old = getRaw(key);
        data.put(key, ColorLightUtil.EMPTY);

        ArrayDeque<Long> relightSeeds = darkenAndCollectSeeds(key, old);

        relightSeeds.add(key);
        for (Direction dir : Direction.values()) {
            relightSeeds.add(pos.relative(dir).asLong());
        }

        propagateAdd(relightSeeds);
    }

    public float getDaylightFactor(BlockPos pos) {
        if (!(level instanceof Level realLevel))
            return 0f;

        float skyExposure = realLevel.getBrightness(LightLayer.SKY, pos) / 15f;

        float timeOfDayFactor = 1f - (realLevel.getSkyDarken() / 11f);

        return ColorLightUtil.clamp01(skyExposure * timeOfDayFactor);
    }

    private int getOpacity(BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return Math.max(0, Math.min(VANILLA_MAX_OPACITY, state.getLightBlock(level, pos)));
    }
}