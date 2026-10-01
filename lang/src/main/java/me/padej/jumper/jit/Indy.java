package me.padej.jumper.jit;

import me.padej.jumper.ast.Exprs;
import me.padej.jumper.ast.FunctionNode;
import me.padej.jumper.runtime.*;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Bootstrap for `obj.name(args)` in Tier 1: invokedynamic (Object recv, Object... args)Object.
 *
 * A call site starts on the slow path; for a Java receiver (or a static call through
 * JavaClass) a direct MethodHandle is built with argument conversions and a guard on the
 * receiver/argument classes. A guard miss adds the next layer of the chain (up to MAX_DEPTH = 16), then megamorphic
 * mode: the slow path forever (the same one the interpreter uses, with its caches).
 */
public final class Indy {
    /**
     * How many receiver shapes a call site keeps as a direct guard chain before falling back
     * to the generic path. It was 4 - and the call_mega12 benchmark (12 classes with a same-named method) always
     * fell through to `Exprs.MethodCall.invokeDynamic`: method lookup by name, a copy of the arguments
     * into a fresh Object[] and the generic JFunction.call, about 59 ns per call.
     *
     * <p>Sixteen, because the chain costs one reference comparison per layer, i.e. on average
     * half the depth, and that is an order of magnitude cheaper than the generic path. Measured on call_mega12:
     * 4 -> 102 ms, 8 -> 74.6, 16 -> 35.7, 24 -> 35.7 (beyond that it is bounded by the benchmark itself, which has 12 shapes).
     *
     * <p>A `layout -> MethodHandle` dictionary instead of the chain was tried and turned out **slower**
     * (120 ms): `invokeExact` on a non-constant MethodHandle is not inlined, and the overhead
     * eats the gain of the O(1) lookup.
     */
    private static final int MAX_DEPTH = 16;
    private static final boolean FIELD_CALL = Opts.on("fieldcall");
    private static final MethodHandle FALLBACK, GUARD, NORMALIZE, CONVERT, ARG_INT, ARG_LONG, ARG_DOUBLE, ARG_BOOL,
            TO_FLOAT, TO_CHAR, TO_SHORT, TO_BYTE, TO_STRING_SLOT, CLASS_GUARD, CALL_METHOD, COMPILED_METHOD,
            NEW_FALLBACK, NEW_GUARD, NEW_CLASS_GUARD, INSTANTIATE,
            GET_REF_PLAIN, CLASS_SHAPE_OK, SAME_FN_NODE, COMPILED_OF, FALSE_OBJ;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            FALLBACK = l.findVirtual(Indy.class, "fallback", MethodType.methodType(Object.class, Object.class, Object[].class));
            GUARD = l.findStatic(Indy.class, "guard", MethodType.methodType(boolean.class, Object.class, Class[].class, Object.class, Object[].class));
            NORMALIZE = l.findStatic(Interop.class, "normalize", MethodType.methodType(Object.class, Object.class));
            CONVERT = l.findStatic(Interop.class, "convert", MethodType.methodType(Object.class, Class.class, Object.class));
            ARG_INT = l.findStatic(Ops.class, "argInt", MethodType.methodType(int.class, Object.class));
            ARG_LONG = l.findStatic(Ops.class, "argLong", MethodType.methodType(long.class, Object.class));
            ARG_DOUBLE = l.findStatic(Ops.class, "argDouble", MethodType.methodType(double.class, Object.class));
            ARG_BOOL = l.findStatic(Ops.class, "argBool", MethodType.methodType(boolean.class, Object.class));
            TO_FLOAT = l.findStatic(Indy.class, "toFloat", MethodType.methodType(float.class, Object.class));
            TO_CHAR = l.findStatic(Indy.class, "toChar", MethodType.methodType(char.class, Object.class));
            TO_SHORT = l.findStatic(Indy.class, "toShort", MethodType.methodType(short.class, Object.class));
            TO_BYTE = l.findStatic(Indy.class, "toByte", MethodType.methodType(byte.class, Object.class));
            TO_STRING_SLOT = l.findStatic(Ops.class, "toStringSlot", MethodType.methodType(Object.class, Object.class));
            CLASS_GUARD = l.findStatic(Indy.class, "classGuard", MethodType.methodType(boolean.class, Object.class, Object.class));
            COMPILED_METHOD = l.findStatic(Indy.class, "compiledMethod", MethodType.methodType(CompiledFunction.class, int.class, Object.class));
            GET_REF_PLAIN = l.findStatic(Indy.class, "getRefPlain", MethodType.methodType(Object.class, int.class, Object.class));
            CLASS_SHAPE_OK = l.findStatic(RegionSite.class, "is", MethodType.methodType(boolean.class, Class.class, Shape.class, Object.class));
            SAME_FN_NODE = l.findStatic(Indy.class, "sameFnNode", MethodType.methodType(boolean.class, Object.class, Object.class));
            COMPILED_OF = l.findStatic(Indy.class, "compiledOf", MethodType.methodType(CompiledFunction.class, Object.class));
            FALSE_OBJ = MethodHandles.dropArguments(MethodHandles.constant(boolean.class, false), 0, Object.class);
            CALL_METHOD = l.findStatic(Indy.class, "callMethod", MethodType.methodType(Object.class, JFunction.class, Object.class, Object[].class));
            NEW_FALLBACK = l.findVirtual(NewSite.class, "fallback", MethodType.methodType(Object.class, Object.class, Object[].class));
            NEW_GUARD = l.findStatic(NewSite.class, "guard", MethodType.methodType(boolean.class, Class.class, Class[].class, Object.class, Object[].class));
            NEW_CLASS_GUARD = l.findStatic(NewSite.class, "classGuard", MethodType.methodType(boolean.class, Object.class, Object.class));
            INSTANTIATE = l.findVirtual(JClass.class, "instantiate", MethodType.methodType(JTable.class, Object[].class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final MutableCallSite site;
    private final String name;
    private final MethodType type;
    private final FieldCache fieldCache;
    private final MethodHandle slowPath;
    private int depth;

    private Indy(String name, MethodType type) {
        this(name, type, -1, 0);
    }

    /**
     * Result folding (Compiler.foldCall): `obj.m(x) % 1000003` where the value is needed as a primitive.
     * The site returns that primitive and applies the operation with the constant itself, per layer:
     * a target returning int gets the int operation and a widening - exactly what Ops.op(Object, int)
     * followed by Ops.unbox* do with an Integer, only without the Integer; any other target gets
     * exactly those two calls. Nothing is assumed, so nothing has to be undone.
     */
    private final int foldOp, foldK;
    private final MethodHandle intFold, genericFold;

    private Indy(String name, MethodType type, int foldOp, int foldK) {
        this.name = name;
        this.type = type;
        this.foldOp = foldOp;
        this.foldK = foldK;
        if (foldOp >= 0) {
            Class<?> want = type.returnType();
            MethodHandle i = foldOp == FOLD_NONE ? MethodHandles.identity(int.class)
                    : MethodHandles.insertArguments(INT_OPS[foldOp], 1, foldK);
            this.intFold = i.asType(MethodType.methodType(want, int.class));
            MethodHandle g = foldOp == FOLD_NONE ? MethodHandles.identity(Object.class)
                    : MethodHandles.insertArguments(OBJ_OPS[foldOp], 1, foldK);
            MethodHandle unbox = want == int.class ? ARG_INT_RET : want == long.class ? ARG_LONG_RET : ARG_DOUBLE_RET;
            this.genericFold = MethodHandles.filterReturnValue(g, unbox);
        } else {
            this.intFold = null;
            this.genericFold = null;
        }
        this.fieldCache = new FieldCache(name);
        this.site = new MutableCallSite(type);
        this.slowPath = fit(FALLBACK.bindTo(this).asCollector(Object[].class, type.parameterCount() - 1));
        site.setTarget(slowPath);
    }

    public static CallSite bootstrap(MethodHandles.Lookup lookup, String name, MethodType type) {
        return new Indy(name, type).site;
    }

    /** A method call whose result is folded (see the fold constructor): op is FOLD_*, k the int constant. */
    public static CallSite bootstrapFold(MethodHandles.Lookup lookup, String name, MethodType type, int op, int k) {
        return new Indy(name, type, op, k).site;
    }

    static final int FOLD_NONE = 0, FOLD_ADD = 1, FOLD_SUB = 2, FOLD_MUL = 3, FOLD_DIV = 4, FOLD_MOD = 5;
    private static final MethodHandle[] INT_OPS = new MethodHandle[6], OBJ_OPS = new MethodHandle[6];
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            String[] n = {null, "add", "sub", "mul", "div", "mod"};
            for (int i = 1; i < 6; i++) {
                INT_OPS[i] = l.findStatic(Indy.class, n[i] + "I", MethodType.methodType(int.class, int.class, int.class));
                OBJ_OPS[i] = l.findStatic(Ops.class, n[i], MethodType.methodType(Object.class, Object.class, int.class));
            }
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // Ops.op(Integer, int) computes in int (wrapping like Java); the divisor is a non-zero literal.
    static int addI(int x, int k) { return x + k; }
    static int subI(int x, int k) { return x - k; }
    static int mulI(int x, int k) { return x * k; }
    static int divI(int x, int k) { return x / k; }
    static int modI(int x, int k) { return x % k; }

    /** A layer's target in the site's type: as is, or with the result folded (int-returning targets without a box). */
    private MethodHandle fit(MethodHandle h) {
        if (foldOp < 0) return h.asType(type);
        Class<?> r = h.type().returnType();
        h = h.asType(type.changeReturnType(r));
        if (r == int.class) h = MethodHandles.filterReturnValue(h, intFold);
        else h = MethodHandles.filterReturnValue(h.asType(h.type().changeReturnType(Object.class)), genericFold);
        return h.asType(type);
    }

    /**
     * Call of a function by a top-level REPL/ScriptEngine name (`fib(n - 1)` inside `fib`).
     * The bootstrap arguments are the indices of the cell and of the expected function in the caller's K.
     */
    public static CallSite bootstrapCell(MethodHandles.Lookup lookup, String name, MethodType type, int cellIdx, int fnIdx) throws Throwable {
        Object[] k = (Object[]) lookup.findStaticGetter(lookup.lookupClass(), "K", Object[].class).invoke();
        return new CellSite((Cell) k[cellIdx], (FunctionNode) k[fnIdx], type).site;
    }

    /**
     * Call site through a cell. As long as the cell holds a function with a static body of the same
     * type, the target is the body itself under the cell's {@link java.lang.invoke.SwitchPoint}: C2 sees a constant
     * target and inlines it, there is no guard on the hot path. A write to the cell ({@link Cell#set}) invalidates
     * the switch point, the site goes to the generic path `Ops.call(cell.v, args)` and on subsequent calls tries
     * to rebind - now to the new value. The number of attempts is bounded: a site whose name
     * is replaced constantly stays on the generic path.
     */
    static final class CellSite {
        private static final MethodHandle CELL_FALLBACK, OPS_CALL;
        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                CELL_FALLBACK = l.findVirtual(CellSite.class, "fallback", MethodType.methodType(Object.class, Object[].class));
                OPS_CALL = l.findStatic(Ops.class, "call", MethodType.methodType(Object.class, Object.class, Object[].class));
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        final MutableCallSite site;
        private final Cell cell;
        private final MethodType type;
        /** Generic path in the site type: box the arguments, coerce the result as convert(DYN, returnType). */
        private final MethodHandle slow;
        private int tries;

        CellSite(Cell cell, FunctionNode expected, MethodType type) {
            this.cell = cell;
            this.type = type;
            MethodHandle f = CELL_FALLBACK.bindTo(this).asCollector(Object[].class, type.parameterCount());
            f = MethodHandles.filterReturnValue(f, unboxFor(type.returnType()));
            this.slow = f.asType(type);
            this.site = new MutableCallSite(slow);
        }

        private static MethodHandle unboxFor(Class<?> rt) {
            if (rt == int.class) return ARG_INT_RET;
            if (rt == long.class) return ARG_LONG_RET;
            if (rt == double.class) return ARG_DOUBLE_RET;
            if (rt == boolean.class) return TRUTHY;
            return MethodHandles.identity(Object.class);
        }

        Object fallback(Object[] args) throws Throwable {
            if (tries < 8) { tries++; tryLink(); }
            return Ops.call(cell.v, args);
        }

        private void tryLink() {
            if (!(cell.v instanceof FunctionNode.ScriptFunction sf)) return;
            FunctionNode fn = sf.node;
            if (fn.jitClass == null && !fn.jitFailed) Jit.compile(fn);
            if (fn.jitClass == null || !fn.jitStaticBody) return;
            try {
                MethodHandle direct;
                // the caller chose the entry by its argument types: body, or the int entry bodyI
                MethodType spec = fn.specDesc == null ? null : MethodType.fromMethodDescriptorString(fn.specDesc, Indy.class.getClassLoader());
                if (spec != null && !type.equals(MethodType.fromMethodDescriptorString(Compiler.bodyDesc(fn), Indy.class.getClassLoader()))
                        && type.parameterList().equals(spec.parameterList()))
                    direct = Jit.LOOKUP.findStatic(fn.jitClass, "bodyI", spec).asType(type);   // result boxed back to body's type
                else direct = Jit.LOOKUP.findStatic(fn.jitClass, "body", type);
                site.setTarget(cell.switchPoint().guardWithTest(direct, slow));
            } catch (ReflectiveOperationException e) {
                if (Jit.DEBUG) System.err.println("[indy] no direct handle for cell " + cell.name + ": " + e);
                tries = 8;
            }
        }
    }
    /**
     * The value of a top-level name as a constant under the cell's SwitchPoint - for `new P(...)` in
     * ScriptEngine mode, where P is a cell. Reading the cell, testing the value is a JClass and
     * comparing its declaration node cost as much as the allocation itself: a Java model of the
     * compiled `new` (object_alloc_churn) ran 85 ms with the read, 50 with a constant class, Java 48.
     * With the class a constant, all of it folds, and so does `cls.shape` in the instance constructor.
     * A write to the cell invalidates the point; the site then reads the cell and rebinds (a few
     * times at most - a name that keeps changing just stays a plain read).
     */
    public static CallSite bootstrapCellValue(MethodHandles.Lookup lookup, String name, MethodType type, int cellIdx) throws Throwable {
        Object[] k = (Object[]) lookup.findStaticGetter(lookup.lookupClass(), "K", Object[].class).invoke();
        return new ValueSite((Cell) k[cellIdx]).site;
    }

    static final class ValueSite {
        private static final MethodHandle READ;
        static {
            try {
                READ = MethodHandles.lookup().findVirtual(ValueSite.class, "read", MethodType.methodType(Object.class));
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
        final MutableCallSite site = new MutableCallSite(MethodType.methodType(Object.class));
        private final Cell cell;
        private final MethodHandle slow;
        private int tries;

        ValueSite(Cell cell) {
            this.cell = cell;
            this.slow = READ.bindTo(this);
            site.setTarget(slow);
        }

        Object read() {
            if (tries < 8) {
                synchronized (this) {
                    if (tries < 8) {
                        tries++;
                        // the point first, the value second: a write in between invalidates this very point
                        java.lang.invoke.SwitchPoint sp = cell.switchPoint();
                        Object v = cell.v;
                        if (!sp.hasBeenInvalidated()) site.setTarget(sp.guardWithTest(MethodHandles.constant(Object.class, v), slow));
                        return v;
                    }
                }
            }
            return cell.v;
        }
    }

    private static final MethodHandle ARG_INT_RET, ARG_LONG_RET, ARG_DOUBLE_RET, TRUTHY;
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            ARG_INT_RET = l.findStatic(Ops.class, "unboxInt", MethodType.methodType(int.class, Object.class));
            ARG_LONG_RET = l.findStatic(Ops.class, "unboxLong", MethodType.methodType(long.class, Object.class));
            ARG_DOUBLE_RET = l.findStatic(Ops.class, "unboxDouble", MethodType.methodType(double.class, Object.class));
            TRUTHY = l.findStatic(Ops.class, "truthy", MethodType.methodType(boolean.class, Object.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // ---------- slow path ----------

    Object fallback(Object recv, Object[] args) {
        if (recv instanceof JTable t && t.cls() != null && depth < MAX_DEPTH) {
            JFunction m = t.cls().findMethod(name);
            if (m != null) {
                try {
                    installClassMethod(t.cls(), m, args.length);
                } catch (Throwable e) {
                    if (Jit.DEBUG) System.err.println("[indy] no direct handle for method " + name + ": " + e);
                    depth = MAX_DEPTH;
                }
            }
        }
        if (FIELD_CALL && recv instanceof JTable t && t.cls() == null && !t.isDict() && depth < MAX_DEPTH) {
            try {
                installTableFunction(t, args.length);
            } catch (Throwable e) {
                if (Jit.DEBUG) System.err.println("[indy] no direct handle for field " + name + ": " + e);
                depth = MAX_DEPTH;
            }
        }
        if (recv instanceof JTable || recv instanceof JArray || recv instanceof JClass || recv == null)
            return Exprs.MethodCall.invokeDynamic(recv, name, fieldCache, args);
        boolean isStatic = recv instanceof JavaClass;
        Class<?> cls = isStatic ? ((JavaClass) recv).cls() : recv.getClass();
        Method[] cands = Interop.candidates(cls, name, args.length, isStatic);
        Method m = Interop.selectMethod(cands, args, isStatic);
        if (m == null) return Exprs.MethodCall.invokeDynamic(recv, name, fieldCache, args); // throws a readable error
        if (depth < MAX_DEPTH) {
            try {
                install(m, recv, args, isStatic);
            } catch (IllegalAccessException | RuntimeException e) {
                if (Jit.DEBUG) System.err.println("[indy] no direct handle for " + m + ": " + e);
                depth = MAX_DEPTH; // do not try again
            }
        }
        return Interop.invokeMethod(m, isStatic ? null : recv, args);
    }

    private void install(Method m, Object recv, Object[] args, boolean isStatic) throws IllegalAccessException {
        int n = args.length;
        MethodHandle direct = MethodHandles.publicLookup().unreflect(m);
        Class<?>[] params = m.getParameterTypes();
        if (m.isVarArgs()) {
            // fix the arity: collect the tail into an array (or pass an array through directly)
            int fixed = params.length - 1;
            if (n == params.length && params[fixed].isInstance(args[n - 1])) {
                // the argument is already an array of the required type - leave it as is
            } else {
                direct = direct.asCollector(params[fixed], n - fixed);
                params = expandVarargs(params, n);
            }
        }
        int offset = isStatic ? 0 : 1;
        // The site may pass primitive arguments as primitives (the compiler knows their static type):
        // such a position needs no unboxing filter and no class check in the guard - it cannot vary.
        MethodHandle[] filters = new MethodHandle[params.length];
        for (int i = 0; i < params.length; i++) {
            Class<?> sp = type.parameterType(i + 1);
            if (sp.isPrimitive()) {
                if (sp == params[i] || widens(sp, params[i]) || !params[i].isPrimitive()) continue;   // asType does it
                filters[i] = converter(params[i]).asType(MethodType.methodType(params[i], sp));
            } else {
                filters[i] = converter(params[i]);
            }
        }
        direct = MethodHandles.filterArguments(direct, offset, filters);
        // return -> Object + normalize (char -> String etc.)
        Class<?> rt = m.getReturnType();
        if (rt == void.class) {
            direct = MethodHandles.filterReturnValue(direct, MethodHandles.constant(Object.class, null));
        } else {
            direct = direct.asType(direct.type().changeReturnType(Object.class));
            if (rt == char.class || rt == Character.class || rt == float.class || rt == Float.class
                    || rt == short.class || rt == byte.class || rt == Short.class || rt == Byte.class
                    // under a policy a Class that comes back (getClass(), a List<Class> element...) becomes a
                    // JavaClass (Interop.wrapClass) - the site is linked under the policy it runs under
                    || !rt.isPrimitive() && rt.isAssignableFrom(Class.class) && me.padej.jumper.runtime.Access.current() != me.padej.jumper.runtime.Access.ALL)
                direct = MethodHandles.filterReturnValue(direct, NORMALIZE);
        }
        if (isStatic) direct = MethodHandles.dropArguments(direct, 0, Object.class);
        direct = fit(direct);

        java.util.List<Class<?>> objClasses = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (type.parameterType(i + 1).isPrimitive()) continue;
            objClasses.add(args[i] == null ? null : args[i].getClass());
        }
        Object key = isStatic ? recv : recv.getClass();
        MethodHandle test = MethodHandles.insertArguments(GUARD, 0, key, objClasses.toArray(new Class<?>[0]))
                .asCollector(Object[].class, objClasses.size());
        for (int i = 0; i < n; i++) {
            Class<?> sp = type.parameterType(i + 1);
            if (sp.isPrimitive()) test = MethodHandles.dropArguments(test, 1 + i, sp);
        }
        test = test.asType(type.changeReturnType(boolean.class));
        MethodHandle guarded = MethodHandles.guardWithTest(test, direct, site.getTarget());
        depth++;
        site.setTarget(guarded);
        if (Jit.DEBUG) System.err.println("[indy] " + name + " -> " + m + " (depth " + depth + ")");
    }

    /**
     * Method of a script class: guard on the instance class, target - the typed body() of the compiled
     * method (no Object[]), or the generic JFunction.call if the method does not compile.
     */
    private void installClassMethod(JClass cls, JFunction m, int n) throws Throwable {
        MethodHandle target = null;
        int idx = cls.methodIndex(name);
        if (m instanceof FunctionNode.ScriptFunction sf && idx >= 0) {
            FunctionNode fn = sf.node;
            if (fn.jitClass == null && !fn.jitFailed && Jit.ENABLED) Jit.compile(fn);
            if (fn.jitClass != null && fn.nparams == n + 1) {
                MethodType bt = MethodType.fromMethodDescriptorString(Compiler.bodyDesc(fn), Indy.class.getClassLoader());
                MethodHandle[] argFilters = new MethodHandle[fn.nparams];
                for (int i = 0; i < fn.nparams; i++) {
                    argFilters[i] = switch (fn.paramTypes[i]) {
                        case INT -> ARG_INT;
                        case LONG -> ARG_LONG;
                        case DOUBLE -> ARG_DOUBLE;
                        case BOOLEAN -> ARG_BOOL;
                        case STRING -> TO_STRING_SLOT;
                        default -> null;
                    };
                }
                if (fn.jitStaticBody) {
                    // The body is static: no CompiledFunction instance is needed at all, the receiver is
                    // simply the first parameter. Previously findVirtual was used unconditionally here, and on
                    // a static body it threw IllegalAccessException: the call site silently
                    // went to depth = MAX_DEPTH, i.e. to the slow path forever.
                    MethodHandle body = MethodHandles.lookup().findStatic(fn.jitClass, "body", bt); // (this, P...)R
                    body = MethodHandles.filterArguments(body, 0, argFilters);
                    target = fit(body);
                } else {
                    MethodHandle body = MethodHandles.lookup().findVirtual(fn.jitClass, "body", bt); // (Fn, this, P...)R
                    // body is not bound to a particular instance: a class with the same layout may be created anew
                    // (e.g. when the script is executed again) - the instance is taken from cls.methodArr[idx] via the receiver
                    MethodHandle getCf = MethodHandles.insertArguments(COMPILED_METHOD, 0, idx).asType(MethodType.methodType(fn.jitClass, Object.class));
                    MethodHandle[] filters = new MethodHandle[fn.nparams + 1];
                    filters[0] = getCf;
                    System.arraycopy(argFilters, 0, filters, 1, fn.nparams);
                    body = MethodHandles.filterArguments(body, 0, filters); // (Object recvForCf, Object this, Object...)R
                    int[] reorder = new int[fn.nparams + 1];
                    reorder[0] = 0;
                    for (int i = 0; i < fn.nparams; i++) reorder[i + 1] = i;
                    MethodType t2 = type.changeReturnType(body.type().returnType());
                    body = MethodHandles.permuteArguments(body, t2, reorder);
                    target = fit(body);
                }
            }
        }
        if (target == null) target = fit(MethodHandles.insertArguments(CALL_METHOD, 0, m).asCollector(Object[].class, n));
        MethodHandle test = MethodHandles.dropArguments(MethodHandles.insertArguments(CLASS_GUARD, 0, cls.layout), 1, type.parameterList().subList(1, type.parameterCount()))
                .asType(type.changeReturnType(boolean.class));
        depth++;
        site.setTarget(MethodHandles.guardWithTest(test, target, site.getTarget()));
        if (Jit.DEBUG) System.err.println("[indy] " + name + " -> " + cls.name + "." + name + (target.type().equals(type) ? "" : "?") + " (depth " + depth + ")");
    }

    /**
     * `t.f(args)` on a plain table (`{ next: (x) -> ... }`, without a class): previously such a site
     * always went the generic way - `invokeDynamic` -> field inline cache -> `JFunction.call` with a copy of
     * the arguments into `Object[]`; on call_mono/dyn that was a 10x gap, and on call_mega12/dyn the result
     * depended on how C2 happened to inline the megamorphic `CompiledFunction.invoke` this time
     * (158 versus 362 ms with identical bytecode). Now there is a guard "same shape and the slot holds the
     * same function", and under it a direct call of the body with a static signature, as for a class method.
     * Different functions in the same slot (twelve tables of one shape) are different layers of the chain.
     */
    private void installTableFunction(JTable t, int n) throws Throwable {
        Shape s = t.shape;
        if (s.store != null) return;   // a private shape (grown Lean table): one table's own, not worth a layer
        int idx = s.indexOf(name);
        if (idx < 0 || s.typeAt(idx).isPrimitive()) return;
        Object fv = t.refAt(idx);
        if (!(fv instanceof FunctionNode.ScriptFunction sf)) return;
        FunctionNode fn = sf.node;
        if (fn.jitClass == null && !fn.jitFailed && Jit.ENABLED) Jit.compile(fn);
        if (fn.jitClass == null || fn.nparams != n) return;
        Class<?> cls = t.getClass();
        // The slot read below (both in the guard and, for a non-static body, in the target) has to
        // go through a getter bound to this concrete class, never through JTable.refAt: refAt is one
        // virtual method shared by every table-function call site in the whole program, so as soon as
        // two of them are ever live at once (Plain and a layout class, or two different layout classes -
        // exactly what happens once one literal's shapes get their own Lay<n> classes, see LayoutGen),
        // the call inside it goes megamorphic and stops inlining everywhere, not just here. A getter
        // resolved for this specific class - Plain's array read or a layout class's generated getRef<i> -
        // is a plain field/array load no matter what else is going on elsewhere.
        MethodHandle refGetter = cls == JTable.Plain.class
                ? MethodHandles.insertArguments(GET_REF_PLAIN, 0, idx)
                : LayoutGen.refGetter(cls, idx);
        if (refGetter == null) return; // layout disagrees about which slot is a reference - stay on the generic path
        MethodType bt = MethodType.fromMethodDescriptorString(Compiler.bodyDesc(fn), Indy.class.getClassLoader());
        MethodHandle[] argFilters = new MethodHandle[fn.nparams];
        for (int i = 0; i < fn.nparams; i++) {
            argFilters[i] = switch (fn.paramTypes[i]) {
                case INT -> ARG_INT;
                case LONG -> ARG_LONG;
                case DOUBLE -> ARG_DOUBLE;
                case BOOLEAN -> ARG_BOOL;
                case STRING -> TO_STRING_SLOT;
                default -> null;
            };
        }
        // The guard is on the function node, not on the value: `dyn c = { next: (x) -> ... }` inside benchRun
        // creates a new closure on every call, and a guard on value identity would miss
        // every time, growing the chain up to MAX_DEPTH. The closure is taken from the receiver's slot.
        MethodHandle target;
        MethodHandle spec = fn.jitStaticBody ? specEntry(fn, 1) : null;
        if (spec != null) {
            target = fit(MethodHandles.dropArguments(spec, 0, Object.class));            // (recv, int...)R
        } else if (fn.jitStaticBody) {
            MethodHandle body = MethodHandles.lookup().findStatic(fn.jitClass, "body", bt);      // (P...)R
            body = MethodHandles.filterArguments(body, 0, argFilters);
            target = fit(MethodHandles.dropArguments(body, 0, Object.class));           // (recv, P...)R
        } else {
            MethodHandle body = MethodHandles.lookup().findVirtual(fn.jitClass, "body", bt);     // (Fn, P...)R
            MethodHandle getCf = MethodHandles.filterReturnValue(refGetter, COMPILED_OF).asType(MethodType.methodType(fn.jitClass, Object.class));
            MethodHandle[] filters = new MethodHandle[fn.nparams + 1];
            filters[0] = getCf;
            System.arraycopy(argFilters, 0, filters, 1, fn.nparams);
            target = fit(MethodHandles.filterArguments(body, 0, filters));                // (recv, P...)R
        }
        // classShapeOk (a plain getClass()==c && shape==s test, no virtual call) guards refOk (this
        // class's own getter, then a reference-identity check against fn's node) - two direct,
        // class-bound reads chained under one guardWithTest, not one shared reflective method.
        MethodHandle classShapeOk = MethodHandles.insertArguments(CLASS_SHAPE_OK, 0, cls, s);
        MethodHandle refOk = MethodHandles.filterReturnValue(refGetter, MethodHandles.insertArguments(SAME_FN_NODE, 0, fn));
        MethodHandle test0 = MethodHandles.guardWithTest(classShapeOk, refOk, FALSE_OBJ);
        MethodHandle test = MethodHandles.dropArguments(test0, 1, type.parameterList().subList(1, type.parameterCount()))
                .asType(type.changeReturnType(boolean.class));
        depth++;
        site.setTarget(MethodHandles.guardWithTest(test, target, site.getTarget()));
        if (Jit.DEBUG) System.err.println("[indy] " + name + " -> field function " + fn.name + " (depth " + depth + ")");
    }

    /**
     * The function's int entry (Compiler.decideSpec) when this site passes a statically int argument
     * at every int-entry position (site parameters from index `from`): no box for the argument, and
     * for an int-returning entry none for the result until the site's own asType. Null otherwise.
     */
    private MethodHandle specEntry(FunctionNode fn, int from) throws ReflectiveOperationException {
        if (fn.specInt == null || fn.specDesc == null || type.parameterCount() - from != fn.nparams) return null;
        for (int i = 0; i < fn.nparams; i++) if (fn.specInt[i] && type.parameterType(from + i) != int.class) return null;
        MethodType st = MethodType.fromMethodDescriptorString(fn.specDesc, Indy.class.getClassLoader());
        MethodHandle h = MethodHandles.lookup().findStatic(fn.jitClass, "bodyI", st);
        // the other parameters: body's own conversions (argInt & co. for typed ones, none for dyn)
        MethodHandle[] f = new MethodHandle[fn.nparams];
        for (int i = 0; i < fn.nparams; i++) {
            if (fn.specInt[i]) continue;
            f[i] = switch (fn.paramTypes[i]) {
                case INT -> ARG_INT; case LONG -> ARG_LONG; case DOUBLE -> ARG_DOUBLE; case BOOLEAN -> ARG_BOOL; case STRING -> TO_STRING_SLOT;
                default -> null;
            };
        }
        return MethodHandles.filterArguments(h, 0, f);
    }

    static Object getRefPlain(int i, Object o) { return ((JTable.Plain) o).values[i]; }

    static boolean sameFnNode(Object node, Object ref) {
        return ref instanceof FunctionNode.ScriptFunction sf && sf.node == node;
    }

    static CompiledFunction compiledOf(Object ref) {
        return ((FunctionNode.ScriptFunction) ref).compiled();
    }

    static boolean classGuard(Object layout, Object recv) {
        return recv instanceof JTable t && t.shape != null && t.shape.ownerLayout == layout;
    }

    /** Compiled instance of method idx of the receiver's class. */
    public static CompiledFunction compiledMethod(int idx, Object recv) {
        FunctionNode.ScriptFunction sf = (FunctionNode.ScriptFunction) ((JTable) recv).cls().methodArr[idx];
        return sf.compiled();
    }

    /** Compiled class constructor (or null if there is none / it does not compile). */
    public static CompiledFunction compiledCtor(Object cls) {
        return ((JClass) cls).ctor instanceof FunctionNode.ScriptFunction sf ? sf.compiled() : null;
    }

    static Object callMethod(JFunction m, Object recv, Object[] args) {
        return m.call(JClass.withThis(recv, args));
    }

    private static Class<?>[] expandVarargs(Class<?>[] params, int n) {
        Class<?>[] out = new Class<?>[n];
        int fixed = params.length - 1;
        System.arraycopy(params, 0, out, 0, fixed);
        for (int i = fixed; i < n; i++) out[i] = params[fixed].getComponentType();
        return out;
    }

    /** MethodHandle (Object)->p with Interop.convert semantics. */
    /** Primitive widening that MethodHandle.asType performs on its own (JLS 5.1.2). */
    private static boolean widens(Class<?> from, Class<?> to) {
        if (from == int.class) return to == long.class || to == double.class;
        if (from == long.class) return to == double.class;
        return false;
    }

    private static MethodHandle converter(Class<?> p) {
        if (p == int.class) return ARG_INT;
        if (p == long.class) return ARG_LONG;
        if (p == double.class) return ARG_DOUBLE;
        if (p == boolean.class) return ARG_BOOL;
        if (p == float.class) return TO_FLOAT;
        if (p == char.class) return TO_CHAR;
        if (p == short.class) return TO_SHORT;
        if (p == byte.class) return TO_BYTE;
        return Interop.converter(p);
    }

    /** key - the receiver class (instance) or the JavaClass itself (static). */
    static boolean guard(Object key, Class<?>[] argClasses, Object recv, Object[] args) {
        if (key instanceof Class<?> k) {
            if (recv == null || recv.getClass() != k) return false;
        } else if (recv != key) return false;
        for (int i = 0; i < argClasses.length; i++) {
            Object a = args[i];
            if ((a == null ? null : a.getClass()) != argClasses[i]) return false;
        }
        return true;
    }

    // ================= new X(args) =================

    /** Bootstrap for `new cls(args)`: invokedynamic (Object cls, Object... args)Object. */
    public static CallSite bootstrapNew(MethodHandles.Lookup lookup, String name, MethodType type) {
        return new NewSite(type).site;
    }

    /**
     * Java class: guard on the Class and the argument classes, target - a direct constructor MethodHandle with conversions.
     * Script class: guard on the layout, target - JClass.instantiate. Otherwise the generic path Exprs.New.construct.
     */
    static final class NewSite {
        private final MutableCallSite site;
        private final MethodType type;
        private int depth;

        NewSite(MethodType type) {
            this.type = type;
            this.site = new MutableCallSite(type);
            site.setTarget(NEW_FALLBACK.bindTo(this).asCollector(Object[].class, type.parameterCount() - 1).asType(type));
        }

        Object fallback(Object cls, Object[] args) {
            if (depth < MAX_DEPTH) {
                try {
                    if (cls instanceof JClass jc) installScript(jc, args.length);
                    else if (cls instanceof JavaClass jc && !Modifier.isAbstract(jc.cls().getModifiers()) && !jc.cls().isInterface()) {
                        java.lang.reflect.Constructor<?> k = Interop.selectConstructor(jc.cls(), args);
                        if (k != null) installJava(jc.cls(), k, args);
                    }
                } catch (IllegalAccessException | RuntimeException e) {
                    if (Jit.DEBUG) System.err.println("[indy] no direct handle for new " + cls + ": " + e);
                    depth = MAX_DEPTH;
                }
            }
            return Exprs.New.construct(cls, args);
        }

        private void installScript(JClass jc, int n) {
            MethodHandle target = INSTANTIATE.asCollector(Object[].class, n).asType(type);
            MethodHandle test = MethodHandles.dropArguments(MethodHandles.insertArguments(NEW_CLASS_GUARD, 0, jc.layout), 1, type.parameterList().subList(1, type.parameterCount()))
                    .asType(type.changeReturnType(boolean.class));
            depth++;
            site.setTarget(MethodHandles.guardWithTest(test, target, site.getTarget()));
            if (Jit.DEBUG) System.err.println("[indy] new -> " + jc.name + " (depth " + depth + ")");
        }

        private void installJava(Class<?> cls, java.lang.reflect.Constructor<?> k, Object[] args) throws IllegalAccessException {
            int n = args.length;
            MethodHandle direct = MethodHandles.publicLookup().unreflectConstructor(k);
            Class<?>[] params = k.getParameterTypes();
            if (k.isVarArgs()) {
                int fixed = params.length - 1;
                if (!(n == params.length && params[fixed].isInstance(args[n - 1]))) {
                    direct = direct.asCollector(params[fixed], n - fixed);
                    params = expandVarargs(params, n);
                }
            }
            MethodHandle[] filters = new MethodHandle[params.length];
            for (int i = 0; i < params.length; i++) filters[i] = converter(params[i]);
            direct = MethodHandles.filterArguments(direct, 0, filters);
            direct = MethodHandles.dropArguments(direct.asType(direct.type().changeReturnType(Object.class)), 0, Object.class).asType(type);
            Class<?>[] argClasses = new Class<?>[n];
            for (int i = 0; i < n; i++) argClasses[i] = args[i] == null ? null : args[i].getClass();
            MethodHandle test = MethodHandles.insertArguments(NEW_GUARD, 0, cls, argClasses)
                    .asCollector(Object[].class, n)
                    .asType(type.changeReturnType(boolean.class));
            depth++;
            site.setTarget(MethodHandles.guardWithTest(test, direct, site.getTarget()));
            if (Jit.DEBUG) System.err.println("[indy] new -> " + k + " (depth " + depth + ")");
        }

        static boolean classGuard(Object layout, Object cls) {
            return cls instanceof JClass c && c.layout == layout;
        }

        static boolean guard(Class<?> k, Class<?>[] argClasses, Object cls, Object[] args) {
            if (!(cls instanceof JavaClass jc) || jc.cls() != k) return false;
            for (int i = 0; i < argClasses.length; i++) {
                Object a = args[i];
                if ((a == null ? null : a.getClass()) != argClasses[i]) return false;
            }
            return true;
        }
    }

    static float toFloat(Object v) { return ((Number) v).floatValue(); }
    static char toChar(Object v) { return ((String) v).charAt(0); }
    static short toShort(Object v) { return ((Number) v).shortValue(); }
    static byte toByte(Object v) { return ((Number) v).byteValue(); }
}
