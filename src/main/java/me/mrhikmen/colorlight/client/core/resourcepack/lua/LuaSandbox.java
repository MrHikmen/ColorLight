package me.mrhikmen.colorlight.client.core.resourcepack.lua;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LoadState;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.Bit32Lib;
import org.luaj.vm2.lib.DebugLib;
import org.luaj.vm2.lib.PackageLib;
import org.luaj.vm2.lib.StringLib;
import org.luaj.vm2.lib.TableLib;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.jse.JseBaseLib;
import org.luaj.vm2.lib.jse.JseMathLib;

import java.io.Reader;
import java.io.StringReader;
import java.util.concurrent.Callable;

/**
 * A locked-down Lua environment for resource-pack scripts.
 * <p>
 * Scripts get {@code base} (without {@code dofile}/{@code loadfile}/{@code require}), {@code table},
 * {@code string}, {@code math}, {@code bit32} and the {@code colorlight} table - nothing else: no {@code io}, no
 * {@code os}, no {@code package}, no {@code debug}, no Java reflection. A resource pack is downloaded content, so it
 * must never be able to touch the player's disk or network.
 * <p>
 * Every call into Lua goes through {@link #run} which enforces an <b>instruction budget</b>: a script that loops
 * forever is aborted with an error instead of freezing the game. LuaJ's {@code Globals} is not thread-safe, so
 * every entry into Lua also serialises on one lock ({@link #run} takes it for you).
 */
public final class LuaSandbox {

    /** Budget (in VM instructions) for a load-time script or a single load-time callback. */
    public static final long LOAD_BUDGET = 20_000_000L;
    /** Budget for one call of a runtime callback, e.g. a scripted {@code propagate}. */
    public static final long CALLBACK_BUDGET = 40_000_000L;

    private static final int HOOK_STEP = 10_000;

    private final Globals globals;
    private final Object lock = new Object();

    private long remaining;   // guarded by lock
    private boolean depleted; // guarded by lock

    public LuaSandbox() {
        Globals g = new Globals();
        g.load(new JseBaseLib());
        g.load(new PackageLib()); // the other libraries register themselves in package.loaded, so it must exist; it is hidden again below
        g.load(new TableLib());
        g.load(new StringLib());
        g.load(new JseMathLib());
        g.load(new Bit32Lib());
        DebugLib debug = new DebugLib(); // only to get instruction hooks; the table is removed from the globals below
        g.load(debug);
        LoadState.install(g);
        LuaC.install(g);

        // nothing that reads files, and nothing that can be used to escape the sandbox
        for (String name : new String[]{"dofile", "loadfile", "require", "module", "package", "io", "os", "luajava", "collectgarbage"})
            g.set(name, LuaValue.NIL);
        // "load" can compile strings only (text mode, sandbox globals as environment); keep it but drop string.dump
        LuaValue string = g.get("string");
        if (string.istable())
            string.set("dump", LuaValue.NIL);

        // instruction budget through a count hook
        LuaValue sethook = g.get("debug").get("sethook");
        LuaValue hook = new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue event, LuaValue arg) {
                remaining -= HOOK_STEP;
                if (remaining < 0) {
                    depleted = true;
                    throw new LuaError("script exceeded its instruction budget (endless loop?)");
                }
                return NIL;
            }
        };
        sethook.invoke(LuaValue.varargsOf(new LuaValue[]{hook, LuaValue.valueOf(""), LuaValue.valueOf(HOOK_STEP)}));
        g.set("debug", LuaValue.NIL);

        this.globals = g;
    }

    /** The global table scripts see. Only touch it from inside {@link #run}. */
    public Globals globals() {
        return globals;
    }

    /** Runs {@code body} holding the Lua lock with a fresh instruction budget. */
    public <T> T run(long budget, Callable<T> body) throws LuaError {
        synchronized (lock) {
            remaining = budget;
            depleted = false;
            try {
                return body.call();
            } catch (LuaError e) {
                throw e;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new LuaError(e);
            }
        }
    }

    public void run(long budget, Runnable body) throws LuaError {
        run(budget, () -> {
            body.run();
            return null;
        });
    }

    /** Compiles and executes {@code source} as a chunk; returns whatever the chunk returns (first value, or NIL). */
    public LuaValue execute(String chunkName, String source) throws LuaError {
        return run(LOAD_BUDGET, () -> {
            try (Reader reader = new StringReader(source)) {
                LuaValue chunk = globals.load(reader, chunkName);
                Varargs result = chunk.invoke();
                return result.arg1();
            } catch (java.io.IOException e) {
                throw new LuaError(e);
            }
        });
    }

    /** Calls a Lua function with a fresh budget. */
    public Varargs call(long budget, LuaValue function, LuaValue... args) throws LuaError {
        return run(budget, () -> function.invoke(LuaValue.varargsOf(args)));
    }

    /** Convenience for building the {@code colorlight} table and other library tables. */
    public static LuaTable newTable() {
        return new LuaTable();
    }
}
