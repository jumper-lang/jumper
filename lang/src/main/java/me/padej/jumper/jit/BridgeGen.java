package me.padej.jumper.jit;

import me.padej.jumper.ast.FunctionNode;
import me.padej.jumper.ast.VarType;
import me.padej.jumper.runtime.Opts;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * A script function as an implementation of a Java functional interface - without {@code Proxy}.
 *
 * <p>Previously {@code list.forEach(x -> ...)} went through {@code Proxy.newProxyInstance}: a reflective
 * {@code InvocationHandler}, argument collection into {@code Object[]}, the generic {@code JFunction.call},
 * {@code Interop.convert} of the result. Measured in {@code Interop.proxy}: 7.1 ns per call versus 3.1 for
 * a Java lambda - and on interop_callback that is the entire gap (12.5 million calls across the boundary).
 *
 * <p>Now, per (interface, function node) pair, a class
 * {@code Br_N implements I { Fn f; R m(P...) { return f.body(...); } }} is generated once in the interpreter's loader:
 * the single abstract method of the interface calls the function body directly, with a static
 * signature, arguments and result are converted in place. C2 sees the exact type of the field and inlines the body.
 * When a bridge does not fit (the interface is not functional, types outside {@code Object/int/long/double/boolean},
 * the interface is not visible from the loader, the function is not compiled or the arity does not match)
 * the old Proxy remains: it covers everything.
 *
 * <p>Bridge constructors are cached in the {@link FunctionNode} itself ({@code bridges}), not in statics:
 * a static map with the node as key would keep the interpreter's loader alive forever.
 */
public final class BridgeGen {
    private BridgeGen() {}

    public static final boolean ENABLED = Opts.on("bridge");
    private static final String OBJ = "java/lang/Object", OBJ_D = "Ljava/lang/Object;";
    private static final String OPS = "me/padej/jumper/runtime/Ops";
    private static final String CF = "me/padej/jumper/jit/CompiledFunction";
    private static int seq;

    /** Bridge instance, or null - then the caller builds a Proxy. */
    public static Object bridge(Class<?> iface, FunctionNode.ScriptFunction sf) {
        if (!ENABLED || !Jit.ENABLED) return null;
        FunctionNode fn = sf.node;
        try {
            Constructor<?> k = constructorFor(iface, fn);
            if (k == null) return null;
            CompiledFunction cf = fn.jitStaticBody ? null : sf.compiled();
            if (!fn.jitStaticBody && cf == null) return null;
            return k.newInstance(cf);
        } catch (Throwable t) {
            if (Jit.DEBUG) System.err.println("[bridge] failed " + iface.getName() + " <- " + fn.name + ": " + t);
            return null;
        }
    }

    private static Constructor<?> constructorFor(Class<?> iface, FunctionNode fn) throws Throwable {
        synchronized (fn) {
            if (fn.bridges != null && fn.bridges.containsKey(iface)) return fn.bridges.get(iface);
        }
        Constructor<?> k = null;
        try {
            k = generate(iface, fn);
        } finally {
            synchronized (fn) {
                if (fn.bridges == null) fn.bridges = new HashMap<>();
                fn.bridges.put(iface, k);   // null too: do not try a second time
            }
        }
        return k;
    }

    private static Method singleAbstract(Class<?> iface) {
        if (!iface.isInterface() || !Modifier.isPublic(iface.getModifiers())) return null;
        Method found = null;
        for (Method m : iface.getMethods()) {
            if (!Modifier.isAbstract(m.getModifiers())) continue;
            if (isObjectMethod(m)) continue;
            if (found != null && !sameSignature(found, m)) return null;
            if (found == null) found = m;
        }
        return found;
    }

