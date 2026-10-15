package me.mrhikmen.colorlight.client.core.light.scan;

import me.mrhikmen.colorlight.client.ColorLightClient;
import me.mrhikmen.colorlight.client.config.BlockSettings;
import me.mrhikmen.colorlight.client.core.light.registry.ColorLightBlockRegistry;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngine;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngineHolder;
import me.mrhikmen.colorlight.client.core.light.engine.SourceBatch;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Finds light-emitting blocks in freshly loaded chunks (off the game thread) and hands them to the
 * engine. Which render sections need rebuilding afterwards is decided by the engine itself from the
 * cells it actually changed - the old code marked every chunk with a source dirty for its full
 * height (~380 sections) plus a light-range margin on all sides.
 */
public final class ColorLightChunkScanner {

    /**
     * Background workers must not fight the game thread and Sodium's mesh workers for CPU while chunks
     * stream in: few threads, slightly lower priority.
     */
    private static final NearestFirstPool SCAN_EXECUTOR = new NearestFirstPool(
            Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() / 4)));

    private static final ExecutorService APPLY_EXECUTOR = Executors.newSingleThreadExecutor(
            runnable -> backgroundThread(runnable, "ColorLight Source Apply"));

    /** Chunks unloaded on the game thread, waiting for the apply thread to forget their light (see {@link #queueUnload}). */
    private static final ConcurrentLinkedQueue<Long> PENDING_UNLOADS = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean UNLOAD_DRAIN_SCHEDULED = new AtomicBoolean();

    /** Tasks queued on or running on the apply thread. Zero means no flood is in progress anywhere. */
    private static final AtomicInteger APPLY_IN_FLIGHT = new AtomicInteger();

    /**
     * True when the apply thread has nothing queued or running. A block change may then be judged (lock-free) as
     * "no light nearby, ignore" - with a flood possibly in progress that judgement could be stale, because the
     * light has just not reached the block yet.
     */
    public static boolean isApplyIdle() {
        return APPLY_IN_FLIGHT.get() == 0;
    }

    private static void submitApply(Runnable task) {
        APPLY_IN_FLIGHT.incrementAndGet();
        APPLY_EXECUTOR.execute(() -> {
            try {
                task.run();
            } finally {
                APPLY_IN_FLIGHT.decrementAndGet();
            }
        });
    }

    private static Thread backgroundThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        return thread;
    }

    public static void register() {
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
            if (!ColorLightClient.config.ENABLE)
                return;
            if (ColorLightBlockRegistry.isEmpty())
                return;

            SCAN_EXECUTOR.execute(chunk.getPos().x(), chunk.getPos().z(), () -> scanChunk(level, chunk));
        });

        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            ChunkPos pos = chunk.getPos();
            queueUnload(pos.x(), pos.z());
        });
    }

    /**
     * Runs an engine mutation on the apply thread, in FIFO order with chunk loads and unloads.
     * The game thread must never wait for the engine lock (a flood on the apply thread can hold it for
     * milliseconds back to back), so anything that needs it - block edits included - goes through here.
     */
    public static void runOnApplyThread(Runnable task) {
        submitApply(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                ColorLightClient.LOGGER.warn("[ColorLight] Light update failed", t);
            }
        });
    }

    /**
     * Unloads arrive on the game thread in bursts while flying. Forgetting a chunk's light has to wait for the
     * engine lock (which the apply thread keeps busy flooding new chunks) and walk the source table, so it is
     * done by the apply thread - in one batch for everything that unloaded meanwhile - and the game thread
     * only appends to a queue. Because the apply thread is single and FIFO, an unload always runs before the
     * sources of a later re-load of the same chunk are applied.
     */
    private static void queueUnload(int chunkX, int chunkZ) {
        PENDING_UNLOADS.add(ColorLightEngine.chunkKey(chunkX, chunkZ));

        if (UNLOAD_DRAIN_SCHEDULED.compareAndSet(false, true)) {
            submitApply(ColorLightChunkScanner::drainUnloads);
        }
    }

    private static void drainUnloads() {
        // clear the flag first: an unload queued while draining schedules a fresh drain instead of being lost
        UNLOAD_DRAIN_SCHEDULED.set(false);
        try {
            ColorLightEngine engine = ColorLightEngineHolder.get();

            long[] batch = new long[16];
            int count = 0;
            Long key;
            while ((key = PENDING_UNLOADS.poll()) != null) {
                if (count == batch.length)
                    batch = java.util.Arrays.copyOf(batch, count << 1);
                batch[count++] = key;
            }

            if (engine != null && count > 0)
                engine.removeChunks(java.util.Arrays.copyOf(batch, count));
        } catch (Throwable t) {
            ColorLightClient.LOGGER.warn("[ColorLight] Failed to release unloaded chunks", t);
        }
    }

    public static void rescanAll(ClientLevel level) {
        if (level == null)
            return;

        var player = Minecraft.getInstance().player;
        if (player == null)
            return;

        int renderDistance = Minecraft.getInstance().options.renderDistance().get();
        ChunkPos center = player.chunkPosition();

        for (int dx = -renderDistance; dx <= renderDistance; dx++) {
            for (int dz = -renderDistance; dz <= renderDistance; dz++) {

                int x = center.x() + dx;
                int z = center.z() + dz;

                if (level.hasChunk(x, z)) {
                    LevelChunk chunk = level.getChunk(x, z);
                    SCAN_EXECUTOR.execute(chunk.getPos().x(), chunk.getPos().z(), () -> scanChunk(level, chunk));
                }
            }
        }
    }

    private static void scanChunk(ClientLevel level, LevelChunk chunk) {
        try {
            if (ColorLightBlockRegistry.isEmpty())
                return;

            ColorLightEngine engine = ColorLightEngineHolder.get();
            if (engine == null)
                return;

            SourceBatch found = new SourceBatch();

            chunk.findBlockLightSources((pos, state) -> {
                BlockSettings entry = ColorLightBlockRegistry.get(state.getBlock());
                if (entry == null)
                    return;

                int emission = state.getLightEmission();
                if (emission <= 0)
                    return;

                // No "already a source?" shortcut here: an unload of this same chunk may still be queued, so
                // whether the source exists is only known on the apply thread, where adding an identical
                // source is a cheap no-op.

                // pos may be a reused mutable position: read the ints right away
                found.add(pos.getX(), pos.getY(), pos.getZ(), entry.r, entry.g, entry.b,
                        Math.min(entry.light, emission), entry.getPropagationId());
            });

            if (!found.isEmpty()) {
                var player = Minecraft.getInstance().player;
                if (player != null)
                    found.sortByDistance(player.getBlockX(), player.getBlockY(), player.getBlockZ());
                ChunkPos chunkPos = chunk.getPos();
                submitApply(() -> applyFound(level, chunkPos, engine, found));
            }
        } catch (Throwable t) {
            ColorLightClient.LOGGER.warn("[ColorLight] Failed to scan chunk {}", chunk.getPos(), t);
        }
    }

    private static void applyFound(ClientLevel level, ChunkPos chunkPos, ColorLightEngine engine, SourceBatch found) {
        try {
            // the chunk may have unloaded, or the world been replaced, while this was queued
            if (engine != ColorLightEngineHolder.get() || !level.hasChunk(chunkPos.x(), chunkPos.z()))
                return;

            engine.addSources(found);
        } catch (Throwable t) {
            ColorLightClient.LOGGER.warn("[ColorLight] Failed to apply light sources of chunk {}", chunkPos, t);
        }
    }

    /**
     * A small worker pool that always takes the waiting chunk closest to the player next. The distance is
     * measured when a worker picks a task, not when it was queued, so it follows the player while they move.
     * Scan results are applied in the order they finish, so light shows up around the player first.
     */
    private static final class NearestFirstPool {
        private record Task(int chunkX, int chunkZ, Runnable run) {
        }

        private final ArrayList<Task> waiting = new ArrayList<>();
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition available = lock.newCondition();

        NearestFirstPool(int threads) {
            for (int i = 0; i < threads; i++)
                backgroundThread(this::work, "ColorLight Chunk Scanner").start();
        }

        void execute(int chunkX, int chunkZ, Runnable run) {
            lock.lock();
            try {
                waiting.add(new Task(chunkX, chunkZ, run));
                available.signal();
            } finally {
                lock.unlock();
            }
        }

        private void work() {
            while (true) {
                Task task;
                lock.lock();
                try {
                    while (waiting.isEmpty())
                        available.awaitUninterruptibly();
                    task = takeNearest();
                } finally {
                    lock.unlock();
                }

                try {
                    task.run().run();
                } catch (Throwable t) {
                    ColorLightClient.LOGGER.warn("[ColorLight] Chunk scan task failed", t);
                }
            }
        }

        /** Call with the lock held and {@code waiting} non-empty. */
        private Task takeNearest() {
            int centerX = 0, centerZ = 0;
            var player = Minecraft.getInstance().player;
            if (player != null) {
                centerX = player.getBlockX() >> 4;
                centerZ = player.getBlockZ() >> 4;
            }

            int best = 0;
            long bestDist = Long.MAX_VALUE;
            for (int i = 0, n = waiting.size(); i < n; i++) {
                Task t = waiting.get(i);
                long dx = t.chunkX - centerX, dz = t.chunkZ - centerZ;
                long dist = dx * dx + dz * dz;
                if (dist < bestDist) {
                    bestDist = dist;
                    best = i;
                }
            }

            int last = waiting.size() - 1;
            Task chosen = waiting.get(best);
            waiting.set(best, waiting.get(last)); // swap-remove: order of the rest doesn't matter
            waiting.remove(last);
            return chosen;
        }
    }

    private ColorLightChunkScanner() {
    }
}
