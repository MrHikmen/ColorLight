package me.mrhikmen.colorlight.client.lua;

import net.minecraft.resources.Identifier;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the Lua files of the active resource packs and collects what they define:
 * <pre>
 * assets/colorlight/settings.lua              default settings (range, tint, scan weights, ...)
 * assets/colorlight/propagation/&lt;name&gt;.lua    a light-spreading method, id  colorlight:&lt;name&gt;
 * assets/colorlight/block/&lt;name&gt;.json         block colours / strengths / methods (plain JSON, see {@link BlockJson})
 * </pre>
 * A broken script never breaks the game: its error is logged, listed in {@link LoadedScripts#problems()} and the file
 * is skipped (a propagation method that fails to load simply is not registered, so the built-in one stays).
 */
public final class LuaPackLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger("ColorLight");

    public static final String NAMESPACE = "colorlight";
    public static final String VERSION = "1";

    private final ScriptSource source;
    private final LuaSandbox sandbox = new LuaSandbox();

    private final Map<SettingKey, Object> settings = new EnumMap<>(SettingKey.class);
    private final Map<Identifier, ScriptedPropagationMethod> methods = new LinkedHashMap<>();
    private final BlockRules blocks = new BlockRules();
    private final List<String> problems = new ArrayList<>();

    private ScriptFile current;
    private String currentFolder = "";

    private LuaPackLoader(ScriptSource source) {
        this.source = source;
    }

    public static LoadedScripts load(ScriptSource source) {
        LuaPackLoader loader = new LuaPackLoader(source);
        loader.run();
        return new LoadedScripts(loader.settings, List.copyOf(loader.methods.values()), loader.blocks, List.copyOf(loader.problems));
    }

    private void run() {
        sandbox.run(LuaSandbox.LOAD_BUDGET, () -> sandbox.globals().set("colorlight", buildApi()));

        currentFolder = "";
        for (ScriptFile file : source.settings())
            exec(file, table -> readSettings(table, file));

        currentFolder = "propagation/";
        for (ScriptFile file : source.propagation())
            exec(file, result -> {
                if (!result.isnil())
                    registerPropagation(Identifier.fromNamespaceAndPath(NAMESPACE, file.name()), result, file);
            });

        // block files are plain JSON, no Lua involved
        for (ScriptFile file : source.blocks()) {
            String origin = "[" + file.packId() + "] block/" + file.name() + ".json";
            int before = problems.size();
            BlockJson.parse(file.text(), origin, blocks, problems);
            for (int i = before; i < problems.size(); i++)
                LOGGER.warn("[ColorLight] {}", problems.get(i));
        }

        LOGGER.info("[ColorLight] scripts: {} propagation method(s), {} block rule(s), {} problem(s)",
                methods.size(), blocks.size(), problems.size());
    }

    // ------------------------------------------------------------------------------------

    private interface ResultHandler {
        void accept(LuaValue result);
    }

    private void exec(ScriptFile file, ResultHandler handler) {
        current = file;
        try {
            LuaValue result = sandbox.execute(file.chunkName(currentFolder), file.text());
            handler.accept(result);
        } catch (LuaError e) {
            problem(file, e.getMessage());
        } catch (RuntimeException e) {
            problem(file, e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            current = null;
        }
    }

    private void problem(ScriptFile file, String message) {
        String line = "[" + file.packId() + "] " + currentFolder + file.name() + ".lua: " + message;
        problems.add(line);
        LOGGER.warn("[ColorLight] {}", line);
    }

    private void warn(String message) {
        if (current != null)
            problem(current, message);
        else {
            problems.add(message);
            LOGGER.warn("[ColorLight] {}", message);
        }
    }

    // ------------------------------------------------------------------------------------ settings

    private void readSettings(LuaValue result, ScriptFile file) {
        if (result.isnil())
            return; // the file used colorlight.settings{...} instead
        applySettings(result);
    }

    private void applySettings(LuaValue table) {
        if (!table.istable())
            throw new IllegalArgumentException("settings must be a table");
        LuaValue key = LuaValue.NIL;
        while (true) {
            Varargs next = table.next(key);
            key = next.arg1();
            if (key.isnil())
                break;
            String name = key.tojstring();
            SettingKey setting = SettingKey.byLuaKey(name);
            if (setting == null) {
                warn("unknown setting '" + name + "' (known: " + knownSettings() + ")");
                continue;
            }
            try {
                settings.put(setting, setting.coerce(next.arg(2)));
            } catch (IllegalArgumentException e) {
                warn(e.getMessage());
            }
        }
    }

    private static String knownSettings() {
        StringBuilder sb = new StringBuilder();
        for (SettingKey k : SettingKey.values())
            sb.append(sb.length() == 0 ? "" : ", ").append(k.luaKey);
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------ propagation

    private void registerPropagation(Identifier id, LuaValue table, ScriptFile file) {
        try {
            PropagationSpec spec = PropagationSpec.fromLua(sandbox, table, id.toString());
            String origin = "resource pack '" + file.packId() + "' (" + NAMESPACE + ":" + currentFolder + file.name() + ".lua)";
            methods.put(id, new ScriptedPropagationMethod(id, spec, origin));
        } catch (LuaError | IllegalArgumentException e) {
            warn("propagation '" + id + "' not registered: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------ the colorlight table

    private LuaTable buildApi() {
        LuaTable api = new LuaTable();
        api.set("version", VERSION);

        api.set("faces", offsets(new int[][]{{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}}));
        int[][] all = new int[26][];
        int n = 0;
        for (int x = -1; x <= 1; x++)
            for (int y = -1; y <= 1; y++)
                for (int z = -1; z <= 1; z++)
                    if (x != 0 || y != 0 || z != 0)
                        all[n++] = new int[]{x, y, z};
        api.set("full", offsets(all));

        // ---- propagation
        api.set("propagation", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue name, LuaValue spec) {
                String raw = name.checkjstring();
                String path = raw.contains(":") ? raw : NAMESPACE + ":" + raw;
                Identifier id;
                try {
                    id = Identifier.parse(path);
                } catch (Exception e) {
                    throw new LuaError("colorlight.propagation: invalid id '" + raw + "'");
                }
                if (current == null)
                    throw new LuaError("colorlight.propagation can only be called while a script loads");
                registerPropagation(id, spec, current);
                return NIL;
            }
        });

        // ---- settings
        api.set("settings", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue table) {
                try {
                    applySettings(table);
                } catch (IllegalArgumentException e) {
                    throw new LuaError("colorlight.settings: " + e.getMessage());
                }
                return NIL;
            }
        });

        api.set("log", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                LOGGER.info("[ColorLight/lua] {}", join(args));
                return NONE;
            }
        });
        api.set("warn", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                LOGGER.warn("[ColorLight/lua] {}", join(args));
                return NONE;
            }
        });

        for (Map.Entry<String, LuaValue> extension : ColorLightLua.extensions().entrySet()) {
            if (api.get(extension.getKey()).isnil())
                api.set(extension.getKey(), extension.getValue());
            else
                LOGGER.warn("[ColorLight] a mod tried to replace colorlight.{} from Lua; ignored", extension.getKey());
        }
        return api;
    }

    private static String join(Varargs args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= args.narg(); i++)
            sb.append(i == 1 ? "" : " ").append(args.arg(i).tojstring());
        return sb.toString();
    }

    private static LuaTable offsets(int[][] list) {
        LuaTable table = new LuaTable();
        for (int i = 0; i < list.length; i++) {
            LuaTable o = new LuaTable();
            o.set(1, LuaValue.valueOf(list[i][0]));
            o.set(2, LuaValue.valueOf(list[i][1]));
            o.set(3, LuaValue.valueOf(list[i][2]));
            table.set(i + 1, o);
        }
        return table;
    }
}