    private static boolean isObjectMethod(Method m) {
        try {
            Object.class.getMethod(m.getName(), m.getParameterTypes());
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static boolean sameSignature(Method a, Method b) {
        return a.getName().equals(b.getName()) && java.util.Arrays.equals(a.getParameterTypes(), b.getParameterTypes())
                && a.getReturnType() == b.getReturnType();
    }

    /** Supported Java types at the boundary: reference (as Object), int, long, double, boolean, void (for the result). */
    private static boolean supported(Class<?> t, boolean ret) {
        if (t == void.class) return ret;
        if (!t.isPrimitive()) return true;
        return t == int.class || t == long.class || t == double.class || t == boolean.class;
    }

    private static String desc(Class<?> t) {
        if (t == void.class) return "V";
        if (t == int.class) return "I";
        if (t == long.class) return "J";
        if (t == double.class) return "D";
        if (t == boolean.class) return "Z";
        if (t.isArray()) return t.getName().replace('.', '/');
        return "L" + t.getName().replace('.', '/') + ";";
    }

    private static String local(Class<?> t) {
        if (t.isPrimitive()) return desc(t);
        if (t.isArray()) return t.getName().replace('.', '/');
        return t.getName().replace('.', '/');
    }

    private static Constructor<?> generate(Class<?> iface, FunctionNode fn) throws Throwable {
        Method m = singleAbstract(iface);
        if (m == null) return null;
        if (m.getParameterCount() != fn.nparams) return null;   // Lua semantics (extra/missing args) - through Proxy
        Class<?>[] jp = m.getParameterTypes();
        Class<?> jr = m.getReturnType();
        for (Class<?> p : jp) if (!supported(p, false)) return null;
        if (!supported(jr, true)) return null;
        // the result is neither Object nor a primitive (String, Integer, List...): Interop.convert is needed - keep the Proxy
        if (!jr.isPrimitive() && jr != void.class && jr != Object.class) return null;
        if (fn.jitClass == null && !fn.jitFailed) Jit.compile(fn);
        if (fn.jitClass == null) return null;
        ScriptLoader loader = Jit.loaderOf(fn.loader);
        if (Class.forName(iface.getName(), false, loader) != iface) return null;   // the interface is not visible from the loader

        String fnCls = fn.jitClass.getName().replace('.', '/');
        String ifaceName = iface.getName().replace('.', '/');
        String name;
        synchronized (BridgeGen.class) { name = Jit.PKG + "Br" + (seq++) + "_" + sanitize(fn.name); }

        ClassBuilder cb = new ClassBuilder(name, OBJ);
        cb.implement(ifaceName);
        boolean stat = fn.jitStaticBody;
        if (!stat) cb.addField(0x0012, "f", "L" + fnCls + ";");   // private final

        // <init>(CompiledFunction)
        Code k = new Code(cb.cp, List.of(name, CF));
        k.aload(0);
        k.invokespecial(OBJ, "<init>", "()V");
        if (!stat) {
            k.aload(0);
            k.aload(1);
            k.checkcast(fnCls);
            k.putfield(name, "f", "L" + fnCls + ";");
        }
        k.vreturn();
        cb.addMethod(0x0001, "<init>", "(L" + CF + ";)V", k.finish());

        // interface method
        List<String> locals = new ArrayList<>();
        locals.add(name);
        StringBuilder md = new StringBuilder("(");
        for (Class<?> p : jp) { locals.add(local(p)); md.append(desc(p)); }
        md.append(')').append(desc(jr));
        Code c = new Code(cb.cp, locals);
        if (!stat) {
            c.aload(0);
            c.getfield(name, "f", "L" + fnCls + ";");
        }
        int slot = 1;
        for (int i = 0; i < jp.length; i++) {
            Class<?> p = jp[i];
            VarType want = fn.paramTypes[i];
            loadArg(c, p, slot, want);
            slot += (p == long.class || p == double.class) ? 2 : 1;
        }
        String bd = Compiler.bodyDesc(fn);
        if (stat) c.invokestatic(fnCls, "body", bd);
        else c.invokevirtual(fnCls, "body", bd);
        returnAs(c, fn.returnType, jr);
        cb.addMethod(0x0001, m.getName(), md.toString(), c.finish());

        byte[] bytes = cb.toBytes();
        Jit.dump(name, bytes);
        Class<?> cls = loader.define(name, bytes);
        if (Jit.DEBUG) System.err.println("[bridge] " + iface.getSimpleName() + "." + m.getName() + " -> " + fn.name + " (" + cls.getName() + ")");
        return cls.getConstructor(CompiledFunction.class);
    }

    /** Java argument (type p in local slot) -> function parameter of type want. */
    private static void loadArg(Code c, Class<?> p, int slot, VarType want) {
        if (p == int.class) {
            c.iload(slot);
            switch (want) {
                case INT -> {}
                case LONG -> c.i2l();
                case DOUBLE -> c.i2d();
                default -> { c.invokestatic(OPS, "box", "(I)" + OBJ_D); toParam(c, want); }
            }
        } else if (p == long.class) {
            c.lload(slot);
            switch (want) {
                case LONG -> {}
                case DOUBLE -> c.l2d();
                default -> { c.invokestatic(OPS, "box", "(J)" + OBJ_D); toParam(c, want); }
            }
        } else if (p == double.class) {
            c.dload(slot);
            if (want != VarType.DOUBLE) { c.invokestatic(OPS, "box", "(D)" + OBJ_D); toParam(c, want); }
        } else if (p == boolean.class) {
            c.iload(slot);
            if (want != VarType.BOOLEAN) { c.invokestatic(OPS, "box", "(Z)" + OBJ_D); toParam(c, want); }
        } else {
            c.aload(slot);
            toParam(c, want);
        }
    }

    /** Object on the stack -> parameter of type want. */
    private static void toParam(Code c, VarType want) {
        switch (want) {
            case INT -> c.invokestatic(OPS, "argInt", "(" + OBJ_D + ")I");
            case LONG -> c.invokestatic(OPS, "argLong", "(" + OBJ_D + ")J");
            case DOUBLE -> c.invokestatic(OPS, "argDouble", "(" + OBJ_D + ")D");
            case BOOLEAN -> c.invokestatic(OPS, "argBool", "(" + OBJ_D + ")Z");
            case STRING -> c.invokestatic(OPS, "toStringSlot", "(" + OBJ_D + ")" + OBJ_D);
            default -> {}
        }
    }

    /** Body result (type from on the stack) -> return of the interface method of type jr. */
    private static void returnAs(Code c, VarType from, Class<?> jr) {
        if (jr == void.class) { c.pop1(); c.vreturn(); return; }
        boolean fromRef = !from.isPrimitive();
        if (jr == Object.class) {
            if (!fromRef) box(c, from);
            c.areturn();
            return;
        }
        // primitive result: from the same primitive - as is, otherwise through a box and argX
        if (jr == int.class) {
            if (from == VarType.INT) { c.ireturn(); return; }
            if (!fromRef) box(c, from);
            c.invokestatic(OPS, "argInt", "(" + OBJ_D + ")I"); c.ireturn();
        } else if (jr == long.class) {
            if (from == VarType.LONG) { c.lreturn(); return; }
            if (from == VarType.INT) { c.i2l(); c.lreturn(); return; }
            if (!fromRef) box(c, from);
            c.invokestatic(OPS, "argLong", "(" + OBJ_D + ")J"); c.lreturn();
        } else if (jr == double.class) {
            if (from == VarType.DOUBLE) { c.dreturn(); return; }
            if (from == VarType.INT) { c.i2d(); c.dreturn(); return; }
            if (from == VarType.LONG) { c.l2d(); c.dreturn(); return; }
            if (!fromRef) box(c, from);
            c.invokestatic(OPS, "argDouble", "(" + OBJ_D + ")D"); c.dreturn();
        } else {   // boolean
            if (from == VarType.BOOLEAN) { c.ireturn(); return; }
            if (!fromRef) box(c, from);
            c.invokestatic(OPS, "truthy", "(" + OBJ_D + ")Z"); c.ireturn();
        }
    }

    private static void box(Code c, VarType t) {
        switch (t) {
            case INT -> c.invokestatic(OPS, "box", "(I)" + OBJ_D);
            case LONG -> c.invokestatic(OPS, "box", "(J)" + OBJ_D);
            case DOUBLE -> c.invokestatic(OPS, "box", "(D)" + OBJ_D);
            case BOOLEAN -> c.invokestatic(OPS, "box", "(Z)" + OBJ_D);
            default -> {}
        }
    }

    private static String sanitize(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) sb.append(Character.isJavaIdentifierPart(ch) ? ch : '_');
        return sb.toString();
    }
}
