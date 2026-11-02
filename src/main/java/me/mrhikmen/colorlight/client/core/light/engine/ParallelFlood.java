package me.mrhikmen.colorlight.client.core.light.engine;

import me.mrhikmen.colorlight.client.core.light.propagation.LightPropagator;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.LevelAccessor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * Floods several lights at the same time on worker threads.
 * <p>
 * Why this is safe: a flood only ever <i>reads</i> the world (block opacity) and writes its own private scratch values,
 * and the result of several floods is the per-cell maximum of each one alone (see {@code TablePropagator}), so the
 * floods do not depend on each other or on the order they finish in. The workers therefore never touch the engine's
 * storage: each one collects what it lights ({@link CollectingField}), and the thread that holds the engine lock stores
 * the collected values one task after another ({@link WorldLightField#raise}, a maximum again). That makes the stored
 * result identical to the single-threaded one.
 * <p>
 * The engine lock stays held by the caller the whole time, so nothing else changes the stored light meanwhile.
 */
final class ParallelFlood {

    /** Threads used by default: {@code -Dcolorlight.threads=N}, else about a third of the machine's threads (1 = off). */
    static final int DEFAULT_THREADS = Math.max(1, Integer.getInteger("colorlight.threads",
            Math.min(6, Math.max(1, Runtime.getRuntime().availableProcessors() / 3))));
    /** Fewer seeds than this in total are not worth handing to other threads. */
    static final int MIN_SEEDS = Integer.getInteger("colorlight.parallel.min", 6);

    /** One flood: the seeds {@code [from, to)} of one colour spread with one method. */
    record Task(Identifier method, int id, long[] keys, int[] levels, int from, int to) {
        int size() {
            return to - from;
        }
    }

    private static final class Context {
        final CollectingField field;
        final Map<Identifier, LightPropagator> propagators = new HashMap<>();

        Context(LevelAccessor level) {
            this.field = new CollectingField(level);
        }
    }

    private static final class Result {
        final long[] keys;
        final int[] levels;
        final int count;

        Result(long[] keys, int[] levels, int count) {
            this.keys = keys;
            this.levels = levels;
            this.count = count;
        }
    }

    private static ThreadPoolExecutor pool;
    /** How many times floods were handed to worker threads (for tests and the profile). */
    static final java.util.concurrent.atomic.AtomicLong RUNS = new java.util.concurrent.atomic.AtomicLong();

    private static synchronized ThreadPoolExecutor pool() {
        if (pool == null) {
            int n = Math.max(2, DEFAULT_THREADS);
            pool = new ThreadPoolExecutor(n, n, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), runnable -> {
                Thread t = new Thread(runnable, "ColorLight-Flood");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
            pool.allowCoreThreadTimeOut(true);
        }
        return pool;
    }

    /** Sets how many worker threads flood at the same time (the settings screen calls this). */
    static synchronized void resize(int threads) {
        int n = Math.max(2, threads);
        ThreadPoolExecutor p = pool();
        if (n > p.getMaximumPoolSize()) {
            p.setMaximumPoolSize(n);
            p.setCorePoolSize(n);
        } else {
            p.setCorePoolSize(n);
            p.setMaximumPoolSize(n);
        }
    }

    private final LevelAccessor level;
    /** Makes a propagator for a method; called on the worker threads. */
    private final Function<Identifier, LightPropagator> propagatorFactory;
    private final ConcurrentLinkedQueue<Context> idle = new ConcurrentLinkedQueue<>();

    ParallelFlood(LevelAccessor level, Function<Identifier, LightPropagator> propagatorFactory) {
        this.level = level;
        this.propagatorFactory = propagatorFactory;
    }

    /** Adds the seeds {@code [from, to)} as floods of at most {@code maxSeeds} seeds each. */
    static void addSlices(List<Task> out, Identifier method, int id, long[] keys, int[] levels, int from, int to, int maxSeeds) {
        for (int start = from; start < to; start += maxSeeds)
            out.add(new Task(method, id, keys, levels, start, Math.min(to, start + maxSeeds)));
    }

    /**
     * Floods every task on the worker pool and stores the results through {@code target}, in task order.
     *
     * @param sequential runs one task on the calling thread, used if a worker failed
     */
    void run(List<Task> tasks, WorldLightField target, java.util.function.Consumer<Task> sequential) {
        RUNS.incrementAndGet();
        ExecutorService executor = pool();
        List<Future<Result>> futures = new ArrayList<>(tasks.size());
        for (Task task : tasks)
            futures.add(executor.submit(() -> flood(task)));

        for (int i = 0; i < tasks.size(); i++) {
            Task task = tasks.get(i);
            Result result;
            try {
                result = futures.get(i).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sequential.accept(task);
                continue;
            } catch (ExecutionException e) {
                me.mrhikmen.colorlight.client.ColorLightClient.LOGGER.warn("[ColorLight] a flood failed on a worker thread, redoing it here", e.getCause());
                sequential.accept(task);
                continue;
            }
            for (int k = 0; k < result.count; k++) {
                long key = result.keys[k];
                target.raise(me.mrhikmen.colorlight.client.core.light.util.PosKey.x(key),
                        me.mrhikmen.colorlight.client.core.light.util.PosKey.y(key),
                        me.mrhikmen.colorlight.client.core.light.util.PosKey.z(key), task.id(), result.levels[k]);
            }
        }
    }

    private Result flood(Task task) {
        Context ctx = idle.poll();
        if (ctx == null)
            ctx = new Context(level);
        try {
            LightPropagator propagator = ctx.propagators.computeIfAbsent(task.method(), propagatorFactory);
            int n = task.size();
            long[] keys = new long[n];
            int[] levels = new int[n];
            System.arraycopy(task.keys(), task.from(), keys, 0, n);
            System.arraycopy(task.levels(), task.from(), levels, 0, n);

            propagator.spread(task.id(), keys, levels, n, ctx.field);

            int lit = ctx.field.count();
            long[] outKeys = new long[lit];
            int[] outLevels = new int[lit];
            int count = ctx.field.drain(outKeys, outLevels);
            return new Result(outKeys, outLevels, count);
        } finally {
            idle.add(ctx);
        }
    }
}
