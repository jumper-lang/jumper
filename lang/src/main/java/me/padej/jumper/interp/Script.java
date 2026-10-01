package me.padej.jumper.interp;

import me.padej.jumper.ast.FunctionNode;
import me.padej.jumper.ast.VarType;
import me.padej.jumper.runtime.JFunction;
import me.padej.jumper.runtime.JmpError;

import java.util.Map;
import java.util.Set;

/**
 * Parsed script with access to its top-level variables after execution
 * (for ScriptEngine.get/invokeFunction and the REPL). The top level runs in a Frame
 * that is kept; nested functions are compiled as usual.
 */
public final class Script {
    private final FunctionNode main;
    private final FunctionNode.ScriptFunction fn;

    Script(FunctionNode main) {
        this.main = main;
        main.forceFrame = true;
        this.fn = new FunctionNode.ScriptFunction(main, null);
    }

    public Object run() {
        return fn.call(new Object[0]);
    }

    public Set<String> names() {
        return main.topLevel.keySet();
    }

    public boolean has(String name) {
        return main.topLevel.containsKey(name);
    }

    /** Value of a top-level variable after run(); null if there is none or the script has not run. */
    public Object get(String name) {
        int[] info = main.topLevel.get(name);
        Frame f = fn.lastFrame;
        if (info == null || f == null) return null;
        VarType t = VarType.values()[info[2]];
        return info[1] == 1 ? t.fromBits(f.p[info[0]]) : f.slots[info[0]];
    }

    public void set(String name, Object value) {
        int[] info = main.topLevel.get(name);
        Frame f = fn.lastFrame;
        if (info == null) throw new JmpError("No top-level variable '" + name + "'");
        if (f == null) throw new JmpError("Script has not been run yet");
        VarType t = VarType.values()[info[2]];
        if (info[1] == 1) f.p[info[0]] = t.toBits(value);
        else f.slots[info[0]] = t.coerce(value);
    }

    /** Call a top-level function by name. */
    public Object invoke(String name, Object... args) {
        Object v = get(name);
        if (v instanceof JFunction jf) return jf.call(args);
        throw new JmpError("'" + name + "' is not a function" + (has(name) ? "" : " (not defined)"));
    }

    public JFunction function() {
        return fn;
    }
}
