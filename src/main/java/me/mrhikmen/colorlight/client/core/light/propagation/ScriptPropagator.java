package me.mrhikmen.colorlight.client.core.light.propagation;

import me.mrhikmen.colorlight.client.core.light.util.LongQueue;
import me.mrhikmen.colorlight.client.core.light.util.PosKey;
import me.mrhikmen.colorlight.client.lua.LuaSandbox;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.ThreeArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link LightPropagator} whose whole flood-fill is a Lua function, for resource packs that need a shape the
 * table-driven kernel cannot express. The function is called once per pass as {@code propagate(field, queue)}:
 * <pre>
 * field.get(x, y, z)            -> r, g, b        (0..255 each)
 * field.set(x, y, z, r, g, b)                     (the cell's owner flags are kept)
 * field.opacity(x, y, z)        -> 0..15
 * queue.poll()                  -> x, y, z        or nil when the queue is empty
 * queue.add(x, y, z)
 * </pre>
 * Runs under the sandbox's instruction budget; if the script fails, the pass is abandoned (the queue is dropped)
 * and the error is logged once. Interpreted Lua is far slower than {@link TablePropagator}: keep such methods for
 * small or rare effects.
 */
public final class ScriptPropagator implements LightPropagator {

    private static final Logger LOGGER = LoggerFactory.getLogger("ColorLight");

    private final LuaSandbox sandbox;
    private final LuaValue function;
    private final String name;
    private final float decayPerOpacityUnit;
    private final int maxOpacity;

    private final LuaTable fieldTable = new LuaTable();
    private final LuaTable queueTable = new LuaTable();
    private final LuaValue context = new LuaTable();

    private LightField field;
    private LongQueue queue;
    private boolean failed;

    public ScriptPropagator(LuaSandbox sandbox, LuaValue function, String name, float decayPerOpacityUnit, int maxOpacity) {
        this.sandbox = sandbox;
        this.function = function;
        this.name = name;
        this.decayPerOpacityUnit = decayPerOpacityUnit;
        this.maxOpacity = maxOpacity;

        fieldTable.set("get", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                int cell = ScriptPropagator.this.field.get(args.checkint(1), args.checkint(2), args.checkint(3));
                return varargsOf(new LuaValue[]{valueOf(cell & 0xFF), valueOf((cell >> 8) & 0xFF), valueOf((cell >> 16) & 0xFF)});
            }
        });
        fieldTable.set("set", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                int x = args.checkint(1), y = args.checkint(2), z = args.checkint(3);
                int r = clamp(args.checkint(4)), g = clamp(args.checkint(5)), b = clamp(args.checkint(6));
                LightField f = ScriptPropagator.this.field;
                int old = f.get(x, y, z);
                int colour = r | (g << 8) | (b << 16);
                if (colour != (old & PropagationTables.RGB_MASK))
                    f.set(x, y, z, (old & PropagationTables.FLAG_MASK) | colour);
                return NONE;
            }
        });
        fieldTable.set("opacity", new ThreeArgFunction() {
            @Override
            public LuaValue call(LuaValue x, LuaValue y, LuaValue z) {
                return valueOf(ScriptPropagator.this.field.opacityAt(x.checkint(), y.checkint(), z.checkint()));
            }
        });

        queueTable.set("poll", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                LongQueue q = ScriptPropagator.this.queue;
                if (q.isEmpty())
                    return NIL;
                long key = q.poll();
                return varargsOf(new LuaValue[]{valueOf(PosKey.x(key)), valueOf(PosKey.y(key)), valueOf(PosKey.z(key))});
            }
        });
        queueTable.set("add", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                ScriptPropagator.this.queue.add(PosKey.pack(args.checkint(1), args.checkint(2), args.checkint(3)));
                return NONE;
            }
        });
        queueTable.set("empty", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return valueOf(ScriptPropagator.this.queue.isEmpty());
            }
        });

        context.set("decay", decayPerOpacityUnit);
        context.set("range", Math.round(255f / Math.max(decayPerOpacityUnit, 0.0001f)));
        context.set("max_opacity", maxOpacity);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    @Override
    public void propagate(LongQueue queue, LightField field) {
        field.beginPass();
        this.field = field;
        this.queue = queue;
        try {
            sandbox.call(LuaSandbox.CALLBACK_BUDGET, function, fieldTable, queueTable, context);
        } catch (LuaError e) {
            if (!failed) {
                failed = true;
                LOGGER.warn("[ColorLight] propagation script '{}' failed and its pass was dropped: {}", name, e.getMessage());
            }
            queue.clear();
        } finally {
            this.field = null;
            this.queue = null;
            field.endPass();
        }
    }
}
