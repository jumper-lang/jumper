package me.padej.jumper.jit;

import me.padej.jumper.ast.*;
import me.padej.jumper.ast.Exprs.*;
import me.padej.jumper.ast.Fields.*;
import me.padej.jumper.ast.Prims.*;
import me.padej.jumper.ast.Stmts.*;
import me.padej.jumper.jit.Code.Label;
import me.padej.jumper.lexer.TokenType;
import me.padej.jumper.runtime.JavaClass;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Bytecode generation for one function.
 *
 * Class: `final class FnN extends CompiledFunction { <init>(FunctionNode, Frame, Object[]);
 * Object invoke(Object[]); <R> body(<typed parameters>); }`.
 *
 * Variables: those not captured by nested functions -> JVM locals (int/long/double/Object without boxing);
 * captured ones -> Frame (as in the interpreter), so that closures see them.
 * Direct calls of declared functions (`int fib(int n)`) go through the typed body() without Object[].
 */
public final class Compiler {
    /** Table-shape speculation goes together with typed slots - one switch. */
    private static final boolean SPEC = me.padej.jumper.runtime.Opts.on("tableprims");
    private static final boolean CELLSITE = me.padej.jumper.runtime.Opts.on("cellsite");
    private static final boolean DUAL_ACC = me.padej.jumper.runtime.Opts.on("dualacc");
    private static final boolean GETSITE = me.padej.jumper.runtime.Opts.on("getsite");
    private static final boolean INT_ENTRY = me.padej.jumper.runtime.Opts.on("intentry");
    private static final boolean RAW_ARRAY = me.padej.jumper.runtime.Opts.on("rawarray");
    private static final boolean LITSITE = me.padej.jumper.runtime.Opts.on("litsite");
    private static final boolean INDY_CONCAT = me.padej.jumper.runtime.Opts.on("concat");
    private static final boolean FOLD_CALL = me.padej.jumper.runtime.Opts.on("foldcall");

    static final String OBJ = "java/lang/Object";
    static final String OBJ_D = "Ljava/lang/Object;";
    static final String OPS = "me/padej/jumper/runtime/Ops";
    static final String FRAME = "me/padej/jumper/interp/Frame";
    static final String SF = "me/padej/jumper/ast/FunctionNode$ScriptFunction";
    static final String FN = "me/padej/jumper/ast/FunctionNode";
    static final String CF = "me/padej/jumper/jit/CompiledFunction";
    static final String FC = "me/padej/jumper/runtime/FieldCache";
    static final String JTABLE = "me/padej/jumper/runtime/JTable";
    static final String SHAPE = "me/padej/jumper/runtime/Shape";
    static final String TF = "me/padej/jumper/ast/Fields$ThisField";
    static final String SF_ = "me/padej/jumper/ast/Fields$StaticField";
    static final String CN = "me/padej/jumper/ast/Classes$ClassNode";
    static final String JARRAY = "me/padej/jumper/runtime/JArray";
    static final String ITER = "java/util/Iterator";
    static final String CELL = "me/padej/jumper/runtime/Cell";
    /** Plain table (values/prims arrays): direct array reads are emitted only for it. */
    static final String PLAIN = "me/padej/jumper/runtime/JTable$Plain";

    final FunctionNode fn;
    final String className;
    final List<Object> consts = new ArrayList<>();
    private final IdentityHashMap<Object, Integer> constIndex = new IdentityHashMap<>();
    private final Captures caps;
    private final boolean needsFrame;

    private ClassBuilder cb;
    private Code c;
    private int indyBsm = -1;

    private int indyBsm() {
        if (indyBsm < 0) indyBsm = cb.bootstrap(Jit.PKG + "Indy", "bootstrap",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;");
        return indyBsm;
    }
    private int indyNewBsm = -1;

    private int indyNewBsm() {
        if (indyNewBsm < 0) indyNewBsm = cb.bootstrap(Jit.PKG + "Indy", "bootstrapNew",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;");
        return indyNewBsm;
    }
    private int frameLocal = -1;
    private int[] slotLocal;   // Object slot -> JVM local (-1 = in the Frame)
    /** Which DYN slots are kept unboxed (see Unbox); null until genBody. */
    private Unbox unbox;
    private int[] primLocal;   // p index -> JVM local (-1 = in the Frame)
    private int[] dualPrim;    // dual slot -> JVM local of its primitive half (-1 = not dual)
    private int[] dualFlag;    // dual slot -> JVM local of the "primitive half is live" flag
    private final Deque<Label[]> loops = new ArrayDeque<>(); // [continueLabel, breakLabel]
    /** Stack of active finally blocks: on break/continue/return through try-finally the finally body is inlined. loopDepth - depth of loops at entry. */
    private final Deque<Object[]> finallyStack = new ArrayDeque<>(); // [Stmt fin, Integer loopDepth]
    private final List<Integer> freeInt = new ArrayList<>(), freeLong = new ArrayList<>(), freeDouble = new ArrayList<>(), freeObj = new ArrayList<>();

    /**
     * The body is a static method: the function needs neither a frame, nor a closure, nor this for constants.
     * Then a call from another class is an invokestatic with a constant target, and C2 inlines it
     * like an ordinary Java method. This was the main obstacle: previously every call went through a chain of
     * instanceof + three checkcasts + ScriptFunction.compiled().
     */
    private final boolean staticBody;
    /**
     * Whether calls of the function to itself count as direct. During analysis - yes (otherwise recursion
     * would always look like a read of the enclosing frame and none would become static),
     * afterwards - exactly staticBody: if the body is not static after all, calling itself through
     * invokestatic is impossible, the ordinary checked path is needed.
     */
    private boolean selfDirect = true;

    Compiler(FunctionNode fn) {
        this.fn = fn;
        this.className = Jit.className(fn);
        // Direct-call targets are compiled in advance: otherwise, when the static-body decision is made,
        // their classes do not exist yet, Captures treats the reference to them as a read of the enclosing frame,
        // and no function that calls another would get a static body.
        precompileTargets(fn.body, 0);
        this.caps = Captures.analyze(fn, this::directCall);
        this.needsFrame = caps.hasInner || fn.forceFrame;
        this.staticBody = !needsFrame && !Captures.usesOuter(fn, this::directCall);
        this.selfDirect = this.staticBody;
        fn.jitStaticBody = this.staticBody;
        if (INT_ENTRY && staticBody && Unbox.ENABLED && fn.specInt == null) decideSpec();
    }

    /**
     * The int entry point {@code bodyI}: a second static body in the same class in which the `dyn`
     * parameters the code treats as ints (they meet only ints in arithmetic - {@code fib(n)}: `n < 2`,
     * `n - 1`) arrive as {@code int} and live in int locals, exactly like a `dyn` local initialized with
     * an int that {@link Unbox} proves stays one. A caller whose argument is statically an int calls it
     * directly - {@code fib(n - 1)} inside {@code bodyI} is, so the recursion never leaves it - and the
     * ordinary {@code body} forwards to it when the arguments turn out to be Integers.
     *
     * <p>Semantics do not change: a `dyn` slot that only ever holds ints already behaves like an int
     * (dynamic int arithmetic wraps like Java's), and a parameter the analysis cannot keep an int gets
     * no entry. The return value stays boxed unless every return is an int under that proof - the
     * result of a call cannot be assumed: the callee behind a top-level name may be replaced.
     */
    private void decideSpec() {
        boolean[] cand = new boolean[fn.nparams];
        boolean any = false;
        for (int i = 0; i < fn.nparams; i++) {
            int slot = fn.paramIndex[i];
            if (fn.paramTypes[i] == VarType.DYN && slot < fn.nslots && Unbox.paramIntEvidence(fn, slot)) { cand[i] = true; any = true; }
        }
        if (!any) return;
        Unbox u;
        while (true) {
            final boolean[] cs = cand.clone();
            u = Unbox.analyze(fn, s -> !isObjParam(s) || specSlot(cs, s), s -> false, s -> specSlot(cs, s));
            boolean dropped = false;
            any = false;
            for (int i = 0; i < fn.nparams; i++) {
                if (!cand[i]) continue;
                if (u.slotType(fn.paramIndex[i]) != VarType.INT) { cand[i] = false; dropped = true; }
                else any = true;
            }
            if (!any) return;
            if (!dropped) break;
        }
        VarType rk = fn.returnType == VarType.DYN ? u.returnKind(fn) : null;
        fn.specReturn = rk != null ? rk : fn.returnType;
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < fn.nparams; i++) sb.append(cand[i] ? "I" : desc(fn.paramTypes[i]));
        fn.specDesc = sb.append(')').append(desc(fn.specReturn)).toString();
        fn.specInt = cand;
        if (Jit.DEBUG) System.err.println("[jit] " + fn.name + " gets an int entry bodyI" + fn.specDesc);
    }

    private boolean isObjParam(int slot) {
        for (int i = 0; i < fn.nparams; i++) if (!fn.paramTypes[i].isPrimitive() && fn.paramIndex[i] == slot) return true;
        return false;
    }

    private boolean specSlot(boolean[] spec, int slot) {
        if (spec == null) return false;
        for (int i = 0; i < fn.nparams; i++) if (spec[i] && fn.paramIndex[i] == slot) return true;
        return false;
    }

    /** AST traversal before generation: compile the functions that will be called directly. */
    private void precompileTargets(Object node, int depth) {
        if (node == null || depth > 64) return;
        if (node instanceof FunctionNode inner) { precompileTargets(inner.body, depth + 1); return; }
        if (node instanceof Call c && c.fixedTarget && c.target != null
                && c.target != fn && c.target.jitClass == null && !c.target.jitFailed
                && !Jit.inProgress(c.target)) {
            Jit.compile(c.target);
        }
        for (Object child : Captures.children(node)) precompileTargets(child, depth);
    }

    /**
     * Whether this call will be made directly (invokestatic, without a variable read and without a check).
     * Used both during analysis and during generation - must give the same answer.
     *
     * <p>Self-recursion is a special case: the static-body decision is being made right now,
     * and assuming "yes" here is correct, because the rest of the analysis is precisely what
     * checks whether the body needs a frame for other reasons.
     */
    private boolean directCall(Call c) {
        if (!c.fixedTarget || c.target == null || c.target.jitFailed) return false;
        if (c.target == fn) return selfDirect;
        return c.target.jitClass != null && c.target.jitStaticBody;
    }

    static final class Unsupported extends RuntimeException {
        Unsupported(String msg) { super(msg); }
    }

    // ================= class assembly =================

    byte[] generate() {
        cb = new ClassBuilder(className, CF);
        cb.addField(0x0009, "K", "[" + OBJ_D);   // public static Object[] K - function constants
        genConstructor();
        // bodyI first: if it cannot be generated, body must not forward to it (see genSpecForwarder)
        if (fn.specInt != null) {
            try {
                genBody(true);
            } catch (RuntimeException e) {
                // Callers (and body itself) may already call bodyI: it must exist. A forwarder to body keeps them right.
                if (Jit.DEBUG) System.err.println("[jit] " + fn.name + ": bodyI fell back to a forwarder: " + e);
                specFailed = true;
                genSpecForwarder();
            }
        }
        genBody(false);
        genInvoke();
        return cb.toBytes();
    }

    private void genConstructor() {
        Code k = new Code(cb.cp, List.of(className, FN, FRAME, "[" + OBJ_D));
        k.aload(0);
        k.aload(1);
        k.aload(2);
        k.aload(3);
        k.invokespecial(CF, "<init>", "(L" + FN + ";L" + FRAME + ";[" + OBJ_D + ")V");
        k.vreturn();
        cb.addMethod(0x0001, "<init>", "(L" + FN + ";L" + FRAME + ";[" + OBJ_D + ")V", k.finish());
    }

    static String desc(VarType t) {
        return switch (t) {
            case INT -> "I";
            case LONG -> "J";
            case DOUBLE -> "D";
            case BOOLEAN -> "Z";
            default -> OBJ_D;
        };
    }

    static String bodyDesc(FunctionNode f) {
        StringBuilder sb = new StringBuilder("(");
        for (VarType t : f.paramTypes) sb.append(desc(t));
        return sb.append(')').append(desc(f.returnType)).toString();
    }

    /** invoke(Object[] args): unbox the arguments -> body -> box the result. */
    private void genInvoke() {
        Code k = new Code(cb.cp, List.of(className, "[" + OBJ_D));
        if (!staticBody) k.aload(0);
        for (int i = 0; i < fn.nparams; i++) {
            k.aload(1);
            k.iconst(i);
            k.invokestatic(OPS, "arg", "([" + OBJ_D + "I)" + OBJ_D);
            switch (fn.paramTypes[i]) {
                case INT -> k.invokestatic(OPS, "argInt", "(" + OBJ_D + ")I");
                case LONG -> k.invokestatic(OPS, "argLong", "(" + OBJ_D + ")J");
                case DOUBLE -> k.invokestatic(OPS, "argDouble", "(" + OBJ_D + ")D");
                case BOOLEAN -> k.invokestatic(OPS, "argBool", "(" + OBJ_D + ")Z");
                case STRING -> k.invokestatic(OPS, "toStringSlot", "(" + OBJ_D + ")" + OBJ_D);
                default -> {}
            }
        }
        if (staticBody) k.invokestatic(className, "body", bodyDesc(fn));
        else k.invokevirtual(className, "body", bodyDesc(fn));
        box(k, fn.returnType);
        k.areturn();
        cb.addMethod(0x0004, "invoke", "([" + OBJ_D + ")" + OBJ_D, k.finish());
    }

    private static void box(Code k, VarType t) {
        switch (t) {
            case INT -> k.invokestatic(OPS, "box", "(I)" + OBJ_D);
            case LONG -> k.invokestatic(OPS, "box", "(J)" + OBJ_D);
            case DOUBLE -> k.invokestatic(OPS, "box", "(D)" + OBJ_D);
            case BOOLEAN -> k.invokestatic(OPS, "box", "(Z)" + OBJ_D);
            default -> {}
        }
    }

    private static String jvmType(VarType t) {
        return switch (t) {
            case INT, BOOLEAN -> "I";
            case LONG -> "J";
            case DOUBLE -> "D";
            default -> OBJ;
        };
    }

    private void genBody(boolean spec) {
        inSpec = spec;
        curReturn = spec ? fn.specReturn : fn.returnType;
        boolean[] sp = spec ? fn.specInt : null;
        freeInt.clear(); freeLong.clear(); freeDouble.clear(); freeObj.clear();
        frameLocal = -1;
        litKinds = null;   // computed from this body's unbox analysis
        List<String> params = new ArrayList<>();
        if (!staticBody) params.add(className);   // for a static body slot 0 is already the first parameter
        for (int i = 0; i < fn.nparams; i++) params.add(sp != null && sp[i] ? "I" : jvmType(fn.paramTypes[i]));
        c = new Code(cb.cp, params);

        // variable allocation
        slotLocal = new int[fn.nslots];
        primLocal = new int[fn.nprims];
        java.util.Arrays.fill(slotLocal, -2);
        java.util.Arrays.fill(primLocal, -2);
        int jvm = staticBody ? 0 : 1;
        for (int i = 0; i < fn.nparams; i++) {
            VarType t = fn.paramTypes[i];
            int idx = fn.paramIndex[i];
            if (t.isPrimitive()) primLocal[idx] = jvm; else slotLocal[idx] = jvm;
            jvm += (t == VarType.LONG || t == VarType.DOUBLE) ? 2 : 1;
        }
        if (needsFrame) frameLocal = c.declareLocal(FRAME);
        // Unboxing of DYN slots: the analysis sees only those that live in a JVM local.
        unbox = Unbox.analyze(fn, s -> !(needsFrame && (caps.slots.get(s) || fn.forceFrame))
                && (slotLocal[s] == -2 || specSlot(sp, s)),
                s -> !(needsFrame && (caps.slots.get(s) || fn.forceFrame)) && slotLocal[s] >= 0 && !specSlot(sp, s),
                s -> specSlot(sp, s));
        if (sp != null) {
            for (int i = 0; i < fn.nparams; i++)
                if (sp[i] && unbox.slotType(fn.paramIndex[i]) != VarType.INT) throw new Unsupported("int entry: parameter " + i + " is not an int");
        }
        for (int s = 0; s < fn.nslots; s++) {
            if (needsFrame && (caps.slots.get(s) || fn.forceFrame)) slotLocal[s] = -1;
            else if (slotLocal[s] == -2) {
                VarType u = unbox.slotType(s);
                slotLocal[s] = c.declareLocal(u == null ? OBJ : jvmType(u));
            }
        }
        for (int i = 0; i < fn.nprims; i++) {
            if (needsFrame && (caps.prims.get(i) || fn.forceFrame)) primLocal[i] = -1;
            else if (primLocal[i] == -2) primLocal[i] = c.declareLocal(jvmType(fn.primTypes.get(i)));
        }
        // Dual slots: a primitive local and a flag next to the Object one (see Unbox.dualKind).
        // All three are initialized by the prologue of Code, so the verifier is happy on every path.
        dualPrim = new int[fn.nslots];
        dualFlag = new int[fn.nslots];
        java.util.Arrays.fill(dualPrim, -1);
        java.util.Arrays.fill(dualFlag, -1);
        for (int s = 0; s < fn.nslots; s++) {
            VarType k = unbox.dualKind(s);
            if (k == null || slotLocal[s] < 0 || unbox.slotType(s) != null) continue;
            dualPrim[s] = c.declareLocal(jvmType(k));
            dualFlag[s] = c.declareLocal("I");
        }

        // a function entry is a back-edge of the call graph: recursion with no loop in it (f() { f(); f(); })
        // must stop on cancel() as a while (true) does - and a callback Java calls in a loop of its own
        // (stream.forEach(x -> ...)) stops on its next call
        cancelCheck(me.padej.jumper.ast.Stmts.cancelOf(fn.loader));

        // prologue: a frame for captured variables, copying of captured parameters
        if (needsFrame) {
            c.newObj(FRAME);
            c.dup();
            c.iconst(fn.nslots);
            c.iconst(fn.nprims);
            c.aload(0);
            c.getfield(CF, "closure", "L" + FRAME + ";");
            c.invokespecial(FRAME, "<init>", "(IIL" + FRAME + ";)V");
            c.astore(frameLocal);
            if (fn.forceFrame) {
                c.aload(0);
                c.aload(frameLocal);
                c.putfield(CF, "lastFrame", "L" + FRAME + ";");
            }
            int j = staticBody ? 0 : 1;
            for (int i = 0; i < fn.nparams; i++) {
                VarType t = fn.paramTypes[i];
                int idx = fn.paramIndex[i];
                if (t.isPrimitive() && primLocal[idx] == -1) {
                    c.aload(frameLocal);
                    c.getfield(FRAME, "p", "[J");
                    c.iconst(idx);
                    c.load(j);
                    toBits(t);
                    c.lastore();
                } else if (!t.isPrimitive() && slotLocal[idx] == -1) {
                    c.aload(frameLocal);
                    c.getfield(FRAME, "slots", "[" + OBJ_D);
                    c.iconst(idx);
                    c.aload(j);
                    c.aastore();
                }
                j += (t == VarType.LONG || t == VarType.DOUBLE) ? 2 : 1;
            }
        }

        // the ordinary body of a function with an int entry: Integer arguments go there
        if (!spec && fn.specInt != null && !specFailed) specDispatch();

        // dual parameters: classify the incoming Object once (Unbox.paramKind)
        for (int i = 0; i < fn.nparams; i++) {
            int s = fn.paramIndex[i];
            if (fn.paramTypes[i] != VarType.DYN || s >= fn.nslots || dualPrim[s] < 0) continue;
            c.aload(slotLocal[s]);
            storeDualObj(s, unbox.dualKind(s));
        }

        stmt(fn.body);
        if (c.reachable()) {
            defaultValue(curReturn);
            ret(curReturn);
        }
        cb.addMethod(staticBody ? 0x0009 : 0x0001, spec ? "bodyI" : "body", spec ? fn.specDesc : bodyDesc(fn), c.finish());
        inSpec = false;
        curReturn = fn.returnType;
    }

    /** Current body: the int entry (bodyI) or the ordinary one; and the type the current body returns. */
    private boolean inSpec, specFailed;
    private VarType curReturn;

    /** Prologue of body: if every int-entry parameter holds an Integer, the call is bodyI's. */
    private void specDispatch() {
        Label normal = c.label();
        for (int i = 0; i < fn.nparams; i++) {
            if (!fn.specInt[i]) continue;
            c.aload(slotLocal[fn.paramIndex[i]]);
            c.instanceOf("java/lang/Integer");
            c.ifeq(normal);
        }
        int j = 0;
        for (int i = 0; i < fn.nparams; i++) {
            VarType t = fn.paramTypes[i];
            if (fn.specInt[i]) {
                c.aload(j);
                c.checkcast("java/lang/Integer");
                c.invokevirtual("java/lang/Integer", "intValue", "()I");
            } else c.load(j);
            j += (t == VarType.LONG || t == VarType.DOUBLE) ? 2 : 1;
        }
        c.invokestatic(className, "bodyI", fn.specDesc);
        convert(fn.specReturn, fn.returnType == VarType.STRING ? VarType.DYN : fn.returnType);
        ret(fn.returnType);
        c.mark(normal);
    }

    /** bodyI that only boxes its int parameters and calls body: used when bodyI itself could not be generated. */
    private void genSpecForwarder() {
        List<String> params = new ArrayList<>();
        for (int i = 0; i < fn.nparams; i++) params.add(fn.specInt[i] ? "I" : jvmType(fn.paramTypes[i]));
        Code k = new Code(cb.cp, params);
        int j = 0;
        for (int i = 0; i < fn.nparams; i++) {
            VarType t = fn.paramTypes[i];
            if (fn.specInt[i]) { k.iload(j); k.invokestatic(OPS, "box", "(I)" + OBJ_D); }
            else k.load(j);
            j += (t == VarType.LONG || t == VarType.DOUBLE) ? 2 : 1;
        }
        // body has no dispatch to bodyI when bodyI failed (specFailed), so this does not loop
        k.invokestatic(className, "body", bodyDesc(fn));
        if (fn.specReturn == VarType.INT) k.invokestatic(OPS, "unboxInt", "(" + OBJ_D + ")I");
        ret(k, fn.specReturn);
        cb.addMethod(0x0009, "bodyI", fn.specDesc, k.finish());
    }

    private static void ret(Code k, VarType t) {
        switch (t) {
            case INT, BOOLEAN -> k.ireturn();
            case LONG -> k.lreturn();
            case DOUBLE -> k.dreturn();
            default -> k.areturn();
        }
    }

    private void defaultValue(VarType t) {
        switch (t) {
            case INT, BOOLEAN -> c.iconst(0);
            case LONG -> c.lconst(0);
            case DOUBLE -> c.dconst(0);
            default -> c.aconstNull();
        }
    }

    private void ret(VarType t) {
        switch (t) {
            case INT, BOOLEAN -> c.ireturn();
            case LONG -> c.lreturn();
            case DOUBLE -> c.dreturn();
            default -> c.areturn();
        }
    }

    // ================= helpers =================

    private void konst(Object v, String castTo) {
        c.getstatic(className, "K", "[" + OBJ_D);
        c.iconst(konstIndex(v));
        c.aaload();
        if (castTo != null) c.checkcast(castTo);
    }

    /** Index of a constant in K (for invokedynamic bootstrap arguments). */
    private int konstIndex(Object v) {
        Integer i = constIndex.get(v);
        if (i == null) {
            i = consts.size();
            consts.add(v);
            constIndex.put(v, i);
        }
        return i;
    }

    /** Reference to the frame at depth depth (0 - own, 1 - closure, ...). */
    private void frameRef(int depth) {
        if (depth == 0) {
            if (frameLocal < 0) throw new Unsupported("frame access without frame");
            c.aload(frameLocal);
            return;
        }
        c.aload(0);
        c.getfield(CF, "closure", "L" + FRAME + ";");
        if (depth > 1) {
            c.iconst(depth - 1);
            c.invokevirtual(FRAME, "up", "(I)L" + FRAME + ";");
        }
    }

    /** Primitive-typed value on the stack -> long bits for Frame.p[]. */
    private void toBits(VarType t) {
        switch (t) {
            case INT, BOOLEAN -> c.i2l();
            case LONG -> {}
            case DOUBLE -> c.invokestatic("java/lang/Double", "doubleToRawLongBits", "(D)J");
            default -> throw new IllegalStateException();
        }
    }

    private void fromBits(VarType t) {
        switch (t) {
            case INT, BOOLEAN -> c.l2i();
            case LONG -> {}
            case DOUBLE -> c.invokestatic("java/lang/Double", "longBitsToDouble", "(J)D");
            default -> throw new IllegalStateException();
        }
    }

    /** Coercion of the value on the stack from type from to want. */
    private void convert(VarType from, VarType want) {
        if (from == VarType.STRING) from = VarType.DYN;
        if (want == VarType.STRING) want = VarType.DYN;
        if (from == want) return;
        switch (want) {
            case DYN -> box(c, from);
            case INT -> {
                if (from == VarType.DYN) c.invokestatic(OPS, "unboxInt", "(" + OBJ_D + ")I");
                else throw new Unsupported("narrowing " + from + " -> int");
            }
            case LONG -> {
                switch (from) {
                    case INT -> c.i2l();
                    case DYN -> c.invokestatic(OPS, "unboxLong", "(" + OBJ_D + ")J");
                    default -> throw new Unsupported("narrowing " + from + " -> long");
                }
            }
            case DOUBLE -> {
                switch (from) {
                    case INT -> c.i2d();
                    case LONG -> c.l2d();
                    case DYN -> c.invokestatic(OPS, "unboxDouble", "(" + OBJ_D + ")D");
                    default -> throw new Unsupported("narrowing " + from + " -> double");
                }
            }
            case BOOLEAN -> {
                if (from == VarType.DYN) c.invokestatic(OPS, "truthy", "(" + OBJ_D + ")Z");
                else { c.pop1(); c.iconst(1); } // a number (not null) is always truthy
            }
            default -> throw new IllegalStateException();
        }
    }

    private int temp(VarType t) {
        List<Integer> free = switch (t) {
            case INT, BOOLEAN -> freeInt;
            case LONG -> freeLong;
            case DOUBLE -> freeDouble;
            default -> freeObj;
        };
        if (!free.isEmpty()) return free.remove(free.size() - 1);
        return c.declareLocal(jvmType(t));
    }

    private void release(VarType t, int slot) {
        switch (t) {
            case INT, BOOLEAN -> freeInt.add(slot);
            case LONG -> freeLong.add(slot);
            case DOUBLE -> freeDouble.add(slot);
            default -> freeObj.add(slot);
        }
    }

    // ================= statements =================

    private void stmt(Stmt s) {
        c.line(s.line);
        if (s instanceof Block b) {
            for (int i = 0; i < b.stmts.length; ) {
                int n = region(b.stmts, i);
                if (n > 0) { i += n; continue; }
                stmt(b.stmts[i++]);
            }
        } else if (s instanceof ExprStmt es) {
            exprStmt(es.e);
        } else if (s instanceof VarDecl vd) {
            VarType dk = vd.type == VarType.DYN && vd.init != null ? dual(vd.slot, 0) : null;
            if (dk != null) {
                dualAssign(vd.slot, dk, vd.init, true);
                return;
            }
            storeSlot(vd.slot, 0, () -> {
                if (vd.init == null) c.aconstNull();
                else {
                    expr(vd.init, VarType.DYN);
                    if (vd.type == VarType.STRING) c.invokestatic(OPS, "toStringSlot", "(" + OBJ_D + ")" + OBJ_D);
                }
            });
        } else if (s instanceof PrimVarDecl pd) {
            storePrim(pd.idx, 0, pd.t, () -> {
                if (pd.init == null) defaultValue(pd.t);
                else expr(pd.init, pd.t);
            });
        } else if (s instanceof FuncDecl fd) {
            storeSlot(fd.slot, 0, () -> newClosure(fd.fn));
        } else if (s instanceof Classes.ClassDecl cd) {
            if (frameLocal < 0) throw new Unsupported("class without frame");
            if (cd.globalVars != null) throw new Unsupported("REPL class");
            storeSlot(cd.slot, 0, () -> {
                konst(cd, "me/padej/jumper/ast/Classes$ClassDecl");
                c.aload(frameLocal);
                if (cd.parent != null) expr(cd.parent, VarType.DYN); else c.aconstNull();
                c.invokevirtual("me/padej/jumper/ast/Classes$ClassDecl", "create", "(L" + FRAME + ";" + OBJ_D + ")Lme/padej/jumper/runtime/JClass;");
            });
        } else if (s instanceof If i) {
            Label lElse = c.label(), lEnd = c.label();
            jump(i.cond, lElse, false);
            stmt(i.then);
            if (i.otherwise != null) {
                c.goTo(lEnd);
                c.mark(lElse);
                stmt(i.otherwise);
                c.mark(lEnd);
            } else c.mark(lElse);
        } else if (s instanceof While w) {
            Label lStart = c.label(), lExit = c.label();
            c.mark(lStart);
            cancelCheck(w.cancel);
            jump(w.cond, lExit, false);
            loops.push(new Label[]{lStart, lExit});
            stmt(w.body);
            loops.pop();
            c.goTo(lStart);
            c.mark(lExit);
        } else if (s instanceof DoWhile dw) {
            Label lStart = c.label(), lCont = c.label(), lExit = c.label();
            c.mark(lStart);
            loops.push(new Label[]{lCont, lExit});
            stmt(dw.body);
            loops.pop();
            c.mark(lCont);
            cancelCheck(dw.cancel);
            jump(dw.cond, lStart, true);
            c.mark(lExit);
        } else if (s instanceof For f) {
            if (f.init != null) stmt(f.init);
            Label lStart = c.label(), lCont = c.label(), lExit = c.label();
            c.mark(lStart);
            cancelCheck(f.cancel);
            if (f.cond != null) jump(f.cond, lExit, false);
            loops.push(new Label[]{lCont, lExit});
            stmt(f.body);
            loops.pop();
            c.mark(lCont);
            if (f.update != null) exprStmt(f.update);
            c.goTo(lStart);
            c.mark(lExit);
        } else if (s instanceof ForEach fe) {
            forEach(fe);
        } else if (s instanceof Return r) {
            if (finallyStack.isEmpty()) {
                if (r.value == null) defaultValue(curReturn);
                else expr(r.value, curReturn);
                ret(curReturn);
            } else {
                VarType rt = curReturn == VarType.STRING ? VarType.DYN : curReturn;
                int tmp = temp(rt);
                if (r.value == null) defaultValue(curReturn);
                else expr(r.value, curReturn);
                c.store(tmp);
                runFinallies(0);
                c.load(tmp);
                ret(curReturn);
                release(rt, tmp);
            }
        } else if (s instanceof Break) {
            if (loops.isEmpty()) throw new Unsupported("break outside loop");
            runFinallies(loops.size());
            c.goTo(loops.peek()[1]);
        } else if (s instanceof Continue) {
            if (loops.isEmpty()) throw new Unsupported("continue outside loop");
            runFinallies(loops.size());
            c.goTo(loops.peek()[0]);
        } else if (s instanceof Throw t) {
            expr(t.value, VarType.DYN);
            c.invokestatic("me/padej/jumper/runtime/JmpThrow", "raise", "(" + OBJ_D + ")Ljava/lang/RuntimeException;");
            c.athrowKeep();
        } else if (s instanceof Try t) {
            tryStmt(t);
        } else if (s instanceof Empty) {
            // nothing
        } else throw new Unsupported("stmt " + s.getClass().getSimpleName());
    }

    /** Inlines the finally bodies the jump passes through: all with loopDepth >= targetLoopDepth. */
    private void runFinallies(int targetLoopDepth) {
        // finallyStack: top is the innermost. Run from inner to outer, temporarily popping from the stack
        // so that a nested return/break inside the finally does not loop forever.
        List<Object[]> popped = new ArrayList<>();
        try {
            while (!finallyStack.isEmpty() && (Integer) finallyStack.peek()[1] >= targetLoopDepth) {
                Object[] f = finallyStack.pop();
                popped.add(f);
                stmt((Stmt) f[0]);
            }
        } finally {
            for (int i = popped.size() - 1; i >= 0; i--) finallyStack.push(popped.get(i));
        }
    }

    /**
     * try/catch/finally the javac way: the try body in the range [start, end) with a catch handler and
     * a catch-all for finally; finally is inlined on the normal exit, at the end of catch and in the catch-all (with rethrow).
     */
    private void tryStmt(Try t) {
        Label start = c.label(), endTry = c.label(), after = c.label();
        Label catchH = t.handler != null ? c.handlerLabel("java/lang/Throwable") : null;
        Label finH = t.fin != null ? c.handlerLabel("java/lang/Throwable") : null;
        if (t.fin != null) finallyStack.push(new Object[]{t.fin, loops.size()});
        c.markPos(start);
        stmt(t.body);
        c.markPos(endTry);
        if (t.fin != null) finallyStack.pop();
        // normal exit
        if (c.reachable()) {
            if (t.fin != null) stmt(t.fin);
            c.goTo(after);
        }
        Label endCatch = null;
        if (catchH != null) {
            c.tryCatch(start, endTry, catchH, "java/lang/Throwable");
            c.markHandler(catchH);
            // StackOverflowError -> JmpError, JmpThrow -> value, wrapped -> Java exception
            c.invokestatic("me/padej/jumper/jit/Compiler", "caught", "(Ljava/lang/Throwable;)" + OBJ_D);
            int cv = temp(VarType.DYN);
            c.astore(cv);
            storeSlot(t.catchSlot, 0, () -> c.aload(cv));
            release(VarType.DYN, cv);
            endCatch = c.label();
            if (t.fin != null) finallyStack.push(new Object[]{t.fin, loops.size()});
            stmt(t.handler);
            c.markPos(endCatch);
            if (t.fin != null) finallyStack.pop();
            if (c.reachable()) {
                if (t.fin != null) stmt(t.fin);
                c.goTo(after);
            }
        }
        if (finH != null) {
            c.tryCatch(start, endTry, finH, null);
            if (catchH != null) c.tryCatch(catchH, endCatch, finH, null);
            c.markHandler(finH);
            int ex = temp(VarType.DYN);
            c.astore(ex);
            // a fatal exception (policy violation, cancellation) leaves without the finally: see Ops.fatal
            c.aload(ex);
            c.invokestatic("me/padej/jumper/jit/Compiler", "throwIfFatal", "(Ljava/lang/Object;)V");
            stmt(t.fin);
            c.aload(ex);
            c.checkcast("java/lang/Throwable");
            c.athrowKeep();
            release(VarType.DYN, ex);
        }
        c.mark(after);
    }

    /** In a finally handler: a fatal exception goes on without running the finally (Ops.fatal). */
    public static void throwIfFatal(Object t) {
        if (t instanceof Throwable th && me.padej.jumper.runtime.Ops.fatal(th))
            throw th instanceof StackOverflowError ? me.padej.jumper.runtime.Ops.stackOverflow() : (RuntimeException) th;
    }

    /** Value for catch (e): see JmpThrow.caught (which lets the fatal ones through). */
    public static Object caught(Throwable t) {
        if (me.padej.jumper.runtime.Ops.fatal(t)) throw t instanceof StackOverflowError ? me.padej.jumper.runtime.Ops.stackOverflow() : (RuntimeException) t;
        if (t instanceof StackOverflowError) return new me.padej.jumper.runtime.JmpError("Stack overflow");
        if (t instanceof ArithmeticException) return new me.padej.jumper.runtime.JmpError("Division by zero");
        return me.padej.jumper.runtime.JmpThrow.caught(t);
    }

    /** Cancellation check on the back edge: only if the host enabled cancellable (otherwise not a single instruction). */
    private void cancelCheck(ScriptLoader cancel) {
        if (cancel == null) return;
        // `if (loader.cancelError != null) throw loader.cancelError;` - the throw uses only what the check
        // itself resolved on its first run: at the bottom of a recursion out of stack, resolving a new
        // constant (loading Ops) would be a StackOverflowError the script could catch and recurse again
        konst(cancel, "me/padej/jumper/jit/ScriptLoader");
        c.getfield("me/padej/jumper/jit/ScriptLoader", "cancelError", "Lme/padej/jumper/runtime/JmpError;");
        Label ok = c.label();
        c.ifnull(ok);
        konst(cancel, "me/padej/jumper/jit/ScriptLoader");
        c.getfield("me/padej/jumper/jit/ScriptLoader", "cancelError", "Lme/padej/jumper/runtime/JmpError;");
        c.athrow();
        c.mark(ok);
    }

    private void forEach(ForEach fe) {
        expr(fe.iterable, VarType.DYN);
        c.invokestatic(OPS, "iter", "(" + OBJ_D + ")L" + ITER + ";");
        int it = c.declareLocal(ITER);
        c.astore(it);
        Label lStart = c.label(), lExit = c.label();
        c.mark(lStart);
        cancelCheck(fe.cancel);
        c.aload(it);
        c.invokeinterface(ITER, "hasNext", "()Z");
        c.ifeq(lExit);
        Runnable next = () -> {
            c.aload(it);
            c.invokeinterface(ITER, "next", "()" + OBJ_D);
        };
        if (fe.type.isPrimitive()) {
            storePrim(fe.slot, 0, fe.type, () -> {
                next.run();
                switch (fe.type) {
                    case INT -> c.invokestatic(OPS, "argInt", "(" + OBJ_D + ")I");
                    case LONG -> c.invokestatic(OPS, "argLong", "(" + OBJ_D + ")J");
                    case DOUBLE -> c.invokestatic(OPS, "argDouble", "(" + OBJ_D + ")D");
                    default -> c.invokestatic(OPS, "argBool", "(" + OBJ_D + ")Z");
                }
            });
        } else {
            storeSlot(fe.slot, 0, () -> {
                next.run();
                if (fe.type == VarType.STRING) c.invokestatic(OPS, "toStringSlot", "(" + OBJ_D + ")" + OBJ_D);
                if (fe.cls != null) { konst(fe.cls, CN); c.invokestatic(OPS, "checkClass", "(" + OBJ_D + "L" + CN + ";)" + OBJ_D); }
            });
        }
        loops.push(new Label[]{lStart, lExit});
        stmt(fe.body);
        loops.pop();
        c.goTo(lStart);
        c.mark(lExit);
    }

    /** Expression statement: the result is not needed. */
    private void exprStmt(Expr e) {
        if (assignLike(e, true)) return;
        expr(e, VarType.DYN);
        c.pop1();
    }

    // ================= variable access =================

    /** Store into an Object slot the value that value produces. */
    private void storeSlot(int slot, int depth, Runnable value) {
        int local = depth == 0 ? slotLocal[slot] : -1;
        VarType dk = dual(slot, depth);
        if (dk != null) {
            value.run();
            storeDualObj(slot, dk);
            return;
        }
        VarType u = unboxed(slot, depth);
        if (u != null) {
            // The value arrives as Object: unbox at the boundary. This way any site
            // that did not get a typed path stays correct - just not fast.
            value.run();
            convert(VarType.DYN, u);
            c.store(local);
            return;
        }
        if (local >= 0) {
            value.run();
            c.astore(local);
        } else {
            frameRef(depth);
            c.getfield(FRAME, "slots", "[" + OBJ_D);
            c.iconst(slot);
            value.run();
            c.aastore();
        }
    }

    /** Primitive type in which the slot sits in a JVM local, or null. */
    private VarType unboxed(int slot, int depth) {
        if (depth != 0 || unbox == null) return null;
        // Safety net: only a slot that really sits in a JVM local may be unboxed.
        // If the analysis and the allocation disagree, better to lose the optimization than to generate
        // a store into local -1 and drop the compilation of the whole function into a silent fallback to Tier 0.
        if (slotLocal == null || slot >= slotLocal.length || slotLocal[slot] < 0) return null;
        return unbox.slotType(slot);
    }

    /** Kind of the dual representation of a slot in a JVM local, or null. */
    private VarType dual(int slot, int depth) {
        if (depth != 0 || unbox == null || dualPrim == null || slot >= dualPrim.length || dualPrim[slot] < 0) return null;
        return unbox.dualKind(slot);
    }

    /** Push the value of a dual slot as Object: the primitive half boxed, or the Object half. */
    private void loadDual(int slot, VarType k) {
        if (unbox.paramDual(slot)) { c.aload(slotLocal[slot]); return; }   // a parameter: the Object is still there, no re-boxing
        Label obj = c.label(), end = c.label();
        c.iload(dualFlag[slot]);
        c.ifeq(obj);
        c.load(dualPrim[slot]);
        box(c, k);
        c.goTo(end);
        c.mark(obj);
        c.aload(slotLocal[slot]);
        c.mark(end);
    }

    /** Store the Object on the stack into a dual slot: a box of the right kind goes to the primitive half. */
    private void storeDualObj(int slot, VarType k) {
        Label obj = c.label(), end = c.label();
        String bx = switch (k) { case INT -> "java/lang/Integer"; case LONG -> "java/lang/Long"; default -> "java/lang/Double"; };
        c.dup();
        c.instanceOf(bx);
        c.ifeq(obj);
        unboxExact(k);
        c.store(dualPrim[slot]);
        c.iconst(1);
        c.istore(dualFlag[slot]);
        c.goTo(end);
        c.mark(obj);
        c.astore(slotLocal[slot]);
        c.iconst(0);
        c.istore(dualFlag[slot]);
        c.mark(end);
    }

    /** Unbox an Object already known (by instanceof) to be the box of kind k: a cast and a getter, no type switch. */
    private void unboxExact(VarType k) {
        switch (k) {
            case INT -> { c.checkcast("java/lang/Integer"); c.invokevirtual("java/lang/Integer", "intValue", "()I"); }
            case LONG -> { c.checkcast("java/lang/Long"); c.invokevirtual("java/lang/Long", "longValue", "()J"); }
            default -> { c.checkcast("java/lang/Double"); c.invokevirtual("java/lang/Double", "doubleValue", "()D"); }
        }
    }

    /** Store the primitive of kind k on the stack into the primitive half of a dual slot. */
    private void storeDualPrim(int slot) {
        c.store(dualPrim[slot]);
        c.iconst(1);
        c.istore(dualFlag[slot]);
    }

    /**
     * Assignment to a dual slot. Order of preference: the value is arithmetic over table fields and
     * dual locals - guard, compute in the slot's kind, store the primitive; the value has that
     * static type - store the primitive; otherwise the generic Object path with a run-time check.
     *
     * <p>Tried and rejected: "dual temporaries" - every dynamic arithmetic node classified at run
     * time (Integer? Long? Double?) and computed in primitives, with Ops only as the fallback. On
     * paper it removes all boxing; measured, it was 2-6x slower than the plain generic path
     * (call_static_1arg 35 -> 200 ms): the flag-and-halves control flow puts every value behind
     * a merge, and C2 can no longer scalar-replace the boxes it removes on its own when the code is
     * straight `Ops.add(box(acc), v)` followed by one instanceof. Keep the generated code simple;
     * the JIT does the rest.
     */
    private void dualAssign(int slot, VarType k, Expr value, boolean discard) {
        boolean done = false;
        Spec sp = specSetup(value, 1);
        if (sp != null) {
            specEnter(sp);
            VarType t = st(value);
            specLeave();
            if (t == k) {
                Label slow = c.label(), end = c.label();
                specEnter(sp);
                specGuards(sp, slow);
                expr(value, k);
                storeDualPrim(slot);
                specLeave();
                c.goTo(end);
                c.mark(slow);
                noSpec = true;
                expr(value, VarType.DYN);
                storeDualObj(slot, k);
                noSpec = false;
                c.mark(end);
                specRelease(sp);
                done = true;
            }
        }
        if (!done && DUAL_ACC && dualAccumulate(slot, k, value)) done = true;
        if (!done) {
            VarType t = st(value);
            if (t == k) {
                expr(value, k);
                storeDualPrim(slot);
            } else {
                expr(value, VarType.DYN);
                storeDualObj(slot, k);
            }
        }
        if (!discard) loadDual(slot, k);
    }

    /**
     * `acc += <dynamic>` (also `-=`, `*=`, `acc = acc + e`) on a live dual slot: evaluate the right-hand
     * side as Object, and if it is a box the slot's kind absorbs exactly (Integer into an int slot;
     * Integer/Long into a long one; Integer/Long/Double into a double one), compute in primitives and
     * stay in the primitive half. Anything else - a slot that is not live, a String, a Double into a
     * long slot - is the generic `Ops.add(box(acc), v)` + reclassification, as before.
     *
     * <p>Why it is not the rejected "dual temporaries" (see {@link #dualAssign}): only the
     * <i>incoming</i> value is classified, once, at the point where it is consumed, and the
     * accumulator itself never leaves its primitive half - there is no merge of boxes to scalar-replace.
     * The generic form boxed the accumulator on every iteration (`Long.valueOf(acc)` outside the
     * cache range) and C2 could not remove that box once the right-hand side came out of a
     * polymorphic call: container, `call_poly4/dyn` 153 -> ~98 ms, `call_mono/dyn` 235 -> ~68 ms,
     * which is what `long sum` in place of `dyn sum` gives. The fast branch is exactly Ops semantics:
     * Long+Integer is `x.longValue() + y.longValue()`, Double+Long is `x.doubleValue() + y.doubleValue()`,
     * Integer+Integer wraps like int.
     */
    private boolean dualAccumulate(int slot, VarType k, Expr value) {
        if (!(value instanceof Binary b) || !(b.left instanceof Local l) || l.slot != slot) return false;
        String opName = switch (b.op) { case PLUS, PLUSEQ -> "add"; case MINUS, MINUSEQ -> "sub"; case STAR, STAREQ -> "mul"; default -> null; };
        if (opName == null || unbox.paramDual(slot)) return false;
        VarType rt = st(b.right);
        if (rt.isNumeric() || rt == VarType.BOOLEAN) return false;   // statically typed: the ordinary paths handle it
        if (writesSlot(b.right, slot, 0)) return false;
        int v = temp(VarType.DYN);
        expr(b.right, VarType.DYN);
        c.astore(v);
        Label generic = c.label(), end = c.label();
        c.iload(dualFlag[slot]);
        c.ifeq(generic);
        String[] boxes = switch (k) {
            case INT -> new String[]{"java/lang/Integer"};
            case LONG -> new String[]{"java/lang/Integer", "java/lang/Long"};
            default -> new String[]{"java/lang/Double", "java/lang/Integer", "java/lang/Long"};
        };
        for (String bx : boxes) {
            Label next = c.label();
            VarType from = switch (bx) { case "java/lang/Integer" -> VarType.INT; case "java/lang/Long" -> VarType.LONG; default -> VarType.DOUBLE; };
            c.aload(v);
            c.instanceOf(bx);
            c.ifeq(next);
            c.load(dualPrim[slot]);
            c.aload(v);
            unboxExact(from);
            convertNum(from, k);
            switch (k) {
                case INT -> { switch (opName) { case "add" -> c.iadd(); case "sub" -> c.isub(); default -> c.imul(); } }
                case LONG -> { switch (opName) { case "add" -> c.ladd(); case "sub" -> c.lsub(); default -> c.lmul(); } }
                default -> { switch (opName) { case "add" -> c.dadd(); case "sub" -> c.dsub(); default -> c.dmul(); } }
            }
            storeDualPrim(slot);
            c.goTo(end);
            c.mark(next);
        }
        c.mark(generic);
        loadDual(slot, k);
        c.aload(v);
        c.invokestatic(OPS, opName, "(" + OBJ_D + OBJ_D + ")" + OBJ_D);
        storeDualObj(slot, k);
        c.mark(end);
        c.aconstNull();
        c.astore(v);   // do not keep the last box alive in a temp
        release(VarType.DYN, v);
        return true;
    }

    /** Does the expression (not descending into nested functions) write local slot `slot`? */
    private static boolean writesSlot(Object node, int slot, int depth) {
        if (node == null || depth > 256) return false;
        if (node instanceof me.padej.jumper.ast.FunctionNode) return false;
        if (node instanceof AssignLocal a && a.slot == slot) return true;
        if (node instanceof IncLocal il && il.slot == slot) return true;
        if (node instanceof CompoundAssign ca && ca.target instanceof Local l && l.slot == slot) return true;
        if (node instanceof Assign a && a.target instanceof Local l && l.slot == slot) return true;
        for (Object ch : Captures.children(node)) if (writesSlot(ch, slot, depth + 1)) return true;
        return false;
    }

    // ---- script array elements under a region guard, inline ----
    //
    // The first version called static helpers (JArray.rdInt/wrInt). In a function whose loops run
    // in OSR code, C2's later standard compile sees those call sites as "low call site frequency"
    // (the profile of the loop is mostly the OSR's) and does not inline them: sieve/dyn made a real
    // call per element - JFR showed wrBool/rdBool at 35 % of the samples. Emitted inline there is no
    // call to decide about: the bounds check against the logical size, then the plain array access.

    private static String arrField(VarType k) {
        return switch (k) { case INT -> "ints"; case DOUBLE -> "dbls"; default -> "bools"; };
    }

    private static String arrDesc(VarType k) {
        return switch (k) { case INT -> "[I"; case DOUBLE -> "[D"; default -> "[Z"; };
    }

    // Tried and removed (stage 14, jmp.inlinearr): script arrays of objects outside regions accessed
    // inline in bytecode (kind OBJ, index in [0, size) -> objs[i]) instead of Ops.indexInt/setIndexInt.
    // A/B on the author's machine: object_fields/typed +23 %, oop_construct/dyn +17 %, call_mega12/typed
    // 1.14x -> 1.77x; the gains (oop_construct/typed -5 %) did not pay for it. The calls C2 sometimes
    // leaves out-of-line are cheaper than the bigger method it gets instead.

    /** key in [0, size) or throw the JArray error; leaves nothing on the stack. */
    private void rawBounds(int arr, int key) {
        Label bad = c.label(), ok = c.label();
        c.iload(key);
        c.iflt(bad);
        c.iload(key);
        c.aload(arr);
        c.checkcast(JARRAY);
        c.getfield(JARRAY, "size", "I");
        c.if_icmplt(ok);
        c.mark(bad);
        c.aload(arr);
        c.checkcast(JARRAY);
        c.iload(key);
        c.invokevirtual(JARRAY, "outOfBounds", "(I)Lme/padej/jumper/runtime/JmpError;");
        c.athrow();
        c.mark(ok);
    }

    private VarType rawArrayRead(ARecv ar, Expr key) {
        int k = temp(VarType.INT);
        expr(key, VarType.INT);
        c.istore(k);
        rawBounds(ar.objLocal, k);
        c.aload(ar.objLocal);
        c.checkcast(JARRAY);
        c.getfield(JARRAY, arrField(ar.kind), arrDesc(ar.kind));
        c.iload(k);
        switch (ar.kind) { case INT -> c.iaload(); case DOUBLE -> c.daload(); default -> c.baload(); }
        release(VarType.INT, k);
        return ar.kind == VarType.INT ? VarType.INT : ar.kind == VarType.DOUBLE ? VarType.DOUBLE : VarType.BOOLEAN;
    }

    /** a[i] = v: in bounds a plain store; otherwise (an append, growth, an error) the full setter, which keeps the kind. */
    private void rawArrayWrite(ARecv ar, Expr key, Expr value) {
        int k = temp(VarType.INT);
        VarType vk = ar.kind == VarType.INT ? VarType.INT : ar.kind == VarType.DOUBLE ? VarType.DOUBLE : VarType.BOOLEAN;
        int v = temp(vk);
        expr(key, VarType.INT);
        c.istore(k);
        expr(value, ar.kind);
        c.store(v);
        Label slow = c.label(), end = c.label();
        c.iload(k);
        c.iflt(slow);
        c.iload(k);
        c.aload(ar.objLocal);
        c.checkcast(JARRAY);
        c.getfield(JARRAY, "size", "I");
        c.if_icmpge(slow);
        c.aload(ar.objLocal);
        c.checkcast(JARRAY);
        c.getfield(JARRAY, arrField(ar.kind), arrDesc(ar.kind));
        c.iload(k);
        c.load(v);
        switch (ar.kind) { case INT -> c.iastore(); case DOUBLE -> c.dastore(); default -> c.bastore(); }
        c.goTo(end);
        c.mark(slow);
        c.aload(ar.objLocal);
        c.checkcast(JARRAY);
        c.iload(k);
        c.load(v);
        switch (ar.kind) {
            case INT -> c.invokevirtual(JARRAY, "setInt", "(II)V");
            case DOUBLE -> c.invokevirtual(JARRAY, "setDouble", "(ID)V");
            default -> c.invokevirtual(JARRAY, "setBool", "(IZ)V");
        }
        c.mark(end);
        release(vk, v);
        release(VarType.INT, k);
    }

    /** Numeric cast between primitive kinds (nothing when equal). */
    private void convertNum(VarType from, VarType to) {
        if (from == to) return;
        switch (to) {
            case INT -> { if (from == VarType.LONG) c.l2i(); else c.d2i(); }
            case LONG -> { if (from == VarType.INT) c.i2l(); else c.d2l(); }
            default -> { if (from == VarType.INT) c.i2d(); else c.l2d(); }
        }
    }

    /** Static type of a node taking slot unboxing and the current table-shape speculation into account. */
    private VarType st(Expr e) {
        if (specDual != null && e instanceof Local l && specDual.contains(l.slot)) return unbox.dualKind(l.slot);
        if (e instanceof Index ix && specArr != null) {
            ARecv a = arrOf(ix);
            if (a != null) return a.kind;
        }
        if (e instanceof MethodCall mc) {
            VarType pm = pureMathType(mc);
            if (pm != null) return pm;
        }
        if (e instanceof BuiltinCall bc && bc.kind >= BuiltinCall.DYN_INT && st(bc.arg).isNumeric()) {
            return switch (bc.kind) { case BuiltinCall.DYN_INT -> VarType.INT; case BuiltinCall.DYN_LONG -> VarType.LONG; default -> VarType.DOUBLE; };
        }
        if (specRecv != null) {
            VarType sp = specType(e);
            if (sp != null) return sp;
            // Unbox computed the types once, before the speculation, and knows nothing about table fields.
            // So under the guard the expression types are recomputed from the operands: otherwise
            // `b.x * 1e-6` would stay DYN and the whole right-hand side would go through boxing -
            // i.e. the speculation would cover only the write, which is exactly half of the optimization.
            if (e instanceof Binary b) {
                VarType r = typedBinaryType(b);
                if (r != null) return r;
            }
            if (e instanceof Neg n) {
                VarType t = st(n.e);
                if (t.isNumeric()) return t;
            }
        }
        return unbox != null ? unbox.typeOf(e) : e.type;
    }

    // ------------------------------------------------------------------ table-shape speculation
    //
    // Inside the fast branch it is known: every receiver is a table whose fields used in this
    // expression all sit in double slots, and their indices are already computed and held as an array in a local.
    // One check covers the whole receiver: both the assigned field and all the
    // fields read. There may be several receivers - a guard for each, one shared slow branch.

    /** One receiver under a guard: its fields and the JVM locals holding the receiver itself and the index array. */
    private static final class Recv {
        final java.util.LinkedHashMap<String, Integer> keys = new java.util.LinkedHashMap<>();
        /** Field -> expected slot representation (INT or DOUBLE); absent = DOUBLE. */
        final java.util.HashMap<String, VarType> kinds = new java.util.HashMap<>();
        /** Kinds of the fields of the table literals this receiver's variable is assigned in this function (see litKinds), or null. */
        java.util.Map<String, VarType> lit;
        Expr obj;
        int objLocal = -1;
        /** Index in K of the region site this receiver is guarded by. */
        int siteIdx = -1;

        VarType kind(String name) {
            VarType k = known(name);
            return k == null ? VarType.DOUBLE : k;
        }

        /** The kind the code gives evidence for: the code around the access first, then the literal the variable holds. */
        VarType known(String name) {
            VarType k = kinds.get(name);
            return k == null && lit != null ? lit.get(name) : k;
        }
    }

    /** A receiver in a local slot, with the literal evidence for that slot. */
    private Recv newRecv(int slot) {
        Recv r = new Recv();
        r.lit = litKinds().get(slot);
        return r;
    }

    private java.util.Map<Integer, java.util.Map<String, VarType>> litKinds;

    /**
     * Slot -> field -> kind, from the table literals assigned to the slot in this function: `dyn p =
     * { a: i % 1000, b: i % 7 }` says p.a and p.b are int slots, because a literal types a slot by the
     * value put there and these values are statically int. Without it such fields were guessed double,
     * the guard missed on every table, and after eight misses the site died: object_alloc_churn/dyn ran
     * `sum += p.a + p.b` on the generic path for the whole run. A key two literals disagree about gives no evidence.
     */
    private java.util.Map<Integer, java.util.Map<String, VarType>> litKinds() {
        if (litKinds == null) {
            litKinds = new java.util.HashMap<>();
            java.util.Set<String> conflicts = new java.util.HashSet<>();
            litScan(fn.body, conflicts, 0);
        }
        return litKinds;
    }

    private void litScan(Object node, java.util.Set<String> conflicts, int depth) {
        if (node == null || node instanceof FunctionNode || depth > 256) return;
        int slot = -1;
        Expr v = null;
        if (node instanceof VarDecl d) { slot = d.slot; v = d.init; }
        else if (node instanceof AssignLocal a) { slot = a.slot; v = a.value; }
        else if (node instanceof Assign a && a.target instanceof Local l) { slot = l.slot; v = a.value; }
        if (slot >= 0 && v instanceof TableLit tl && tl.hasShape() && unbox != null) {
            java.util.Map<String, VarType> m = litKinds.computeIfAbsent(slot, k -> new java.util.HashMap<>());
            for (int i = 0; i < tl.keys.length; i++) {
                if (!(tl.keys[i] instanceof Literal kl) || !(kl.value instanceof String key)) continue;
                VarType t = unbox.typeOf(tl.values[i]);
                String id = slot + "." + key;
                if (conflicts.contains(id)) continue;
                if (t != VarType.INT && t != VarType.DOUBLE) { if (m.containsKey(key)) { m.remove(key); conflicts.add(id); } else conflicts.add(id); continue; }
                VarType old = m.putIfAbsent(key, t);
                if (old != null && old != t) { m.remove(key); conflicts.add(id); }
            }
        }
        for (Object ch : Captures.children(node)) litScan(ch, conflicts, depth + 1);
    }

    /** Slot of the receiver variable -> its Recv; null outside speculation. Speculation never nests. */
    private java.util.Map<Integer, Recv> specRecv;
    /** Dual slots whose primitive half is proven live under the current guard; null outside speculation. */
    private java.util.Set<Integer> specDual;

    /** A script array under a guard: its slot, expected element kind, and the local holding the receiver. */
    private static final class ARecv {
        Expr obj;
        VarType kind;
        int objLocal = -1;
    }

    /** Everything one guarded region needs: receivers with their fields, arrays with their kinds, and the dual slots read. */
    private static final class Spec {
        final java.util.LinkedHashMap<Integer, Recv> recv = new java.util.LinkedHashMap<>();
        final java.util.LinkedHashMap<Integer, ARecv> arrs = new java.util.LinkedHashMap<>();
        final java.util.LinkedHashSet<Integer> duals = new java.util.LinkedHashSet<>();
        int fields() {
            int n = 0;
            for (Recv r : recv.values()) n += r.keys.size();
            return n;
        }
    }

    /**
     * Scan an expression for a guarded region: null when the expression does not qualify or
     * there is nothing to guard. {@code minFields} - how many table fields make a guard worth it
     * when no dual slot is involved (a dual slot's guard is one flag test and always pays off).
     */
    private Spec specSetup(Expr e, int minFields) {
        if (!SPEC || unbox == null || specRecv != null || noSpec) return null;
        Spec sp = new Spec();
        if (!specScan(e, sp)) return null;
        if (sp.duals.isEmpty() && sp.arrs.isEmpty() && sp.fields() < minFields) return null;
        if (sp.duals.isEmpty() && sp.recv.isEmpty() && sp.arrs.isEmpty()) return null;
        specNumber(sp.recv);
        return sp;
    }

    /** Arrays under the current guard: slot -> ARecv; null outside speculation. */
    private java.util.Map<Integer, ARecv> specArr;

    private void specEnter(Spec sp) {
        specRecv = sp.recv;
        specDual = sp.duals;
        specArr = sp.arrs;
    }

    private void specLeave() {
        specRecv = null;
        specDual = null;
        specArr = null;
    }

    /** Guards of the region: receivers first (their field indices into locals), then arrays, then the dual flags. */
    private void specGuards(Spec sp, Label slow) {
        for (Recv r : sp.recv.values()) specGuard(r, slow);
        for (ARecv a : sp.arrs.values()) {
            a.objLocal = temp(VarType.DYN);
            expr(a.obj, VarType.DYN);
            c.astore(a.objLocal);
            c.aload(a.objLocal);
            c.instanceOf(JARRAY);
            c.ifeq(slow);
            c.aload(a.objLocal);
            c.checkcast(JARRAY);
            c.invokevirtual(JARRAY, "kind", "()I");
            c.iconst(arrayKindCode(a.kind));
            c.if_icmpne(slow);
        }
        for (int slot : sp.duals) {
            c.iload(dualFlag[slot]);
            c.ifeq(slow);
        }
    }

    /** JArray.kind() codes: INT = 1, DOUBLE = 2, BOOL = 3. */
    private static int arrayKindCode(VarType k) {
        return switch (k) { case INT -> 1; case DOUBLE -> 2; default -> 3; };
    }

    /** The array under a guard whose element this Index reads, or null. */
    private ARecv arrOf(Index ix) {
        if (specArr == null || !(ix.obj instanceof Local l)) return null;
        return specArr.get(l.slot);
    }

    private void specRelease(Spec sp) {
        for (Recv r : sp.recv.values()) release(VarType.DYN, r.objLocal);
        for (ARecv a : sp.arrs.values()) if (a.objLocal >= 0) release(VarType.DYN, a.objLocal);
    }
    /**
     * The slow branch of the speculation is generated by the same expr as everything else, and it would
     * call specExpr again on the same expression - and so on forever. This flag forbids that.
     */
    private boolean noSpec;

    /** Recv for a receiver expression, if it is under a guard. */
    private Recv recvOf(Expr obj) {
        if (specRecv == null || !(obj instanceof Local l)) return null;
        return specRecv.get(l.slot);
    }

    /** The field under the current speculation is held in a primitive slot of this kind - that is what the guard proved. */
    private VarType specType(Expr e) {
        if (!(e instanceof Member m)) return null;
        Recv r = recvOf(m.obj);
        return r != null && r.keys.containsKey(m.name) ? r.kind(m.name) : null;
    }

    /**
     * Field read under the guard: an invokedynamic whose target is linked with the slot index as a
     * constant once the region's shape is known ({@link RegionSite}); no cache and no boxing.
     */
    private void specGet(Member m) {
        Recv r = recvOf(m.obj);
        VarType k = r.kind(m.name);
        c.aload(r.objLocal);
        c.invokedynamic(cb.bootstrap(Jit.PKG + "RegionSite", "bootstrapGet", REGION_ACCESS_BSM,
                cb.cp.integer(r.siteIdx), cb.cp.integer(r.keys.get(m.name))), "get", "(" + OBJ_D + ")" + desc(k));
    }

    /** Field write under the guard: the value (of the field's kind) is produced by {@code value}; same linking as the read. */
    private void specSet(Member m, Runnable value) {
        Recv r = recvOf(m.obj);
        VarType k = r.kind(m.name);
        c.aload(r.objLocal);
        value.run();
        c.invokedynamic(cb.bootstrap(Jit.PKG + "RegionSite", "bootstrapSet", REGION_ACCESS_BSM,
                cb.cp.integer(r.siteIdx), cb.cp.integer(r.keys.get(m.name))), "set", "(" + OBJ_D + desc(k) + ")V");
    }

    // ------------------------------------------------------------------ java.lang.Math as typed nodes
    //
    // `Math.sqrt(x)`, `Math.abs(x)`, `Math.min(a, b)`... with statically numeric arguments are
    // compiled to a direct invokestatic of the right overload: no receiver, no site, no box for the
    // result - and, being side-effect free, such a call is a pure node of a guarded region, so
    // `energy += Math.sqrt(e.vx * e.vx + e.vy * e.vy)` no longer breaks the region in two.
    //
    // The access policy is consulted once, at compile time, through the interpreter's loader:
    // the policy is fixed before the first script is loaded (Interpreter.access refuses to change
    // it afterwards), so the decision cannot go stale.

    /** One double in, one double out. */
    private static final java.util.Set<String> MATH_D_D = java.util.Set.of(
            "sqrt", "cbrt", "sin", "cos", "tan", "asin", "acos", "atan", "sinh", "cosh", "tanh",
            "exp", "expm1", "log", "log10", "log1p", "floor", "ceil", "rint", "signum", "toRadians", "toDegrees");
    /** Two doubles in, one double out. */
    private static final java.util.Set<String> MATH_DD_D = java.util.Set.of("pow", "hypot", "atan2", "IEEEremainder");
    /** Overloaded on int / long / double with the same shape of signature. */
    private static final java.util.Set<String> MATH_ANY = java.util.Set.of("abs", "min", "max");

    /** Result type of a Math call the compiler can emit directly, or null when it is not such a call. */
    private VarType pureMathType(MethodCall mc) {
        if (!(mc.obj instanceof Literal l) || !(l.value instanceof JavaClass jc) || jc.cls() != Math.class) return null;
        if (!mathAllowed(mc.name)) return null;
        VarType[] at = new VarType[mc.args.length];
        for (int i = 0; i < at.length; i++) {
            at[i] = st(mc.args[i]);
            if (!at[i].isNumeric()) return null;
        }
        if (at.length == 1 && MATH_D_D.contains(mc.name)) return VarType.DOUBLE;
        if (at.length == 2 && MATH_DD_D.contains(mc.name)) return VarType.DOUBLE;
        if (at.length == 1 && mc.name.equals("round")) return VarType.LONG;   // round(double) -> long, as in Java
        if (MATH_ANY.contains(mc.name) && (at.length == 1 && mc.name.equals("abs") || at.length == 2 && !mc.name.equals("abs"))) {
            VarType t = at[0];
            for (VarType x : at) t = VarType.arith(t, x);
            return t;
        }
        return null;
    }

    private VarType pureMathCall(MethodCall mc, VarType t) {
        VarType arg = mc.name.equals("round") ? VarType.DOUBLE : MATH_ANY.contains(mc.name) ? t : VarType.DOUBLE;
        StringBuilder d = new StringBuilder("(");
        for (Expr a : mc.args) { expr(a, arg); d.append(desc(arg)); }
        d.append(')').append(desc(t));
        c.invokestatic("java/lang/Math", mc.name, d.toString());
        return t;
    }

    /** The interpreter's policy allows this Math method (null policy - everything is allowed). */
    private boolean mathAllowed(String name) {
        me.padej.jumper.runtime.Access a = fn.loader == null ? null : fn.loader.access;
        return a == null || a.memberAllowed(Math.class, Math.class, name);
    }

    private static final String REGION_ACCESS_BSM =
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;II)Ljava/lang/invoke/CallSite;";
    private static final String REGION_GUARD_BSM =
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;I)Ljava/lang/invoke/CallSite;";

    /**
     * Collect the receivers of the expression and make sure it is "quiet": arithmetic over locals,
     * literals and table fields. A call inside could change the shape between the guard and the read.
     *
     * @return false - the expression does not qualify
     */
    private boolean specScan(Expr e, Spec sp) {
        java.util.Map<Integer, Recv> out = sp.recv;
        if (e == null) return false;
        if (e instanceof Local l) {
            if (dual(l.slot, 0) != null) sp.duals.add(l.slot);
            return true;
        }
        if (e instanceof Upvalue) return true;
        if (e instanceof IntLocal || e instanceof LongLocal
                || e instanceof DoubleLocal || e instanceof BoolLocal) return true;
        // Only numeric and boolean literals: a region is arithmetic in primitives. `n.left == null` used to
        // qualify, the field got the default guess "double", the guard missed on every table and the site
        // died - binary_trees/dyn then read `n.left` through the monomorphic FieldCache on every call,
        // thrashing between the node and leaf shapes.
        if (e instanceof Literal lit) return lit.value instanceof Number || lit.value instanceof Boolean;
        if (e instanceof IntLit || e instanceof LongLit
                || e instanceof DoubleLit || e instanceof BoolLit) return true;
        if (e instanceof Member m) {
            if (!(m.obj instanceof Local l)) return false;
            out.computeIfAbsent(l.slot, k -> newRecv(k)).obj = l;
            out.get(l.slot).keys.putIfAbsent(m.name, 0);
            return true;
        }
        if (e instanceof Index ix) {
            // a script array with a guessed element kind and an int index: a typed element access under the guard
            if (!(ix.obj instanceof Local l) || unbox.arrayKind(l.slot) == null || st(ix.key) != VarType.INT) return false;
            if (!specScan(ix.key, sp)) return false;
            ARecv a = sp.arrs.computeIfAbsent(l.slot, k -> new ARecv());
            a.obj = l;
            a.kind = unbox.arrayKind(l.slot);
            return true;
        }
        if (e instanceof Binary b) return b.type != VarType.STRING && specScan(b.left, sp) && specScan(b.right, sp);
        if (e instanceof Neg n) return specScan(n.e, sp);
        // the parser's typed nodes (a double literal in the expression makes it a DoubleBin): pure too
        if (e instanceof IntBin b) return specScan(b.l, sp) && specScan(b.r, sp);
        if (e instanceof LongBin b) return specScan(b.l, sp) && specScan(b.r, sp);
        if (e instanceof DoubleBin b) return specScan(b.l, sp) && specScan(b.r, sp);
        if (e instanceof IntCmp b) return specScan(b.l, sp) && specScan(b.r, sp);
        if (e instanceof LongCmp b) return specScan(b.l, sp) && specScan(b.r, sp);
        if (e instanceof DoubleCmp b) return specScan(b.l, sp) && specScan(b.r, sp);
        if (e instanceof IntNeg n) return specScan(n.e, sp);
        if (e instanceof LongNeg n) return specScan(n.e, sp);
        if (e instanceof DoubleNeg n) return specScan(n.e, sp);
        if (e instanceof BuiltinCall bc && bc.kind >= BuiltinCall.TO_INT) return specScan(bc.arg, sp);   // int(x) etc.: a cast
        if (e instanceof MethodCall mc && mc.obj instanceof Literal l && l.value instanceof JavaClass jc && jc.cls() == Math.class
                && mathAllowed(mc.name) && (MATH_D_D.contains(mc.name) || MATH_DD_D.contains(mc.name) || MATH_ANY.contains(mc.name) || mc.name.equals("round"))) {
            // a Math call over quiet arguments: no side effects, resolved statically once the argument types are known
            for (Expr a : mc.args) if (!specScan(a, sp)) return false;
            return true;
        }
        return false;
    }

    /** Number the fields of every receiver: the number is the position in the site's index array. */
    private static void specNumber(java.util.Map<Integer, Recv> recv) {
        for (Recv r : recv.values()) {
            int i = 0;
            for (java.util.Map.Entry<String, Integer> en : r.keys.entrySet()) en.setValue(i++);
        }
    }

    /**
     * Guard for one receiver: the region site's guard call, on a miss - jump to slow. The site is a
     * constant in K; the accesses inside the region find it there by index and link to the same shape.
     */
    private void specGuard(Recv r, Label slow) {
        r.objLocal = temp(VarType.DYN);
        expr(r.obj, VarType.DYN);
        c.astore(r.objLocal);
        String[] names = r.keys.keySet().toArray(new String[0]);
        VarType[] kinds = new VarType[names.length];
        for (int i = 0; i < names.length; i++) kinds[i] = r.kind(names[i]);
        r.siteIdx = konstIndex(new RegionSite(names, kinds));
        c.aload(r.objLocal);
        c.invokedynamic(cb.bootstrap(Jit.PKG + "RegionSite", "bootstrapGuard", REGION_GUARD_BSM, cb.cp.integer(r.siteIdx)),
                "guard", "(" + OBJ_D + ")Z");
        c.ifeq(slow);
    }

    /**
     * The expression is pure arithmetic over table fields: a guard for every receiver, inside everything
     * in double without boxing, otherwise the old generic path.
     *
     * <p>Both branches leave a box on the stack, so this is an ordinary expression and fits everywhere
     * memberGet used to be: both in `dyn dx = bi.x - bj.x` and in the right-hand side of `energy += ...`.
     * It was precisely reads with <b>two different</b> receivers in one expression that made nbody worse:
     * the speculation took the statement `bi.vx -= ...`, but not the three reads before it.
     *
     * <p>Fewer than two field accesses are not taken: the guard would then cost more than it saves.
     */
    private boolean specExpr(Expr e, VarType want) {
        if (want != VarType.DYN && want != VarType.BOOLEAN) return false;
        boolean typedRoot = e instanceof DoubleBin || e instanceof IntBin || e instanceof LongBin
                || e instanceof DoubleCmp || e instanceof IntCmp || e instanceof LongCmp
                || e instanceof DoubleNeg || e instanceof IntNeg || e instanceof LongNeg;
        if (!(e instanceof Binary) && !(e instanceof Neg) && !typedRoot) return false;
        if (want == VarType.BOOLEAN && e.type != VarType.BOOLEAN) return false;
        Spec sp = specSetup(e, 1);
        if (sp == null) return false;

        // the decision is made before a single byte is emitted: after the guards there is no way back
        specEnter(sp);
        VarType a = st(e);
        specLeave();
        boolean cmp = e.type == VarType.BOOLEAN;
        if (cmp ? a != VarType.BOOLEAN : !a.isNumeric()) return false;

        Label slow = c.label(), end = c.label();
        specEnter(sp);
        specGuards(sp, slow);
        if (cmp) {
            expr(e, VarType.BOOLEAN);
            if (want == VarType.DYN) box(c, VarType.BOOLEAN);
        } else {
            expr(e, a);
            box(c, a);
        }
        specLeave();
        c.goTo(end);

        c.mark(slow);
        noSpec = true;
        expr(e, want);
        noSpec = false;
        c.mark(end);
        specRelease(sp);
        return true;
    }

    private void loadSlot(int slot, int depth) {
        int local = depth == 0 ? slotLocal[slot] : -1;
        VarType u = unboxed(slot, depth);
        if (u != null) { c.load(local); box(c, u); return; }
        VarType dk = dual(slot, depth);
        if (dk != null) { loadDual(slot, dk); return; }
        if (local >= 0) c.aload(local);
        else {
            frameRef(depth);
            c.getfield(FRAME, "slots", "[" + OBJ_D);
            c.iconst(slot);
            c.aaload();
        }
    }

    private void storePrim(int idx, int depth, VarType t, Runnable value) {
        int local = depth == 0 ? primLocal[idx] : -1;
        if (local >= 0) {
            value.run();
            c.store(local);
        } else {
            frameRef(depth);
            c.getfield(FRAME, "p", "[J");
            c.iconst(idx);
            value.run();
            toBits(t);
            c.lastore();
        }
    }

    private void loadPrim(int idx, int depth, VarType t) {
        int local = depth == 0 ? primLocal[idx] : -1;
        if (local >= 0) c.load(local);
        else {
            frameRef(depth);
            c.getfield(FRAME, "p", "[J");
            c.iconst(idx);
            c.laload();
            fromBits(t);
        }
    }

    // ================= guarded regions =================
    //
    // A run of consecutive statements that are all pure arithmetic over table fields and dual
    // locals is guarded once, not once per statement: the shapes of the receivers cannot change
    // inside it (no calls, no generic writes), so one idxFor per receiver at the entry proves every
    // field access in the run. Inside, field reads and writes are array accesses in the slot's
    // kind, dual locals are their primitive halves, comparisons are typed. The whole run is
    // generated a second time generically for the slow branch.

    /** Statements of a region collected so far: what it reads and writes, to decide what needs a guard. */
    private static final class Region {
        final Spec sp = new Spec();
        /** Dual slots read before any write in the region: their flags are guarded at the entry. */
        final java.util.LinkedHashSet<Integer> guarded = new java.util.LinkedHashSet<>();
        /** Dual slots written earlier on the current straight-line path: their primitive half is live. */
        java.util.HashSet<Integer> written = new java.util.HashSet<>();
        int fields;
    }

    /**
     * Try to start a region at {@code from}; returns how many statements it took (0 = none).
     * The decision is made statement by statement: a statement that does not fit ends the region.
     */
    private int region(Stmt[] stmts, int from) {
        if (!SPEC || unbox == null || specRecv != null || noSpec) return 0;
        Region rg = new Region();
        int n = 0;
        for (int i = from; i < stmts.length; i++) {
            if (!regionAdmits(stmts[i], rg)) break;
            n++;
        }
        if (n == 0) return 0;
        if (rg.sp.recv.isEmpty() && rg.sp.arrs.isEmpty() && rg.guarded.isEmpty() && rg.sp.duals.isEmpty()) return 0;
        if (rg.fields == 0 && rg.sp.duals.isEmpty()) return 0;
        specNumber(rg.sp.recv);
        rg.sp.duals.clear();
        rg.sp.duals.addAll(rg.guarded);

        Label slow = c.label(), end = c.label();
        specEnter(rg.sp);
        specDual = new java.util.HashSet<>(rg.guarded);
        specGuards(rg.sp, slow);
        for (int i = from; i < from + n; i++) regionStmt(stmts[i]);
        specLeave();
        c.goTo(end);

        c.mark(slow);
        noSpec = true;
        for (int i = from; i < from + n; i++) stmt(stmts[i]);
        noSpec = false;
        c.mark(end);
        specRelease(rg.sp);
        return n;
    }

    /**
     * Whether the statement fits the region; on success its receivers, fields, dual reads and
     * writes are added. Type checks run under the tentative spec (fields collected so far plus
     * this statement's), so a statement whose result would not fit the target's kind is refused.
     */
    private boolean regionAdmits(Stmt s, Region rg) {
        boolean r = regionAdmits0(s, rg);
        if (Jit.DEBUG && !r) System.err.println("[region] refused " + s.getClass().getSimpleName() + " line " + s.line);
        return r;
    }

    private boolean regionAdmits0(Stmt s, Region rg) {
        if (s instanceof Block b) {
            for (Stmt x : b.stmts) if (!regionAdmits(x, rg)) return false;
            return true;
        }
        if (s instanceof ExprStmt es) return regionAdmitsExpr(es.e, rg);
        if (s instanceof VarDecl vd) {
            if (vd.type != VarType.DYN || vd.init == null) return false;
            VarType k = dual(vd.slot, 0);
            if (k != null) return regionWrite(vd.slot, k, vd.init, rg);
            VarType u = unboxed(vd.slot, 0);
            return u != null && regionStaticWrite(u, vd.init, rg);
        }
        if (s instanceof PrimVarDecl pd) return pd.init != null && regionStaticWrite(pd.t, pd.init, rg);
        if (s instanceof While w) {
            // the whole loop under one guard: nothing inside can change a shape or an array kind
            if (!regionCond(w.cond, rg)) return false;
            java.util.HashSet<Integer> before = rg.written;
            rg.written = new java.util.HashSet<>(before);
            boolean ok = regionAdmits(w.body, rg);
            rg.written = before;   // the body may run zero times
            return ok;
        }
        if (s instanceof For f) {
            if (f.init != null && !regionAdmits(f.init, rg)) return false;
            if (f.cond != null && !regionCond(f.cond, rg)) return false;
            java.util.HashSet<Integer> before = rg.written;
            rg.written = new java.util.HashSet<>(before);
            boolean ok = regionAdmits(f.body, rg);
            if (ok && f.update != null) ok = regionAdmitsExpr(f.update, rg);
            rg.written = before;
            return ok;
        }
        if (s instanceof If i) {
            if (!regionCond(i.cond, rg)) return false;
            java.util.HashSet<Integer> before = rg.written;
            rg.written = new java.util.HashSet<>(before);
            boolean ok = regionAdmits(i.then, rg);
            rg.written = new java.util.HashSet<>(before);
            if (ok && i.otherwise != null) ok = regionAdmits(i.otherwise, rg);
            rg.written = before;   // a branch write proves nothing after the if
            return ok;
        }
        return false;
    }

    private boolean regionAdmitsExpr(Expr e, Region rg) {
        if (e instanceof CompoundAssign ca) {
            if (ca.target instanceof Member m && m.obj instanceof Local) {
                Binary syn = new Binary(ca.op, m, ca.value, ca.line);
                return regionFieldWrite(m, syn, rg, null);
            }
            if (ca.target instanceof Local tl) {
                VarType k = dual(tl.slot, 0);
                if (k != null) return regionWrite(tl.slot, k, new Binary(ca.op, tl, ca.value, ca.line), rg);
                VarType u = unboxed(tl.slot, 0);
                return u != null && regionStaticWrite(u, new Binary(ca.op, tl, ca.value, ca.line), rg);
            }
            if (ca.target instanceof IntLocal || ca.target instanceof LongLocal || ca.target instanceof DoubleLocal) {
                return regionStaticWrite(primOf(ca.target), new Binary(ca.op, ca.target, ca.value, ca.line), rg);
            }
            return false;
        }
        if (e instanceof Assign a) {
            if (a.target instanceof Member m && m.obj instanceof Local) return regionFieldWrite(m, a.value, rg, null);
            if (a.target instanceof Index ix && ix.obj instanceof Local l && unbox.arrayKind(l.slot) != null) {
                Spec probe = new Spec();
                if (!specScan(ix, probe) || !specScan(a.value, probe)) return false;
                if (!regionMerge(probe, rg)) return false;
                regionInferKinds(a.value, rg);
                VarType t = regionTypeOf(a.value, rg);
                return t == unbox.arrayKind(l.slot);   // the element must be stored as is: the array's kind must not change
            }
            if (a.target instanceof Local tl) {
                VarType k = dual(tl.slot, 0);
                return k != null && regionWrite(tl.slot, k, a.value, rg);
            }
            return false;
        }
        if (e instanceof AssignLocal a) {
            VarType k = a.type == VarType.DYN ? dual(a.slot, 0) : null;
            if (k != null) return regionWrite(a.slot, k, a.value, rg);
            VarType u = a.type == VarType.DYN ? unboxed(a.slot, 0) : null;   // a statically unboxed local: typed anyway
            return u != null && regionStaticWrite(u, a.value, rg);
        }
        if (e instanceof IncLocal il) {
            VarType k = il.type == VarType.DYN ? dual(il.slot, 0) : null;
            if (k != null) return regionWrite(il.slot, k,
                    new Binary(TokenType.PLUS, new Local(il.slot, il.line), new IntLit(il.delta, il.line), il.line), rg);
            return il.type == VarType.DYN && unboxed(il.slot, 0) == VarType.INT;   // iinc, nothing to guard
        }
        if (e instanceof IncGeneric ig && ig.target instanceof Member m && m.obj instanceof Local) {
            return regionFieldWrite(m, new Binary(TokenType.PLUS, m, new IntLit(ig.delta, m.line), m.line), rg, VarType.INT);
        }
        return false;
    }

    /** A condition: comparisons over pure operands, possibly joined by && / || / !. */
    private boolean regionCond(Expr e, Region rg) {
        if (e instanceof Logical lg) return regionCond(lg.left, rg) && regionCond(lg.right, rg);
        if (e instanceof Not n) return regionCond(n.e, rg);
        if (e instanceof BoolLit) return true;
        if (e instanceof Index ix && ix.obj instanceof Local l && unbox.arrayKind(l.slot) == VarType.BOOLEAN) {
            Spec probe = new Spec();   // `if (!flags[i])` on a boolean array
            if (!specScan(ix, probe)) return false;
            return regionMerge(probe, rg);
        }
        if (e.type != VarType.BOOLEAN) return false;
        if (!(e instanceof Binary) && !(e instanceof IntCmp) && !(e instanceof LongCmp) && !(e instanceof DoubleCmp)) return false;
        Spec probe = new Spec();
        if (!specScan(e, probe)) return false;
        if (!regionMerge(probe, rg)) return false;
        regionInferKinds(e, rg);
        VarType t = regionTypeOf(e, rg);
        return t == VarType.BOOLEAN;
    }

    /** `t.f op= value` / `t.f = value` / `t.f++`: the value in the field's kind, stored as bits. */
    private boolean regionFieldWrite(Member m, Expr value, Region rg, VarType hint) {
        Spec probe = new Spec();
        Local l = (Local) m.obj;
        Recv r = probe.recv.computeIfAbsent(l.slot, k -> newRecv(k));
        r.obj = l;
        r.keys.putIfAbsent(m.name, 0);
        if (!specScan(value, probe)) return false;
        if (!regionMerge(probe, rg)) return false;
        if (hint != null) regionFieldEvidence(m, hint, rg);
        regionInferKinds(value, rg);
        Recv tr = rg.sp.recv.get(l.slot);
        // a field assigned from a field of known kind takes that kind, and the other way round
        if (value instanceof Member vm && vm.obj instanceof Local) {
            VarType vk = regionHint(vm, rg), tk = tr.kinds.get(m.name);
            if (vk == null && tk != null) regionFieldEvidence(vm, tk, rg);
            else if (vk != null && tk == null) regionFieldEvidence(m, vk, rg);
        }
        // the value must land in the slot as is: a double into an int slot would change the representation
        VarType want = tr.kind(m.name);
        VarType t = regionTypeOf(value, rg);
        if (t != want) {
            // a still-unknown field takes the kind of what is written into it
            if (!tr.kinds.containsKey(m.name) && (t == VarType.INT || t == VarType.DOUBLE)) {
                tr.kinds.put(m.name, t);
                return regionTypeOf(value, rg) == t;
            }
            return false;
        }
        return true;
    }

    /** Write of a statically unboxed local inside a region: the value must be pure and of that type. */
    private boolean regionStaticWrite(VarType u, Expr value, Region rg) {
        Spec probe = new Spec();
        if (!specScan(value, probe)) return false;
        if (!regionMerge(probe, rg)) return false;
        regionInferKinds(value, rg);
        return regionTypeOf(value, rg) == u;
    }

    /** Write of a dual slot: the value must be of the slot's kind under the region's types. */
    private boolean regionWrite(int slot, VarType k, Expr value, Region rg) {
        Spec probe = new Spec();
        if (!specScan(value, probe)) return false;
        if (probe.recv.containsKey(slot) || rg.sp.recv.containsKey(slot)) return false;   // a receiver is not a dual
        if (!regionMerge(probe, rg)) return false;
        if (value instanceof Member vm && vm.obj instanceof Local && regionHint(vm, rg) == null) regionFieldEvidence(vm, k, rg);
        regionInferKinds(value, rg);
        VarType t = regionTypeOf(value, rg);
        if (t != k) return false;
        rg.written.add(slot);
        return true;
    }

    /** Merge the receivers and dual reads of one expression into the region; a dual read before its write is guarded. */
    private boolean regionMerge(Spec probe, Region rg) {
        for (int k : probe.recv.keySet()) if (rg.sp.duals.contains(k) || rg.written.contains(k)) return false;
        for (int d : probe.duals) if (rg.sp.recv.containsKey(d)) return false;
        for (java.util.Map.Entry<Integer, Recv> en : probe.recv.entrySet()) {
            Recv r = rg.sp.recv.computeIfAbsent(en.getKey(), k -> newRecv(k));
            r.obj = en.getValue().obj;
            for (String f : en.getValue().keys.keySet()) if (r.keys.putIfAbsent(f, 0) == null) rg.fields++;
        }
        for (java.util.Map.Entry<Integer, ARecv> en : probe.arrs.entrySet()) {
            if (rg.sp.duals.contains(en.getKey()) || rg.written.contains(en.getKey()) || rg.sp.recv.containsKey(en.getKey())) return false;
            ARecv a = rg.sp.arrs.computeIfAbsent(en.getKey(), k -> new ARecv());
            a.obj = en.getValue().obj;
            a.kind = en.getValue().kind;
            rg.fields++;
        }
        for (int d : probe.duals) {
            rg.sp.duals.add(d);
            if (!rg.written.contains(d)) rg.guarded.add(d);
        }
        return true;
    }

    /** Type of an expression under the region's tentative spec. */
    private VarType regionTypeOf(Expr e, Region rg) {
        java.util.Map<Integer, Recv> pr = specRecv;
        java.util.Set<Integer> pd = specDual;
        java.util.Map<Integer, ARecv> pa = specArr;
        specRecv = rg.sp.recv;
        specDual = rg.sp.duals;
        specArr = rg.sp.arrs;
        try {
            return st(e);
        } finally {
            specRecv = pr;
            specDual = pd;
            specArr = pa;
        }
    }

    // ---- guessing the kind of a field from the code around it ----
    //
    // The compiler has no profile, so the representation of a table slot is a guess checked by the
    // guard: a wrong guess costs the slow path, never correctness. Evidence: an int literal or an
    // int local next to the field, ++/--, the kind of what is assigned to it. No evidence - double.

    private void regionFieldEvidence(Member m, VarType k, Region rg) {
        if (!(m.obj instanceof Local l)) return;
        Recv r = rg.sp.recv.get(l.slot);
        if (r != null && r.keys.containsKey(m.name)) r.kinds.putIfAbsent(m.name, k);
    }

    /** The kind an operand suggests for its neighbour, or null. */
    private VarType regionHint(Expr e, Region rg) {
        if (e instanceof IntLit || e instanceof IntLocal || e instanceof IntBin || e instanceof IntNeg) return VarType.INT;
        if (e instanceof DoubleLit || e instanceof DoubleLocal || e instanceof DoubleBin || e instanceof DoubleNeg) return VarType.DOUBLE;
        if (e instanceof Local l) {
            VarType u = unboxed(l.slot, 0);
            if (u == VarType.INT || u == VarType.DOUBLE) return u;
            VarType d = dual(l.slot, 0);
            if (d == VarType.INT || d == VarType.DOUBLE) return d;
            return null;
        }
        if (e instanceof Member m && m.obj instanceof Local l) {
            Recv r = rg.sp.recv.get(l.slot);
            return r == null ? null : r.known(m.name);
        }
        if (e instanceof Index ix && ix.obj instanceof Local l) {
            VarType k = unbox.arrayKind(l.slot);
            return k == VarType.INT || k == VarType.DOUBLE ? k : null;
        }
        if (e instanceof Neg n) return regionHint(n.e, rg);
        if (e instanceof MethodCall mc && mc.obj instanceof Literal l && l.value instanceof JavaClass jc && jc.cls() == Math.class) {
            if (MATH_D_D.contains(mc.name) || MATH_DD_D.contains(mc.name)) return VarType.DOUBLE;   // sqrt & co: double in, double out
            return null;
        }
        if (e instanceof Binary b) {
            VarType a = regionHint(b.left, rg), c2 = regionHint(b.right, rg);
            if (a == null) return c2;
            if (c2 == null) return a;
            return VarType.arith(a, c2);
        }
        return null;
    }

    private void regionInferKinds(Expr e, Region rg) {
        if (e instanceof Binary b) {
            regionInferKinds(b.left, rg);
            regionInferKinds(b.right, rg);
            if (b.type == VarType.STRING) return;
            VarType hl = regionHint(b.left, rg), hr = regionHint(b.right, rg);
            // a side without evidence takes the kind of the other side, down to its unknown fields:
            // `r.a + r.b + c % 97` - the int on the right says the fields on the left are ints
            if (hl == null && hr != null) regionPushKind(b.left, hr, rg);
            if (hr == null && hl != null) regionPushKind(b.right, hl, rg);
        } else if (e instanceof Neg n) regionInferKinds(n.e, rg);
        else if (e instanceof MethodCall mc) {
            // Math.sqrt(e.vx * e.vx): the fields inside are doubles; abs/min/max leave them to their own evidence
            boolean dbl = MATH_D_D.contains(mc.name) || MATH_DD_D.contains(mc.name) || mc.name.equals("round");
            for (Expr a : mc.args) { regionInferKinds(a, rg); if (dbl) regionPushKind(a, VarType.DOUBLE, rg); }
        }
        else if (e instanceof Logical lg) { regionInferKinds(lg.left, rg); regionInferKinds(lg.right, rg); }
        else if (e instanceof Not n) regionInferKinds(n.e, rg);
    }

    /** Give every field without evidence inside e the kind k (int or double only). */
    private void regionPushKind(Expr e, VarType k, Region rg) {
        if (k != VarType.INT && k != VarType.DOUBLE) return;
        if (e instanceof Member m) { if (regionHint(m, rg) == null) regionFieldEvidence(m, k, rg); }
        else if (e instanceof Binary b) { regionPushKind(b.left, k, rg); regionPushKind(b.right, k, rg); }
        else if (e instanceof Neg n) regionPushKind(n.e, k, rg);
        else if (e instanceof MethodCall mc && MATH_ANY.contains(mc.name)) for (Expr a : mc.args) regionPushKind(a, k, rg);
    }

    // ---- generation inside the region ----

    private void regionStmt(Stmt s) {
        c.line(s.line);
        if (s instanceof Block b) {
            for (Stmt x : b.stmts) regionStmt(x);
        } else if (s instanceof ExprStmt es) {
            regionExpr(es.e);
        } else if (s instanceof VarDecl vd) {
            VarType k = dual(vd.slot, 0);
            if (k == null) { stmt(vd); return; }   // statically unboxed: the ordinary typed store
            dualAssign(vd.slot, k, vd.init, true);
            specDual.add(vd.slot);
        } else if (s instanceof PrimVarDecl) {
            stmt(s);
        } else if (s instanceof While w) {
            Label lStart = c.label(), lExit = c.label();
            c.mark(lStart);
            cancelCheck(w.cancel);
            jump(w.cond, lExit, false);
            loops.push(new Label[]{lStart, lExit});
            java.util.Set<Integer> before = new java.util.HashSet<>(specDual);
            regionStmt(w.body);
            specDual = new java.util.HashSet<>(before);
            loops.pop();
            c.goTo(lStart);
            c.mark(lExit);
        } else if (s instanceof For f) {
            if (f.init != null) regionStmt(f.init);
            Label lStart = c.label(), lCont = c.label(), lExit = c.label();
            c.mark(lStart);
            cancelCheck(f.cancel);
            if (f.cond != null) jump(f.cond, lExit, false);
            loops.push(new Label[]{lCont, lExit});
            java.util.Set<Integer> before = new java.util.HashSet<>(specDual);
            regionStmt(f.body);
            loops.pop();
            c.mark(lCont);
            if (f.update != null) regionExpr(f.update);
            specDual = new java.util.HashSet<>(before);
            c.goTo(lStart);
            c.mark(lExit);
        } else if (s instanceof If i) {
            Label lElse = c.label(), lEnd = c.label();
            jump(i.cond, lElse, false);
            java.util.Set<Integer> before = new java.util.HashSet<>(specDual);
            regionStmt(i.then);
            specDual = new java.util.HashSet<>(before);
            if (i.otherwise != null) {
                c.goTo(lEnd);
                c.mark(lElse);
                regionStmt(i.otherwise);
                specDual = new java.util.HashSet<>(before);
                c.mark(lEnd);
            } else {
                c.mark(lElse);
            }
        } else throw new IllegalStateException("region: " + s.getClass().getSimpleName());
    }

    private void regionExpr(Expr e) {
        if (e instanceof CompoundAssign ca) {
            if (ca.target instanceof Member m) {
                Binary syn = new Binary(ca.op, m, ca.value, ca.line);
                specSet(m, () -> expr(syn, specType(m)));
            } else if (ca.target instanceof Local tl && dual(tl.slot, 0) != null) {
                VarType k = dual(tl.slot, 0);
                dualAssign(tl.slot, k, new Binary(ca.op, tl, ca.value, ca.line), true);
                specDual.add(tl.slot);
            } else {
                compoundAssign(ca, true);   // a statically unboxed local: the typed path, under the guard
            }
        } else if (e instanceof Assign a) {
            if (a.target instanceof Member m) {
                specSet(m, () -> expr(a.value, specType(m)));
            } else if (a.target instanceof Index ix) {
                ARecv ar = arrOf(ix);
                if (RAW_ARRAY) { rawArrayWrite(ar, ix.key, a.value); return; }
                c.aload(ar.objLocal);
                c.checkcast(JARRAY);
                expr(ix.key, VarType.INT);
                expr(a.value, ar.kind);
                switch (ar.kind) {
                    case INT -> c.invokevirtual(JARRAY, "setInt", "(II)V");
                    case DOUBLE -> c.invokevirtual(JARRAY, "setDouble", "(ID)V");
                    default -> c.invokevirtual(JARRAY, "setBool", "(IZ)V");
                }
            } else {
                Local tl = (Local) a.target;
                VarType k = dual(tl.slot, 0);
                dualAssign(tl.slot, k, a.value, true);
                specDual.add(tl.slot);
            }
        } else if (e instanceof AssignLocal a) {
            if (dual(a.slot, 0) == null) { assignLike(e, true); return; }   // statically unboxed
            dualAssign(a.slot, dual(a.slot, 0), a.value, true);
            specDual.add(a.slot);
        } else if (e instanceof IncLocal il) {
            if (dual(il.slot, 0) == null) { assignLike(e, true); return; }
            dualAssign(il.slot, dual(il.slot, 0),
                    new Binary(TokenType.PLUS, new Local(il.slot, il.line), new IntLit(il.delta, il.line), il.line), true);
            specDual.add(il.slot);
        } else if (e instanceof IncGeneric ig) {
            Member m = (Member) ig.target;
            specSet(m, () -> expr(new Binary(TokenType.PLUS, m, new IntLit(ig.delta, m.line), m.line), specType(m)));
        } else throw new IllegalStateException("region expr: " + e.getClass().getSimpleName());
    }

    // ================= expressions =================

    private static VarType primOf(Expr e) {
        if (e instanceof IntLocal) return VarType.INT;
        if (e instanceof LongLocal) return VarType.LONG;
        if (e instanceof DoubleLocal) return VarType.DOUBLE;
        if (e instanceof BoolLocal) return VarType.BOOLEAN;
        return null;
    }

    /**
     * Assignments and increments. If discard, the result is not left on the stack.
     * Returns false if e is not such a node.
     */
    private boolean assignLike(Expr e, boolean discard) {
        if (e instanceof IntAssign a) { primAssign(a.idx, a.depth, VarType.INT, a.value, discard); return true; }
        if (e instanceof LongAssign a) { primAssign(a.idx, a.depth, VarType.LONG, a.value, discard); return true; }
        if (e instanceof DoubleAssign a) { primAssign(a.idx, a.depth, VarType.DOUBLE, a.value, discard); return true; }
        if (e instanceof BoolAssign a) { primAssign(a.idx, a.depth, VarType.BOOLEAN, a.value, discard); return true; }
        if (e instanceof IntInc inc) {
            int local = inc.depth == 0 ? primLocal[inc.idx] : -1;
            if (local >= 0) {
                if (discard) c.iinc(local, inc.delta);
                else if (inc.prefix) { c.iinc(local, inc.delta); c.iload(local); }
                else { c.iload(local); c.iinc(local, inc.delta); }
            } else {
                int t = temp(VarType.INT);
                loadPrim(inc.idx, inc.depth, VarType.INT);
                c.istore(t);
                storePrim(inc.idx, inc.depth, VarType.INT, () -> { c.iload(t); c.iconst(inc.delta); c.iadd(); });
                if (!discard) {
                    c.iload(t);
                    if (inc.prefix) { c.iconst(inc.delta); c.iadd(); }
                }
                release(VarType.INT, t);
            }
            return true;
        }
        if (e instanceof StaticFieldSet ss) { staticFieldSet(ss, discard); return true; }
        if (e instanceof StaticFieldInc si) {
            konst(si.target, SF_);
            c.dup();
            c.invokevirtual(SF_, "getInt", "()I");
            int old = temp(VarType.INT);
            c.istore(old);
            c.iload(old);
            c.iconst(si.delta);
            c.iadd();
            c.invokevirtual(SF_, "setI", "(I)V");
            if (!discard) {
                c.iload(old);
                if (si.prefix) { c.iconst(si.delta); c.iadd(); }
            }
            release(VarType.INT, old);
            return true;
        }
        if (e instanceof ThisFieldSet ts) { thisFieldSet(ts, discard); return true; }
        if (e instanceof ThisFieldInc ti) { thisFieldInc(ti, discard); return true; }
        if (e instanceof AssignLocal a) { slotAssign(a.slot, 0, a.type, a.value, discard); return true; }
        if (e instanceof AssignUpvalue a) { slotAssign(a.slot, a.depth, a.type, a.value, discard); return true; }
        if (e instanceof Assign a) {
            if (a.target instanceof Member m) {
                expr(m.obj, VarType.DYN);
                konst(m.cache, FC);
                VarType vt = st(a.value);
                // typed write - no boxing, exactly as for arrays below
                VarType store = vt.isPrimitive() ? vt : VarType.DYN;
                expr(a.value, store);
                String name, d;
                switch (store) {
                    case INT -> { name = "memberSetI"; d = "(" + OBJ_D + "L" + FC + ";I)V"; }
                    case LONG -> { name = "memberSetJ"; d = "(" + OBJ_D + "L" + FC + ";J)V"; }
                    case DOUBLE -> { name = "memberSetD"; d = "(" + OBJ_D + "L" + FC + ";D)V"; }
                    case BOOLEAN -> { name = "memberSetZ"; d = "(" + OBJ_D + "L" + FC + ";Z)V"; }
                    default -> { name = "memberSet"; d = "(" + OBJ_D + "L" + FC + ";" + OBJ_D + ")V"; }
                }
                if (!discard) {
                    int t = temp(store);
                    c.dup();
                    c.store(t);
                    c.invokestatic(OPS, name, d);
                    c.load(t);
                    box(c, store);
                    release(store, t);
                } else {
                    c.invokestatic(OPS, name, d);
                }
            } else if (a.target instanceof Index ix) {
                VarType avt = st(a.value);
                String da = st(ix.key) == VarType.INT ? directArray(ix.obj, avt) : null;
                if (da != null) {
                    expr(ix.obj, VarType.DYN);
                    c.checkcast(da);
                    expr(ix.key, VarType.INT);
                    expr(a.value, avt);
                    if (!discard) {
                        int t = temp(avt);
                        c.dup();
                        c.store(t);
                        arrayStore(avt);
                        c.load(t);
                        release(avt, t);
                    } else {
                        arrayStore(avt);
                    }
                    return true;
                }
                expr(ix.obj, VarType.DYN);
                boolean intKey = st(ix.key) == VarType.INT;
                expr(ix.key, intKey ? VarType.INT : VarType.DYN);
                VarType vt = st(a.value);
                // typed write into a specialized array - no boxing
                VarType store = intKey && (vt == VarType.INT || vt == VarType.DOUBLE || vt == VarType.BOOLEAN) ? vt : VarType.DYN;
                expr(a.value, store);
                String name, d;
                switch (store) {
                    case INT -> { name = "setIndexI"; d = "(" + OBJ_D + "II)V"; }
                    case DOUBLE -> { name = "setIndexD"; d = "(" + OBJ_D + "ID)V"; }
                    case BOOLEAN -> { name = "setIndexZ"; d = "(" + OBJ_D + "IZ)V"; }
                    default -> {
                        name = intKey ? "setIndexInt" : "setIndex";
                        d = intKey ? "(" + OBJ_D + "I" + OBJ_D + ")V" : "(" + OBJ_D + OBJ_D + OBJ_D + ")V";
                    }
                }
                if (!discard) {
                    int t = temp(store);
                    c.dup();
                    c.store(t);
                    c.invokestatic(OPS, name, d);
                    c.load(t);
                    box(c, store); // the assignment result used as a value is rare - box for uniformity
                    release(store, t);
                    return true;
                }
                c.invokestatic(OPS, name, d);
            } else throw new Unsupported("assign target " + a.target.getClass().getSimpleName());
            return true;
        }
        if (e instanceof CompoundAssign ca) {
            compoundAssign(ca, discard);
            return true;
        }
        if (e instanceof IncLocal il) {
            VarType uk = unboxed(il.slot, 0);
            if (uk == VarType.INT && il.type == VarType.DYN) {
                // a statically unboxed int local: iinc, not box-add-unbox
                int local = slotLocal[il.slot];
                if (discard) c.iinc(local, il.delta);
                else if (il.prefix) { c.iinc(local, il.delta); c.iload(local); }
                else { c.iload(local); c.iinc(local, il.delta); }
                return true;
            }
            VarType dk = dual(il.slot, 0);
            if (dk != null && il.type == VarType.DYN) {
                if (discard || il.prefix) {
                    dualAssign(il.slot, dk, new Binary(TokenType.PLUS, new Local(il.slot, il.line), new IntLit(il.delta, il.line), il.line), discard);
                } else {
                    loadDual(il.slot, dk);
                    dualAssign(il.slot, dk, new Binary(TokenType.PLUS, new Local(il.slot, il.line), new IntLit(il.delta, il.line), il.line), true);
                }
                return true;
            }
            incGeneric(new Local(il.slot, il.line), il.delta, il.prefix, il.type, discard);
            return true;
        }
        if (e instanceof IncGeneric ig) {
            incGeneric(ig.target, ig.delta, ig.prefix, VarType.DYN, discard);
            return true;
        }
        return false;
    }

    /**
     * What an assignment leaves on the stack when its value is used: a statically unboxed slot
     * (or an `iinc`-ed int slot) produces its primitive, everything else the Object of the node.
     * Must agree with {@link Unbox#typeOf} for the same nodes, or the consumer would convert from the wrong type.
     */
    private VarType assignProduced(Expr e) {
        if (e instanceof AssignLocal a && a.type == VarType.DYN) {
            VarType u = dual(a.slot, 0) == null ? unboxed(a.slot, 0) : null;
            if (u != null) return u;
        } else if (e instanceof AssignUpvalue a && a.type == VarType.DYN) {
            VarType u = dual(a.slot, a.depth) == null ? unboxed(a.slot, a.depth) : null;
            if (u != null) return u;
        } else if (e instanceof IncLocal il && il.type == VarType.DYN && unboxed(il.slot, 0) == VarType.INT) {
            return VarType.INT;
        }
        return e.type == VarType.STRING ? VarType.DYN : e.type;
    }

    private void primAssign(int idx, int depth, VarType t, Expr value, boolean discard) {
        if (discard) {
            storePrim(idx, depth, t, () -> expr(value, t));
        } else {
            int tmp = temp(t);
            expr(value, t);
            c.store(tmp);
            storePrim(idx, depth, t, () -> c.load(tmp));
            c.load(tmp);
            release(t, tmp);
        }
    }

    private void slotAssign(int slot, int depth, VarType type, Expr value, boolean discard) {
        VarType dk = dual(slot, depth);
        if (dk != null && type == VarType.DYN) {
            dualAssign(slot, dk, value, discard);
            return;
        }
        VarType u = unboxed(slot, depth);
        if (u != null && type == VarType.DYN) {   // unboxed slot: write a primitive into it
            int local = slotLocal[slot];
            expr(value, u);
            if (!discard) c.dupWide();
            c.store(local);
            return;
        }
        Runnable v = () -> {
            expr(value, VarType.DYN);
            if (type == VarType.STRING) c.invokestatic(OPS, "toStringSlot", "(" + OBJ_D + ")" + OBJ_D);
        };
        if (discard) storeSlot(slot, depth, v);
        else {
            int tmp = temp(VarType.DYN);
            v.run();
            c.astore(tmp);
            storeSlot(slot, depth, () -> c.aload(tmp));
            c.aload(tmp);
            release(VarType.DYN, tmp);
        }
    }

    /** Load the current value of an lvalue as Object (for compound assignments). */
    private void loadLvalue(Expr target) {
        if (target instanceof GlobalGet g) { gen(g, VarType.DYN); return; }
        if (target instanceof Local l) loadSlot(l.slot, 0);
        else if (target instanceof Upvalue u) loadSlot(u.slot, u.depth);
        else if (target instanceof Member m) { expr(m.obj, VarType.DYN); konst(m.cache, FC); c.invokestatic(OPS, "memberGet", "(" + OBJ_D + "L" + FC + ";)" + OBJ_D); }
        else if (target instanceof ThisField tf) { konst(tf, TF); expr(tf.self, VarType.DYN); c.invokevirtual(TF, "slowGet", "(" + OBJ_D + ")" + OBJ_D); }
        else if (target instanceof StaticField sf) { konst(sf, SF_); c.invokevirtual(SF_, "get", "()" + OBJ_D); }
        else if (target instanceof Index ix) { expr(ix.obj, VarType.DYN); expr(ix.key, VarType.DYN); c.invokestatic(OPS, "index", "(" + OBJ_D + OBJ_D + ")" + OBJ_D); }
        else throw new Unsupported("lvalue " + target.getClass().getSimpleName());
    }

    /** Store the Object value from temp into the lvalue. */
    private void storeLvalue(Expr target, int tmp) {
        if (target instanceof GlobalGet g) {
            if (g.cell != null) {
                // Cell.set, as GlobalSet: the write must invalidate the sites that took the old value
                // or the old function as a constant (ValueSite, CellSite)
                konst(g.cell, CELL); c.aload(tmp); c.invokevirtual(CELL, "set", "(" + OBJ_D + ")V");
                return;
            }
            konst(g.vars, "java/util/Map"); c.ldcString(g.name); c.aload(tmp); c.invokeinterface("java/util/Map", "put", "(" + OBJ_D + OBJ_D + ")" + OBJ_D); c.pop1(); return;
        }
        if (target instanceof Local l) storeSlot(l.slot, 0, () -> c.aload(tmp));
        else if (target instanceof Upvalue u) storeSlot(u.slot, u.depth, () -> c.aload(tmp));
        else if (target instanceof Member m) { expr(m.obj, VarType.DYN); konst(m.cache, FC); c.aload(tmp); c.invokestatic(OPS, "memberSet", "(" + OBJ_D + "L" + FC + ";" + OBJ_D + ")V"); }
        else if (target instanceof ThisField tf) { konst(tf, TF); expr(tf.self, VarType.DYN); c.aload(tmp); c.invokevirtual(TF, "slowSet", "(" + OBJ_D + OBJ_D + ")V"); }
        else if (target instanceof StaticField sf) { konst(sf, SF_); c.aload(tmp); c.invokevirtual(SF_, "set", "(" + OBJ_D + ")V"); }
        else if (target instanceof Index ix) { expr(ix.obj, VarType.DYN); expr(ix.key, VarType.DYN); c.aload(tmp); c.invokestatic(OPS, "setIndex", "(" + OBJ_D + OBJ_D + OBJ_D + ")V"); }
        else throw new Unsupported("lvalue " + target.getClass().getSimpleName());
    }

    private static String opsName(TokenType op) {
        return switch (op) {
            case PLUS, PLUSEQ -> "add";
            case MINUS, MINUSEQ -> "sub";
            case STAR, STAREQ -> "mul";
            case SLASH, SLASHEQ -> "div";
            case PERCENT, PERCENTEQ -> "mod";
            case AMP -> "band";
            case PIPE -> "bor";
            case CARET -> "bxor";
            case SHL -> "shl";
            case SHR -> "shr";
            case USHR -> "ushr";
            default -> null;
        };
    }

    /** Name of the mixed operation Ops.op(Object, prim) for arithmetic with a primitive right operand, otherwise null. */
    private static String mixedOp(TokenType op, VarType rt) {
        if (rt != VarType.INT && rt != VarType.LONG && rt != VarType.DOUBLE) return null;
        return switch (op) {
            case PLUS, PLUSEQ, MINUS, MINUSEQ, STAR, STAREQ, SLASH, SLASHEQ, PERCENT, PERCENTEQ -> opsName(op);
            default -> null;
        };
    }

    /** The current value (Object) is already on the stack: apply op with the value of ca.value, leaving an Object. */
    private void mixedApply(CompoundAssign ca, String op) {
        VarType rt = st(ca.value);
        if (mixedOp(ca.op, rt) != null) {
            expr(ca.value, rt);
            c.invokestatic(OPS, op, "(" + OBJ_D + desc(rt) + ")" + OBJ_D);
        } else {
            expr(ca.value, VarType.DYN);
            c.invokestatic(OPS, op, "(" + OBJ_D + OBJ_D + ")" + OBJ_D);
        }
    }

    /**
     * Statement `t.f op= expr` on a plain table: one guard on the shape, inside - a direct
     * read and write of the slot without boxing, on a miss - the old generic path.
     *
     * <p>The shape is taken from the inline cache of this same site: by compilation time the function has already
     * run on the interpreter, and the cache is warm. It is baked into the code as a <b>constant</b>
     * rather than read from the cache on every step: the cache self-heals and may show another shape
     * where the same name has a different index and a different representation - and we would silently
     * read someone else's field.
     *
     * <p>Only a "quiet" right-hand side is taken - arithmetic over locals, literals and fields
     * of the same receiver. A call inside it could change the table shape between the guard
     * and the write, and the write would go into prims of a slot that had become a reference by then.
     *
     * <p>Only in statement form (discard): nobody needs the result of the compound assignment,
     * and both branches leave the stack empty - otherwise their types would have to be merged at the join point.
     *
     * @return true if the statement was generated here in full
     */
    private boolean specCompound(CompoundAssign ca) {
        if (!SPEC || unbox == null || specRecv != null) return false;
        if (!(ca.target instanceof Member m) || !(m.obj instanceof Local l)) return false;
        if (noSpec) return false;
        Spec sp = new Spec();
        Recv tr = sp.recv.computeIfAbsent(l.slot, k -> newRecv(k));
        tr.obj = l;
        tr.keys.putIfAbsent(m.name, 0);
        if (!specScan(ca.value, sp)) return false;
        specNumber(sp.recv);

        // The decision is made before a single byte is emitted: after the guards there is no way back.
        Binary syn = new Binary(ca.op, m, ca.value, ca.line);
        specEnter(sp);
        VarType a = typedBinaryType(syn);
        specLeave();
        // The result must land in the slot as is: otherwise the write would change the representation
        // of the slot, while the guard has already promised it is double.
        if (a != VarType.DOUBLE) return false;

        Label slow = c.label(), end = c.label();
        specEnter(sp);
        specGuards(sp, slow);
        specSet(m, () -> typedBinary(syn));
        specLeave();
        c.goTo(end);

        c.mark(slow);
        noSpec = true;
        int res = temp(VarType.DYN);
        c.aload(tr.objLocal);
        konst(m.cache, FC);
        c.invokestatic(OPS, "memberGet", "(" + OBJ_D + "L" + FC + ";)" + OBJ_D);
        mixedApply(ca, opsName(ca.op));
        c.astore(res);
        c.aload(tr.objLocal);
        konst(m.cache, FC);
        c.aload(res);
        c.invokestatic(OPS, "memberSet", "(" + OBJ_D + "L" + FC + ";" + OBJ_D + ")V");
        release(VarType.DYN, res);
        noSpec = false;
        c.mark(end);
        specRelease(sp);
        return true;
    }

    private void compoundAssign(CompoundAssign ca, boolean discard) {
        // A naive load+store of Member/Index would evaluate obj/key twice, which the interpreter's CompoundAssign
        // does not do, so for them obj and key are cached in temps.
        Expr target = ca.target;
        if (discard && specCompound(ca)) return;

        // Unboxed local: `energy += ...` without the round trip "box the result, unbox it back".
        // Without this branch unboxing an accumulator slot makes the code slower, not faster:
        // the value is taken from the local, boxed for Ops, and immediately unboxed on the write.
        if (target instanceof Local tl) {
            VarType dk = dual(tl.slot, 0);
            if (dk != null) {
                dualAssign(tl.slot, dk, new Binary(ca.op, tl, ca.value, ca.line), discard);
                return;
            }
            VarType u = unboxed(tl.slot, 0);
            if (u != null) {
                VarType r = typedBinary(new Binary(ca.op, tl, ca.value, ca.line));
                if (r != null) {
                    convert(r, u);
                    if (!discard) c.dupWide();
                    c.store(slotLocal[tl.slot]);
                    return;
                }
            }
        }
        String op = opsName(ca.op);
        int res = temp(VarType.DYN);
        if (target instanceof Member m) {
            int o = temp(VarType.DYN);
            expr(m.obj, VarType.DYN);
            c.astore(o);
            c.aload(o);
            konst(m.cache, FC);
            c.invokestatic(OPS, "memberGet", "(" + OBJ_D + "L" + FC + ";)" + OBJ_D);
            mixedApply(ca, op);
            c.astore(res);
            c.aload(o);
            konst(m.cache, FC);
            c.aload(res);
            c.invokestatic(OPS, "memberSet", "(" + OBJ_D + "L" + FC + ";" + OBJ_D + ")V");
            release(VarType.DYN, o);
        } else if (target instanceof Index ix) {
            int o = temp(VarType.DYN), k = temp(VarType.DYN);
            expr(ix.obj, VarType.DYN);
            c.astore(o);
            expr(ix.key, VarType.DYN);
            c.astore(k);
            c.aload(o);
            c.aload(k);
            c.invokestatic(OPS, "index", "(" + OBJ_D + OBJ_D + ")" + OBJ_D);
            mixedApply(ca, op);
            c.astore(res);
            c.aload(o);
            c.aload(k);
            c.aload(res);
            c.invokestatic(OPS, "setIndex", "(" + OBJ_D + OBJ_D + OBJ_D + ")V");
            release(VarType.DYN, o);
            release(VarType.DYN, k);
        } else {
            loadLvalue(target);
            mixedApply(ca, op);
            c.astore(res);
            storeLvalue(target, res);
        }
        if (!discard) c.aload(res);
        release(VarType.DYN, res);
    }

    private void incGeneric(Expr target, int delta, boolean prefix, VarType slotType, boolean discard) {
        int old = temp(VarType.DYN), nv = temp(VarType.DYN);
        loadLvalue(target);
        c.astore(old);
        c.aload(old);
        c.iconst(delta);
        c.invokestatic(OPS, "box", "(I)" + OBJ_D);
        c.invokestatic(OPS, "add", "(" + OBJ_D + OBJ_D + ")" + OBJ_D);
        if (slotType == VarType.STRING) c.invokestatic(OPS, "toStringSlot", "(" + OBJ_D + ")" + OBJ_D);
        c.astore(nv);
        storeLvalue(target, nv);
        if (!discard) c.aload(prefix ? nv : old);
        release(VarType.DYN, old);
        release(VarType.DYN, nv);
    }

    // ================= fields of this (sealed classes) =================

    /**
     * Generated instance class for the receiver of this node, or null - then the field sits
     * in an array, as before. The class is generated right here: its name goes into the bytecode, and by the time
     * of execution the class must exist, otherwise the call site will not link.
     *
     * <p>Additionally the field name is cross-checked by index: the index comes from the ClassNode, the descriptor
     * from it too, but a mistake here would silently read someone else's field instead of throwing.
     */
    private String instClass(ThisField tf) {
        if (tf.owner == null || InstanceGen.classFor(tf.owner) == null) return null;
        if (!tf.name.equals(InstanceGen.keyAt(tf.owner, tf.idx))) return null;
        if (!InstanceGen.desc(tf.ftype).equals(InstanceGen.desc(InstanceGen.typeAt(tf.owner, tf.idx)))) return null;
        return InstanceGen.nameFor(tf.owner);
    }

    /**
     * Guard for direct access. For a generated instance class - a type check of the receiver:
     * a subclass passes it, because the inheritance of the generated classes mirrors
     * the script inheritance. Otherwise, as before, the shape is compared with the one verified for the node.
     * self sits in local o; on a miss - jump to slow.
     */
    private void thisFieldGuard(int o, ThisField tf, Label slow) {
        String ic = instClass(tf);
        if (ic != null) {
            c.aload(o);
            c.instanceOf(ic);
            c.ifeq(slow);
            return;
        }
        c.aload(o);
        c.instanceOf(JTABLE);
        c.ifeq(slow);
        c.aload(o);
        c.checkcast(JTABLE);
        c.getfield(JTABLE, "shape", "L" + SHAPE + ";");
        konst(tf, TF);
        c.getfield(TF, "cached", "L" + SHAPE + ";");
        c.if_acmpne(slow);
    }

    /**
     * Descriptor of an array whose element can be accessed directly: the element type is known
     * statically and matches the type in which it is read or written. Otherwise null - the
     * generic path through Ops remains, with all its checks and error messages.
     *
     * <p>The type match is mandatory: `int[] a; a[i] = true` must keep arriving at
     * Ops.setIndexZ and getting a readable error there, not crash on a bastore into an int[].
     */
    private static String directArray(Expr obj, VarType t) {
        Class<?> comp = obj.arrayComp;
        if (comp == null) return null;
        return switch (t) {
            case INT -> comp == int.class ? "[I" : null;
            case LONG -> comp == long.class ? "[J" : null;
            case DOUBLE -> comp == double.class ? "[D" : null;
            case BOOLEAN -> comp == boolean.class ? "[Z" : null;
            default -> null;
        };
    }

    private void arrayStore(VarType t) {
        switch (t) {
            case INT -> c.iastore();
            case LONG -> c.lastore();
            case DOUBLE -> c.dastore();
            default -> c.bastore();
        }
    }

    /** Operation index for typed arithmetic (as in Prims.IntBin), or -1. */
    private static int binIndex(TokenType op) {
        return switch (op) {
            case PLUS, PLUSEQ -> 0; case MINUS, MINUSEQ -> 1; case STAR, STAREQ -> 2;
            case SLASH, SLASHEQ -> 3; case PERCENT, PERCENTEQ -> 4;
            case AMP -> 5; case PIPE -> 6; case CARET -> 7;
            case SHL -> 8; case SHR -> 9; case USHR -> 10;
            default -> -1;
        };
    }

    /**
     * Binary operation whose operands are both primitive, taking slot unboxing into account.
     *
     * <p>This is the second half of unboxing, and without it the first is nearly useless: a slot can be kept
     * in an int local, but if `i < n` and `i & 1023` still go through Ops with boxing,
     * the value is boxed back on every step. The type comes from st(), not from the node field:
     * the field was computed by the parser, when the slot was still dynamic.
     *
     * @return the result type on the stack, or null - the operation is not typed, take the generic path
     */
    /**
     * Result type of typed arithmetic, or null - we do not take such an operation.
     *
     * <p>Split out of {@link #typedBinary} because the same decision has to be made <b>before</b>
     * generation too: the table-shape speculation emits a guard after which there is no way
     * back, and there is no other way to learn "will it work". The checks here are exactly the ones
     * typedBinary used to refuse on, and it calls them itself - so that the two places cannot diverge.
     */
    private VarType typedBinaryType(Binary b) {
        if (unbox == null) return null;
        VarType lt = st(b.left), rt = st(b.right);
        if (!lt.isPrimitive() || !rt.isPrimitive()) return null;
        if (lt == VarType.BOOLEAN || rt == VarType.BOOLEAN) return null;   // boolean arithmetic is left alone
        if (b.type == VarType.STRING) return null;
        int op = binIndex(b.op);
        boolean isCmp = switch (b.op) { case LT, LE, GT, GE, EQEQ, NE -> true; default -> false; };
        if (!isCmp && op < 0) return null;
        VarType a = VarType.arith(lt, rt);
        if (!a.isNumeric()) return null;
        if (op >= 5 && a == VarType.DOUBLE) return null;                   // double has no bitwise ops
        return isCmp ? VarType.BOOLEAN : a;
    }

    private VarType typedBinary(Binary b) {
        if (typedBinaryType(b) == null) return null;
        VarType lt = st(b.left), rt = st(b.right);
        int op = binIndex(b.op);
        boolean isCmp = switch (b.op) { case LT, LE, GT, GE, EQEQ, NE -> true; default -> false; };
        VarType a = VarType.arith(lt, rt);

        if (isCmp) {
            Label lTrue = c.label(), lEnd = c.label();
            expr(b.left, a);
            expr(b.right, a);
            switch (a) {
                case INT -> {
                    switch (b.op) {
                        case LT -> c.if_icmplt(lTrue); case LE -> c.if_icmple(lTrue);
                        case GT -> c.if_icmpgt(lTrue); case GE -> c.if_icmpge(lTrue);
                        case EQEQ -> c.if_icmpeq(lTrue); default -> c.if_icmpne(lTrue);
                    }
                }
                case LONG -> {
                    c.lcmp();
                    cmpBranch(b.op, lTrue);
                }
                default -> {
                    // NaN: < and <= need dcmpg, > and >= need dcmpl, otherwise the condition would become true
                    if (b.op == TokenType.LT || b.op == TokenType.LE) c.dcmpg(); else c.dcmpl();
                    cmpBranch(b.op, lTrue);
                }
            }
            c.iconst(0);
            c.goTo(lEnd);
            c.mark(lTrue);
            c.iconst(1);
            c.mark(lEnd);
            return VarType.BOOLEAN;
        }

        if (op >= 8) {          // shifts: the right operand is an int
            expr(b.left, a);
            expr(b.right, VarType.INT);
        } else {
            expr(b.left, a);
            expr(b.right, a);
        }
        switch (a) {
            case INT -> {
                switch (op) {
                    case 0 -> c.iadd(); case 1 -> c.isub(); case 2 -> c.imul(); case 3 -> c.idiv(); case 4 -> c.irem();
                    case 5 -> c.iand(); case 6 -> c.ior(); case 7 -> c.ixor();
                    case 8 -> c.ishl(); case 9 -> c.ishr(); default -> c.iushr();
                }
                return VarType.INT;
            }
            case LONG -> {
                switch (op) {
                    case 0 -> c.ladd(); case 1 -> c.lsub(); case 2 -> c.lmul(); case 3 -> c.ldiv(); case 4 -> c.lrem();
                    case 5 -> c.land(); case 6 -> c.lor(); case 7 -> c.lxor();
                    case 8 -> c.lshl(); case 9 -> c.lshr(); default -> c.lushr();
                }
                return VarType.LONG;
            }
            default -> {
                switch (op) {
                    case 0 -> c.dadd(); case 1 -> c.dsub(); case 2 -> c.dmul(); case 3 -> c.ddiv(); case 4 -> c.drem();
                    default -> throw new Unsupported("double " + b.op);
                }
                return VarType.DOUBLE;
            }
        }
    }

    /** Branch on the result of lcmp/dcmp (-1/0/1 on the stack). */
    private void cmpBranch(TokenType op, Label t) {
        switch (op) {
            case LT -> c.iflt(t); case LE -> c.ifle(t); case GT -> c.ifgt(t);
            case GE -> c.ifge(t); case EQEQ -> c.ifeq(t); default -> c.ifne(t);
        }
    }

    private static String slowSuffix(VarType t) {
        return switch (t) { case INT -> "I"; case LONG -> "J"; case DOUBLE -> "D"; case BOOLEAN -> "Z"; default -> ""; };
    }

    private VarType thisFieldGet(ThisField tf) {
        VarType ft = tf.ftype;
        VarType res = ft.isPrimitive() ? ft : VarType.DYN;
        int o = temp(VarType.DYN);
        expr(tf.self, VarType.DYN);
        c.astore(o);
        Label slow = c.label(), end = c.label();
        thisFieldGuard(o, tf, slow);
        String ic = instClass(tf);
        c.aload(o);
        if (ic != null) {                 // a real field: a single access, as in Java
            c.checkcast(ic);
            c.getfield(ic, "f" + tf.idx, InstanceGen.desc(ft));
        } else {
            c.checkcast(PLAIN);
            if (ft.isPrimitive()) {
                c.getfield(PLAIN, "prims", "[J");
                c.iconst(tf.idx);
                c.laload();
                fromBits(ft);
            } else {
                c.getfield(PLAIN, "values", "[" + OBJ_D);
                c.iconst(tf.idx);
                c.aaload();
            }
        }
        c.goTo(end);
        c.mark(slow);
        konst(tf, TF);
        c.aload(o);
        switch (ft) {
            case INT -> c.invokevirtual(TF, "slowInt", "(" + OBJ_D + ")I");
            case LONG -> c.invokevirtual(TF, "slowLong", "(" + OBJ_D + ")J");
            case DOUBLE -> c.invokevirtual(TF, "slowDouble", "(" + OBJ_D + ")D");
            case BOOLEAN -> c.invokevirtual(TF, "slowBool", "(" + OBJ_D + ")Z");
            default -> c.invokevirtual(TF, "slowGet", "(" + OBJ_D + ")" + OBJ_D);
        }
        c.mark(end);
        release(VarType.DYN, o);
        return res;
    }

    private void thisFieldSet(ThisFieldSet ts, boolean discard) {
        ThisField tf = ts.target;
        VarType ft = tf.ftype;
        VarType vt = ft.isPrimitive() ? ft : VarType.DYN;
        int o = temp(VarType.DYN);
        expr(tf.self, VarType.DYN);
        c.astore(o);
        int v = temp(vt);
        expr(ts.value, vt);
        if (ft == VarType.STRING) c.invokestatic(OPS, "toStringSlot", "(" + OBJ_D + ")" + OBJ_D);
        c.store(v);
        Label slow = c.label(), end = c.label();
        thisFieldGuard(o, tf, slow);
        String ic = instClass(tf);
        c.aload(o);
        if (ic != null) {
            c.checkcast(ic);
            c.load(v);
            c.putfield(ic, "f" + tf.idx, InstanceGen.desc(ft));
        } else {
            c.checkcast(PLAIN);
            if (ft.isPrimitive()) {
                c.getfield(PLAIN, "prims", "[J");
                c.iconst(tf.idx);
                c.load(v);
                toBits(ft);
                c.lastore();
            } else {
                c.getfield(PLAIN, "values", "[" + OBJ_D);
                c.iconst(tf.idx);
                c.aload(v);
                c.aastore();
            }
        }
        c.goTo(end);
        c.mark(slow);
        konst(tf, TF);
        c.aload(o);
        c.load(v);
        c.invokevirtual(TF, "slowSet" + slowSuffix(ft), "(" + OBJ_D + (ft.isPrimitive() ? desc(ft) : OBJ_D) + ")V");
        c.mark(end);
        if (!discard) c.load(v);
        release(VarType.DYN, o);
        release(vt, v);
    }

    private void staticFieldSet(StaticFieldSet ss, boolean discard) {
        StaticField sf = ss.target;
        VarType ft = sf.ftype;
        VarType vt = ft.isPrimitive() ? ft : VarType.DYN;
        konst(sf, SF_);
        expr(ss.value, vt);
        if (ft == VarType.STRING) c.invokestatic(OPS, "toStringSlot", "(" + OBJ_D + ")" + OBJ_D);
        int v = temp(vt);
        c.store(v);
        c.load(v);
        switch (ft) {
            case INT -> c.invokevirtual(SF_, "setI", "(I)V");
            case LONG -> c.invokevirtual(SF_, "setJ", "(J)V");
            case DOUBLE -> c.invokevirtual(SF_, "setD", "(D)V");
            case BOOLEAN -> c.invokevirtual(SF_, "setZ", "(Z)V");
            default -> c.invokevirtual(SF_, "set", "(" + OBJ_D + ")V");
        }
        if (!discard) c.load(v);
        release(vt, v);
    }

    private void thisFieldInc(ThisFieldInc ti, boolean discard) {
        ThisField tf = ti.target;
        int o = temp(VarType.DYN), old = temp(VarType.INT);
        expr(tf.self, VarType.DYN);
        c.astore(o);
        Label slow = c.label(), end = c.label();
        thisFieldGuard(o, tf, slow);
        String ic = instClass(tf);
        if (ic != null) {
            c.aload(o);
            c.checkcast(ic);
            c.dup();
            c.getfield(ic, "f" + tf.idx, "I");
            c.istore(old);
            c.iload(old);
            c.iconst(ti.delta);
            c.iadd();
            c.putfield(ic, "f" + tf.idx, "I");
        } else {
            c.aload(o);
            c.checkcast(PLAIN);
            c.getfield(PLAIN, "prims", "[J");
            c.dup();
            c.iconst(tf.idx);
            c.laload();
            c.l2i();
            c.istore(old);
            c.iconst(tf.idx);
            c.iload(old);
            c.iconst(ti.delta);
            c.iadd();
            c.i2l();
            c.lastore();
        }
        c.goTo(end);
        c.mark(slow);
        konst(tf, TF);
        c.aload(o);
        c.invokevirtual(TF, "slowInt", "(" + OBJ_D + ")I");
        c.istore(old);
        konst(tf, TF);
        c.aload(o);
        c.iload(old);
        c.iconst(ti.delta);
        c.iadd();
        c.invokevirtual(TF, "slowSetI", "(" + OBJ_D + "I)V");
        c.mark(end);
        if (!discard) {
            c.iload(old);
            if (ti.prefix) { c.iconst(ti.delta); c.iadd(); }
        }
        release(VarType.DYN, o);
        release(VarType.INT, old);
    }

    /**
     * A whole chain `"key" + i + ...` as one {@code StringConcatFactory.makeConcatWithConstants} site,
     * the way javac compiles it: literal parts go into the recipe, int/long/boolean operands are passed
     * as primitives (Java formats them exactly as Jumper does), doubles and everything else as the
     * String Jumper's own str() makes of them. The pairwise String.concat it replaces built each
     * intermediate string: `"key" + i` was Integer.toString's byte[] and String, then concat's
     * byte[] and String - map_string_keys/dyn spent a third of its bytes on that.
     */
    private void indyConcat(Binary b) {
        List<Expr> parts = new ArrayList<>();
        flattenConcat(b, parts);
        StringBuilder recipe = new StringBuilder();
        StringBuilder desc = new StringBuilder("(");
        int args = 0;
        for (Expr e : parts) {
            if (e instanceof Literal l && l.value instanceof String str && str.indexOf('\u0001') < 0 && str.indexOf('\u0002') < 0) {
                recipe.append(str);
                continue;
            }
            VarType et = st(e);
            if (et == VarType.INT || et == VarType.LONG || et == VarType.BOOLEAN) {
                gen(e, et);
                desc.append(desc(et));
            } else {
                concatOperand(e);
                desc.append("Ljava/lang/String;");
            }
            recipe.append('\u0001');
            args++;
        }
        if (args == 0) { c.ldcString(recipe.toString()); return; }
        c.invokedynamic(cb.bootstrap("java/lang/invoke/StringConcatFactory", "makeConcatWithConstants",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                        cb.cp.string(recipe.toString())),
                "concat", desc.append(")Ljava/lang/String;").toString());
    }

    /** Operands of a chain of string `+`, left to right (at most 150 - the factory takes up to 200 slots). */
    private void flattenConcat(Expr e, List<Expr> out) {
        if (e instanceof Binary b && b.type == VarType.STRING && (b.op == TokenType.PLUS || b.op == TokenType.PLUSEQ) && out.size() < 150) {
            flattenConcat(b.left, out);
            flattenConcat(b.right, out);
        } else out.add(e);
    }

    private void concatOperand(Expr e) {
        if (e instanceof Literal l && l.value instanceof String str) { c.ldcString(str); return; }
        VarType et = st(e);
        VarType t = gen(e, et == VarType.STRING ? VarType.DYN : et);
        switch (t) {
            case INT -> c.invokestatic(OPS, "str", "(I)Ljava/lang/String;");
            case LONG -> c.invokestatic(OPS, "str", "(J)Ljava/lang/String;");
            case DOUBLE -> c.invokestatic(OPS, "str", "(D)Ljava/lang/String;");
            case BOOLEAN -> c.invokestatic(OPS, "str", "(Z)Ljava/lang/String;");
            default -> c.invokestatic(OPS, "str", "(" + OBJ_D + ")Ljava/lang/String;");
        }
    }

    /** Evaluate the expression, leaving a value of type want on the stack. */
    private void expr(Expr e, VarType want) {
        if (specExpr(e, want)) return;
        if (FOLD_CALL && foldCall(e, want)) return;
        VarType produced = gen(e, want);
        convert(produced, want);
    }

    /**
     * `obj.m(args)` or `obj.m(args) OP k` (k an int literal, non-zero for / and %) needed as int, long
     * or double, with obj of no static class: one invokedynamic returning that primitive, the operation
     * and the conversion done by the site per target (Indy.fit). The generic code would be the method
     * site returning Object, Ops.op(Object, int), Ops.unbox*: a typed method's int result got boxed
     * only to be unboxed right after - call_poly4 allocated 240 MB per run, Java nothing. The site
     * applies exactly those operations, and for an int-returning target their int version.
     */
    private boolean foldCall(Expr e, VarType want) {
        if (want != VarType.INT && want != VarType.LONG && want != VarType.DOUBLE) return false;
        MethodCall mc;
        int op = Indy.FOLD_NONE, k = 0;
        if (e instanceof MethodCall m) mc = m;
        else if (e instanceof Binary b && b.type != VarType.STRING && b.left instanceof MethodCall m && intLit(b.right) != null) {
            mc = m;
            k = intLit(b.right);
            op = switch (b.op) {
                case PLUS -> Indy.FOLD_ADD; case MINUS -> Indy.FOLD_SUB; case STAR -> Indy.FOLD_MUL;
                case SLASH -> k != 0 ? Indy.FOLD_DIV : -1; case PERCENT -> k != 0 ? Indy.FOLD_MOD : -1;
                default -> -1;
            };
            if (op < 0 || st(b) != VarType.DYN) return false;
        } else return false;
        if (mc.recvClass != null || pureMathType(mc) != null || st(mc) != VarType.DYN) return false;
        expr(mc.obj, VarType.DYN);
        StringBuilder d = new StringBuilder("(").append(OBJ_D);
        for (Expr a : mc.args) {
            VarType at = st(a);
            if (at == VarType.INT || at == VarType.LONG || at == VarType.DOUBLE || at == VarType.BOOLEAN) {
                expr(a, at);
                d.append(desc(at));
            } else {
                expr(a, VarType.DYN);
                d.append(OBJ_D);
            }
        }
        d.append(')').append(desc(want));
        c.invokedynamic(cb.bootstrap(Jit.PKG + "Indy", "bootstrapFold",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;II)Ljava/lang/invoke/CallSite;",
                cb.cp.integer(op), cb.cp.integer(k)), mc.name, d.toString());
        return true;
    }

    private static Integer intLit(Expr e) {
        if (e instanceof IntLit l) return l.v;
        if (e instanceof Literal l && l.value instanceof Integer i) return i;
        return null;
    }

    /** Generates e in its natural (or the given) type; returns the type on the stack. */
    private VarType gen(Expr e, VarType want) {
        // ---- literals ----
        if (e instanceof IntLit l) { c.iconst(l.v); return VarType.INT; }
        if (e instanceof LongLit l) { c.lconst(l.v); return VarType.LONG; }
        if (e instanceof DoubleLit l) { c.dconst(l.v); return VarType.DOUBLE; }
        if (e instanceof BoolLit l) { c.iconst(l.v ? 1 : 0); return VarType.BOOLEAN; }
        if (e instanceof Literal l) {
            Object v = l.value;
            if (v == null) c.aconstNull();
            else if (v instanceof String s) c.ldcString(s);
            else if (v instanceof Integer i) { c.iconst(i); return VarType.INT; }
            else if (v instanceof Double d) { c.dconst(d); return VarType.DOUBLE; }
            else if (v instanceof Long x) { c.lconst(x); return VarType.LONG; }
            else if (v instanceof Boolean b) { c.iconst(b ? 1 : 0); return VarType.BOOLEAN; }
            else konst(v, null);
            return VarType.DYN;
        }
        // ---- variables ----
        if (e instanceof Local l) {
            VarType u = unboxed(l.slot, 0);
            if (u != null) { c.load(slotLocal[l.slot]); return u; }
            VarType dk = dual(l.slot, 0);
            if (dk != null) {
                if (specDual != null && specDual.contains(l.slot)) { c.load(dualPrim[l.slot]); return dk; }
                loadDual(l.slot, dk);
                return VarType.DYN;
            }
            loadSlot(l.slot, 0);
            return VarType.DYN;
        }
        if (e instanceof GlobalGet g) {
            if (g.cell != null) {   // the name is resolved to a cell: one field load instead of Map.get
                konst(g.cell, CELL);
                c.getfield(CELL, "v", OBJ_D);
                return VarType.DYN;
            }
            konst(g.vars, "java/util/Map");
            c.ldcString(g.name);
            c.invokeinterface("java/util/Map", "get", "(" + OBJ_D + ")" + OBJ_D);
            return VarType.DYN;
        }
        if (e instanceof GlobalSet g) {
            if (g.cell != null) {
                // Through Cell.set, not putfield: the write must invalidate the direct calls through the cell (CellSite)
                konst(g.cell, CELL);
                if (g.value == null) { if (g.type.defaultValue() == null) c.aconstNull(); else konst(g.type.defaultValue(), null); }
                else expr(g.value, VarType.DYN);
                if (g.type != VarType.DYN) { konst(g.type, "me/padej/jumper/ast/VarType"); c.swap(); c.invokevirtual("me/padej/jumper/ast/VarType", "coerce", "(" + OBJ_D + ")" + OBJ_D); }
                c.dup_x1();
                c.invokevirtual(CELL, "set", "(" + OBJ_D + ")V");
                return VarType.DYN;
            }
            konst(g.vars, "java/util/Map");
            c.ldcString(g.name);
            if (g.value == null) { if (g.type.defaultValue() == null) c.aconstNull(); else konst(g.type.defaultValue(), null); }
            else expr(g.value, VarType.DYN);
            if (g.type != VarType.DYN) { konst(g.type, "me/padej/jumper/ast/VarType"); c.swap(); c.invokevirtual("me/padej/jumper/ast/VarType", "coerce", "(" + OBJ_D + ")" + OBJ_D); }
            c.dup_x2();
            c.invokeinterface("java/util/Map", "put", "(" + OBJ_D + OBJ_D + ")" + OBJ_D);
            c.pop1();
            return VarType.DYN;
        }
        if (e instanceof Upvalue u) { loadSlot(u.slot, u.depth); return VarType.DYN; }
        if (e instanceof ThisField tf) return thisFieldGet(tf);
        if (e instanceof StaticField sf) {
            konst(sf, SF_);
            switch (sf.ftype) {
                case INT -> { c.invokevirtual(SF_, "getInt", "()I"); return VarType.INT; }
                case LONG -> { c.invokevirtual(SF_, "getLong", "()J"); return VarType.LONG; }
                case DOUBLE -> { c.invokevirtual(SF_, "getDouble", "()D"); return VarType.DOUBLE; }
                case BOOLEAN -> { c.invokevirtual(SF_, "getBool", "()Z"); return VarType.BOOLEAN; }
                default -> { c.invokevirtual(SF_, "get", "()" + OBJ_D); return VarType.DYN; }
            }
        }
        if (e instanceof CheckJava cj) {
            expr(cj.value, VarType.DYN);
            konst(cj.cls, "java/lang/Class");
            c.invokestatic(OPS, "checkJava", "(" + OBJ_D + "Ljava/lang/Class;)" + OBJ_D);
            return VarType.DYN;
        }
        if (e instanceof CheckClass cc) {
            expr(cc.value, VarType.DYN);
            String ic = InstanceGen.classFor(cc.cls) == null ? null : InstanceGen.nameFor(cc.cls);
            if (ic != null) {
                // null or an instance of exactly this class (its generated class) passes inline; the
                // rest - subclasses, plain tables, errors - through Ops.checkClass. A `Node` parameter
                // or field (binary_trees) is checked on every construction.
                int t = temp(VarType.DYN);
                Label lOk = c.label();
                c.astore(t);
                c.aload(t);
                c.ifnull(lOk);
                c.aload(t);
                c.invokevirtual("java/lang/Object", "getClass", "()Ljava/lang/Class;");
                c.ldcClass(ic);
                c.if_acmpeq(lOk);
                c.aload(t);
                konst(cc.cls, CN);
                c.invokestatic(OPS, "checkClass", "(" + OBJ_D + "L" + CN + ";)" + OBJ_D);
                c.pop1();
                c.mark(lOk);
                c.aload(t);
                release(VarType.DYN, t);
                return VarType.DYN;
            }
            konst(cc.cls, CN);
            c.invokestatic(OPS, "checkClass", "(" + OBJ_D + "L" + CN + ";)" + OBJ_D);
            return VarType.DYN;
        }
        VarType pt = primOf(e);
        if (pt != null) {
            int[] loc = Prims.location(e);
            loadPrim(loc[0], loc[1], pt);
            return pt;
        }
        // ---- assignments ----
        if (assignLike(e, false)) return assignProduced(e);
        // ---- arithmetic ----
        if (e instanceof IntBin b) {
            expr(b.l, VarType.INT);
            expr(b.r, VarType.INT);
            switch (b.op) {
                case 0 -> c.iadd(); case 1 -> c.isub(); case 2 -> c.imul(); case 3 -> c.idiv(); case 4 -> c.irem();
                case 5 -> c.iand(); case 6 -> c.ior(); case 7 -> c.ixor(); case 8 -> c.ishl(); case 9 -> c.ishr(); case 10 -> c.iushr();
                default -> throw new IllegalStateException();
            }
            return VarType.INT;
        }
        if (e instanceof LongBin b) {
            expr(b.l, VarType.LONG);
            if (b.op >= 8) expr(b.r, b.r.type == VarType.INT ? VarType.INT : VarType.LONG);
            else expr(b.r, VarType.LONG);
            if (b.op >= 8 && b.r.type != VarType.INT) c.l2i();
            switch (b.op) {
                case 0 -> c.ladd(); case 1 -> c.lsub(); case 2 -> c.lmul(); case 3 -> c.ldiv(); case 4 -> c.lrem();
                case 5 -> c.land(); case 6 -> c.lor(); case 7 -> c.lxor(); case 8 -> c.lshl(); case 9 -> c.lshr(); case 10 -> c.lushr();
                default -> throw new IllegalStateException();
            }
            return VarType.LONG;
        }
        if (e instanceof DoubleBin b) {
            expr(b.l, VarType.DOUBLE);
            expr(b.r, VarType.DOUBLE);
            switch (b.op) {
                case 0 -> c.dadd(); case 1 -> c.dsub(); case 2 -> c.dmul(); case 3 -> c.ddiv(); case 4 -> c.drem();
                default -> throw new IllegalStateException();
            }
            return VarType.DOUBLE;
        }
        if (e instanceof IntNeg n) { expr(n.e, VarType.INT); c.ineg(); return VarType.INT; }
        if (e instanceof LongNeg n) { expr(n.e, VarType.LONG); c.lneg(); return VarType.LONG; }
        if (e instanceof DoubleNeg n) { expr(n.e, VarType.DOUBLE); c.dneg(); return VarType.DOUBLE; }
        if (e instanceof Neg n) {
            VarType nt = st(n.e);
            if (nt.isNumeric()) {
                expr(n.e, nt);
                switch (nt) { case INT -> c.ineg(); case LONG -> c.lneg(); default -> c.dneg(); }
                return nt;
            }
            expr(n.e, VarType.DYN); c.invokestatic(OPS, "neg", "(" + OBJ_D + ")" + OBJ_D); return VarType.DYN;
        }
        if (e instanceof AddLocalConst a) {
            VarType u = unboxed(a.slot, 0);
            if (u != null && u.isNumeric()) {
                c.load(slotLocal[a.slot]);
                switch (u) {
                    case INT -> { c.iconst(a.c); c.iadd(); }
                    case LONG -> { c.lconst(a.c); c.ladd(); }
                    default -> { c.dconst(a.c); c.dadd(); }
                }
                return u;
            }
            loadSlot(a.slot, 0);
            c.iconst(a.c);
            c.invokestatic(OPS, "box", "(I)" + OBJ_D);
            c.invokestatic(OPS, "add", "(" + OBJ_D + OBJ_D + ")" + OBJ_D);
            return VarType.DYN;
        }
        if (e instanceof Binary b && b.type == VarType.STRING && (b.op == TokenType.PLUS || b.op == TokenType.PLUSEQ)) {
            // concatenation: the sides are converted to String by their static type, without boxing primitives
            if (INDY_CONCAT) { indyConcat(b); return VarType.DYN; }
            concatOperand(b.left);
            concatOperand(b.right);
            c.invokevirtual("java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;");
            return VarType.DYN;
        }
        if (e instanceof Binary b) {
            VarType tb = typedBinary(b);
            if (tb != null) return tb;
            String cmp = switch (b.op) {
                case LT -> "lt"; case LE -> "le"; case GT -> "gt"; case GE -> "ge"; case EQEQ, NE -> "eq";
                default -> null;
            };
            VarType brt = st(b.right);
            if (cmp != null && (brt == VarType.INT || brt == VarType.LONG || brt == VarType.DOUBLE)) {
                // comparison of an object with a primitive - without boxing the right operand
                expr(b.left, VarType.DYN);
                expr(b.right, brt);
                c.invokestatic(OPS, cmp, "(" + OBJ_D + desc(brt) + ")Z");
                if (b.op == TokenType.NE) { c.iconst(1); c.ixor(); }
                return VarType.BOOLEAN;
            }
            String mixed = cmp == null ? mixedOp(b.op, brt) : null;
            if (mixed != null) { // Object op primitive - the right operand without boxing
                expr(b.left, VarType.DYN);
                expr(b.right, brt);
                c.invokestatic(OPS, opsName(b.op), "(" + OBJ_D + desc(brt) + ")" + OBJ_D);
                return VarType.DYN;
            }
            expr(b.left, VarType.DYN);
            expr(b.right, VarType.DYN);
            if (cmp != null) {
                c.invokestatic(OPS, cmp, "(" + OBJ_D + OBJ_D + ")Z");
                if (b.op == TokenType.NE) { c.iconst(1); c.ixor(); }
                return VarType.BOOLEAN;
            }
            String op = opsName(b.op);
            if (op == null) throw new Unsupported("binary " + b.op);
            c.invokestatic(OPS, op, "(" + OBJ_D + OBJ_D + ")" + OBJ_D);
            return VarType.DYN;
        }
        // ---- logic ----
        if (e instanceof IntCmp || e instanceof LongCmp || e instanceof DoubleCmp || e instanceof Not
                || (e instanceof Logical lg && lg.type == VarType.BOOLEAN)) {
            Label lTrue = c.label(), lEnd = c.label();
            jump(e, lTrue, true);
            c.iconst(0);
            c.goTo(lEnd);
            c.mark(lTrue);
            c.iconst(1);
            c.mark(lEnd);
            return VarType.BOOLEAN;
        }
        if (e instanceof Logical lg) {
            // the value is the last evaluated operand
            int t = temp(VarType.DYN);
            Label lEnd = c.label();
            expr(lg.left, VarType.DYN);
            c.astore(t);
            c.aload(t);
            c.invokestatic(OPS, "truthy", "(" + OBJ_D + ")Z");
            if (lg.isAnd) c.ifeq(lEnd); else c.ifne(lEnd);
            expr(lg.right, VarType.DYN);
            c.astore(t);
            c.mark(lEnd);
            c.aload(t);
            release(VarType.DYN, t);
            return VarType.DYN;
        }
        if (e instanceof ToSlot ts) {
            // a dyn value into a typed location (Ops.slot*: null -> default, no truthiness). When the value is
            // proven to be of the type (unboxing, a guarded array read) it is stored as is
            VarType sat = st(ts.value), spt = ts.type;
            boolean fits = sat == spt || spt == VarType.LONG && sat == VarType.INT
                    || spt == VarType.DOUBLE && (sat == VarType.INT || sat == VarType.LONG);
            if (fits) { expr(ts.value, spt); return spt; }
            expr(ts.value, VarType.DYN);
            switch (spt) {
                case INT -> c.invokestatic(OPS, "slotInt", "(" + OBJ_D + ")I");
                case LONG -> c.invokestatic(OPS, "slotLong", "(" + OBJ_D + ")J");
                case DOUBLE -> c.invokestatic(OPS, "slotDouble", "(" + OBJ_D + ")D");
                default -> c.invokestatic(OPS, "slotBool", "(" + OBJ_D + ")Z");
            }
            return spt;
        }
        if (e instanceof Ternary t) {
            // needed as a value: the node's own type, as Tier 0 has it - `c ? 1 : 2.5` is a double on both
            // branches (1.0), whatever unboxing learnt about the operands
            VarType rt = want == VarType.STRING || want == VarType.DYN
                    ? (t.type.isPrimitive() ? t.type : VarType.DYN) : want;
            Label lElse = c.label(), lEnd = c.label();
            jump(t.cond, lElse, false);
            expr(t.a, rt);
            c.goTo(lEnd);
            c.mark(lElse);
            expr(t.b, rt);
            c.mark(lEnd);
            return rt;
        }
        // ---- access ----
        if (e instanceof Member m) {
            // under a guard the slot representation is proven: read the array directly, in the slot's kind
            VarType sk = specType(m);
            if (sk != null) {
                specGet(m);
                return sk;
            }
            expr(m.obj, VarType.DYN);
            if (GETSITE && !noSpec) {
                // polymorphic inline cache per site (GetSite): a layer per receiver class and shape
                VarType rt = switch (want) { case INT, LONG, DOUBLE, BOOLEAN -> want; default -> VarType.DYN; };
                String rd = switch (rt) { case INT -> "I"; case LONG -> "J"; case DOUBLE -> "D"; case BOOLEAN -> "Z"; default -> OBJ_D; };
                c.invokedynamic(cb.bootstrap(Jit.PKG + "GetSite", "bootstrap", REGION_GUARD_BSM, cb.cp.integer(konstIndex(m.cache))),
                        "get", "(" + OBJ_D + ")" + rd);
                return rt;
            }
            konst(m.cache, FC);
            // typed field read - no boxing when the surrounding expression is primitive
            switch (want) {
                case INT -> { c.invokestatic(OPS, "memberGetInt", "(" + OBJ_D + "L" + FC + ";)I"); return VarType.INT; }
                case LONG -> { c.invokestatic(OPS, "memberGetLong", "(" + OBJ_D + "L" + FC + ";)J"); return VarType.LONG; }
                case DOUBLE -> { c.invokestatic(OPS, "memberGetDouble", "(" + OBJ_D + "L" + FC + ";)D"); return VarType.DOUBLE; }
                case BOOLEAN -> { c.invokestatic(OPS, "memberGetBool", "(" + OBJ_D + "L" + FC + ";)Z"); return VarType.BOOLEAN; }
                default -> { c.invokestatic(OPS, "memberGet", "(" + OBJ_D + "L" + FC + ";)" + OBJ_D); return VarType.DYN; }
            }
        }
        if (e instanceof Index ix) {
            ARecv ar = arrOf(ix);
            if (ar != null) {   // under a guard: the array is a JArray of this kind, read the element typed
                if (RAW_ARRAY) return rawArrayRead(ar, ix.key);
                c.aload(ar.objLocal);
                c.checkcast(JARRAY);
                expr(ix.key, VarType.INT);
                switch (ar.kind) {
                    case INT -> { c.invokevirtual(JARRAY, "getInt", "(I)I"); return VarType.INT; }
                    case DOUBLE -> { c.invokevirtual(JARRAY, "getDouble", "(I)D"); return VarType.DOUBLE; }
                    default -> { c.invokevirtual(JARRAY, "getBool", "(I)Z"); return VarType.BOOLEAN; }
                }
            }
            String da = st(ix.key) == VarType.INT ? directArray(ix.obj, want) : null;
            if (da != null) {   // the element type is known: an ordinary aaload/iaload, as in Java
                expr(ix.obj, VarType.DYN);
                c.checkcast(da);
                expr(ix.key, VarType.INT);
                switch (want) {
                    case INT -> { c.iaload(); return VarType.INT; }
                    case LONG -> { c.laload(); return VarType.LONG; }
                    case DOUBLE -> { c.daload(); return VarType.DOUBLE; }
                    default -> { c.baload(); return VarType.BOOLEAN; }
                }
            }
            expr(ix.obj, VarType.DYN);
            if (st(ix.key) == VarType.INT) {
                expr(ix.key, VarType.INT);
                switch (want) { // typed read from a specialized array
                    case INT -> { c.invokestatic(OPS, "indexAsInt", "(" + OBJ_D + "I)I"); return VarType.INT; }
                    case DOUBLE -> { c.invokestatic(OPS, "indexAsDouble", "(" + OBJ_D + "I)D"); return VarType.DOUBLE; }
                    case BOOLEAN -> { c.invokestatic(OPS, "indexAsBool", "(" + OBJ_D + "I)Z"); return VarType.BOOLEAN; }
                    default -> c.invokestatic(OPS, "indexInt", "(" + OBJ_D + "I)" + OBJ_D);
                }
            } else {
                expr(ix.key, VarType.DYN);
                c.invokestatic(OPS, "index", "(" + OBJ_D + OBJ_D + ")" + OBJ_D);
            }
            return VarType.DYN;
        }
        // ---- calls ----
        if (e instanceof Call call) return call(call, want);
        if (e instanceof BuiltinCall bc) {
            if (bc.kind >= BuiltinCall.DYN_INT) {
                VarType to = switch (bc.kind) { case BuiltinCall.DYN_INT -> VarType.INT; case BuiltinCall.DYN_LONG -> VarType.LONG; default -> VarType.DOUBLE; };
                VarType from = st(bc.arg);
                if (from.isNumeric()) {   // typed under a guard or by unboxing: a cast
                    expr(bc.arg, from);
                    convertNum(from, to);
                    return to;
                }
                expr(bc.arg, VarType.DYN);
                String name = switch (to) { case INT -> "toInt"; case LONG -> "toLong"; default -> "toDouble"; };
                c.invokestatic("me/padej/jumper/runtime/Builtins", name, "(" + OBJ_D + ")" + OBJ_D);
                return VarType.DYN;
            }
            if (bc.kind >= BuiltinCall.TO_INT) {   // numeric type cast: a single instruction
                VarType from = bc.arg.type;
                expr(bc.arg, from);
                switch (bc.kind) {
                    case BuiltinCall.TO_INT -> { if (from == VarType.LONG) c.l2i(); else if (from == VarType.DOUBLE) c.d2i(); }
                    case BuiltinCall.TO_LONG -> { if (from == VarType.INT) c.i2l(); else if (from == VarType.DOUBLE) c.d2l(); }
                    default -> { if (from == VarType.INT) c.i2d(); else if (from == VarType.LONG) c.l2d(); }
                }
                return bc.type;
            }
            expr(bc.arg, VarType.DYN);
            switch (bc.kind) {
                case BuiltinCall.LEN -> { c.invokestatic(OPS, "len", "(" + OBJ_D + ")I"); return VarType.INT; }
                case BuiltinCall.STR -> c.invokestatic(OPS, "str", "(" + OBJ_D + ")Ljava/lang/String;");
                default -> c.invokestatic(OPS, "typeName", "(" + OBJ_D + ")Ljava/lang/String;");
            }
            return VarType.DYN;
        }
        if (e instanceof MethodCall mc) {
            if (mc.recvClass != null) {
                VarType r = directMethodCall(mc, want);
                if (r != null) return r;
            }
            VarType pm = pureMathType(mc);
            if (pm != null) return pureMathCall(mc, pm);
            // invokedynamic: the receiver and the arguments are separate parameters, no Object[];
            // an argument with a static primitive type is passed as that primitive - no box for it,
            // and the site's guard does not have to look at it (Indy.install)
            expr(mc.obj, VarType.DYN);
            StringBuilder d = new StringBuilder("(").append(OBJ_D);
            for (Expr a : mc.args) {
                VarType at = st(a);
                if (at == VarType.INT || at == VarType.LONG || at == VarType.DOUBLE || at == VarType.BOOLEAN) {
                    expr(a, at);
                    d.append(desc(at));
                } else {
                    expr(a, VarType.DYN);
                    d.append(OBJ_D);
                }
            }
            d.append(')').append(OBJ_D);
            c.invokedynamic(indyBsm(), mc.name, d.toString());
            return VarType.DYN;
        }
        if (e instanceof Classes.SuperCall sc) {
            konst(sc, "me/padej/jumper/ast/Classes$SuperCall");
            expr(sc.self, VarType.DYN);
            argsArray(sc.args);
            c.invokevirtual("me/padej/jumper/ast/Classes$SuperCall", "invoke", "(" + OBJ_D + "[" + OBJ_D + ")" + OBJ_D);
            return VarType.DYN;
        }
        if (e instanceof Classes.SuperMethodCall sm) {
            konst(sm, "me/padej/jumper/ast/Classes$SuperMethodCall");
            expr(sm.self, VarType.DYN);
            argsArray(sm.args);
            c.invokevirtual("me/padej/jumper/ast/Classes$SuperMethodCall", "invoke", "(" + OBJ_D + "[" + OBJ_D + ")" + OBJ_D);
            return VarType.DYN;
        }
        if (e instanceof NewArray na) {
            konst(na, "me/padej/jumper/ast/Exprs$NewArray");
            if (na.items == null) {
                expr(na.size, VarType.INT);
                c.invokevirtual("me/padej/jumper/ast/Exprs$NewArray", "create", "(I)" + OBJ_D);
            } else {
                objectArray(na.items, na.items.length);
                c.checkcast("[" + OBJ_D);
                c.invokevirtual("me/padej/jumper/ast/Exprs$NewArray", "fill", "([" + OBJ_D + ")" + OBJ_D);
            }
            return VarType.DYN;
        }
        if (e instanceof New n) {
            if (n.staticClass != null && directNew(n)) return VarType.DYN;
            // invokedynamic: the class and the arguments are separate parameters, a direct Java constructor / JClass.instantiate
            expr(n.cls, VarType.DYN);
            StringBuilder d = new StringBuilder("(").append(OBJ_D);
            for (Expr a : n.args) { expr(a, VarType.DYN); d.append(OBJ_D); }
            d.append(')').append(OBJ_D);
            c.invokedynamic(indyNewBsm(), "new", d.toString());
            return VarType.DYN;
        }
        // ---- value constructors ----
        if (e instanceof Lambda l) { newClosure(l.fn); return VarType.DYN; }
        if (e instanceof ArrayLit a) {
            int t = temp(VarType.DYN);
            objectArray(a.items, Math.max(4, a.items.length));
            c.astore(t);
            c.newObj(JARRAY);
            c.dup();
            c.aload(t);
            c.checkcast("[" + OBJ_D);
            c.iconst(a.items.length);
            c.invokespecial(JARRAY, "<init>", "([" + OBJ_D + "I)V");
            release(VarType.DYN, t);
            return VarType.DYN;
        }
        if (e instanceof TableLit tl) {
            if (tl.hasShape() && staticLiteral(tl)) {
                // every value has a statically known representation: build the typed table directly
                // (Plain + long[] instead of Object[] of boxes + Plain + long[] via typeSlots)
                int n = tl.values.length;
                VarType[] reps = new VarType[n];
                boolean anyPrim = false, anyRef = false;
                for (int i = 0; i < n; i++) {
                    VarType t = st(tl.values[i]);
                    if (t.isPrimitive()) { reps[i] = t; anyPrim = true; } else anyRef = true;
                }
                me.padej.jumper.runtime.Shape shape = tl.shape().withReps(reps);
                LayoutGen.Layout lay = LayoutGen.of(shape);
                if (lay != null) {
                    // the shape has a layout class: one allocation, the values straight into the fields
                    // (arguments in slot order = literal order, so side effects keep their order)
                    konst(shape, SHAPE);
                    for (int i = 0; i < n; i++) {
                        if (reps[i] != null) { expr(tl.values[i], reps[i]); toBits(reps[i]); }
                        else expr(tl.values[i], VarType.DYN);
                    }
                    // through the layout's makeSite: it switches from the Lean class to the Full one when
                    // a table of this layout first outgrows its fields (LayoutGen.Layout.grow)
                    c.invokedynamic(cb.bootstrap(Jit.PKG + "LayoutGen", "bootstrapMake", REGION_GUARD_BSM,
                            cb.cp.integer(konstIndex(shape))), "make", lay.makeDesc);
                    return VarType.DYN;
                }
                int pl = temp(VarType.DYN), vl = temp(VarType.DYN);
                if (anyPrim) { c.iconst(n); c.newarrayLong(); } else c.aconstNull();
                c.astore(pl);
                if (anyRef) { c.iconst(n); c.anewarray(OBJ); } else c.aconstNull();
                c.astore(vl);
                for (int i = 0; i < n; i++) {   // in literal order: the values may have side effects
                    if (reps[i] != null) {
                        c.aload(pl);
                        c.checkcast("[J");
                        c.iconst(i);
                        expr(tl.values[i], reps[i]);
                        toBits(reps[i]);
                        c.lastore();
                    } else {
                        c.aload(vl);
                        c.checkcast("[" + OBJ_D);
                        c.iconst(i);
                        expr(tl.values[i], VarType.DYN);
                        c.aastore();
                    }
                }
                c.newObj(JTABLE + "$Plain");
                c.dup();
                konst(shape, SHAPE);
                c.aload(pl);
                c.checkcast("[J");
                c.aload(vl);
                c.checkcast("[" + OBJ_D);
                c.invokespecial(JTABLE + "$Plain", "<init>", "(L" + SHAPE + ";[J[" + OBJ_D + ")V");
                release(VarType.DYN, pl);
                release(VarType.DYN, vl);
            } else if (tl.hasShape() && LITSITE) {
                // values without a static type: a per-site cache of the values' kind patterns (LitSite)
                StringBuilder d = new StringBuilder("(");
                for (Expr v : tl.values) { expr(v, VarType.DYN); d.append(OBJ_D); }
                c.invokedynamic(cb.bootstrap(Jit.PKG + "LitSite", "bootstrap", REGION_GUARD_BSM, cb.cp.integer(konstIndex(tl))),
                        "lit", d.append(')').append(OBJ_D).toString());
            } else if (tl.hasShape()) {
                konst(tl, "me/padej/jumper/ast/Exprs$TableLit");
                objectArray(tl.values, Math.max(4, tl.values.length));
                c.invokevirtual("me/padej/jumper/ast/Exprs$TableLit", "build", "([" + OBJ_D + ")" + OBJ_D);
            } else {
                int t = temp(VarType.DYN);
                c.newObj(JTABLE + "$Plain");
                c.dup();
                c.iconst(tl.keys.length);
                c.invokespecial(JTABLE + "$Plain", "<init>", "(I)V");
                c.astore(t);
                for (int i = 0; i < tl.keys.length; i++) {
                    c.aload(t);
                    c.checkcast(JTABLE);
                    expr(tl.keys[i], VarType.DYN);
                    expr(tl.values[i], VarType.DYN);
                    c.invokevirtual(JTABLE, "put", "(" + OBJ_D + OBJ_D + ")V");
                }
                c.aload(t);
                release(VarType.DYN, t);
            }
            return VarType.DYN;
        }
        throw new Unsupported("expr " + e.getClass().getSimpleName());
    }

    private void newClosure(FunctionNode inner) {
        if (frameLocal < 0) throw new Unsupported("closure without frame");
        c.newObj(SF);
        c.dup();
        konst(inner, FN);
        c.aload(frameLocal);
        c.invokespecial(SF, "<init>", "(L" + FN + ";L" + FRAME + ";)V");
    }

    /** Object[] from expressions (all as Object), length n >= items.length. */
    /**
     * Can the typed shape of this literal be decided at compile time? Yes when every value is
     * either of a static primitive type (it will be a box of exactly that kind at run time, which
     * is what typeSlots would see) or provably a non-null, non-number reference (a string or
     * nested literal). A `dyn` value could be a number or null at run time and would give the
     * table a different shape than the interpreter's path - then the ordinary build is used.
     */
    private boolean staticLiteral(TableLit tl) {
        if (!SPEC || unbox == null) return false;
        for (Expr v : tl.values) {
            VarType t = st(v);
            if (t.isPrimitive()) continue;
            if (v instanceof Literal l && l.value instanceof String) continue;
            if (v instanceof TableLit inner && inner.hasShape()) continue;
            if (v instanceof ArrayLit) continue;
            return false;
        }
        return true;
    }

    private void objectArray(Expr[] items, int n) {
        c.iconst(n);
        c.anewarray(OBJ);
        for (int i = 0; i < items.length; i++) {
            c.dup();
            c.iconst(i);
            expr(items[i], VarType.DYN);
            c.aastore();
        }
    }

    private void argsArray(Expr[] args) {
        objectArray(args, args.length);
    }

    /**
     * One argument of a typed call, converted as Tier 0 converts a parameter (FunctionNode.interpret, and
     * this class's own invoke entry): a statically matching or widening argument as is; anything else as a value
     * through Ops.arg* - null becomes 0 / 0.0 / false, a boolean parameter takes only a boolean
     * (convert() would unbox strictly and apply truthiness, which is the semantics of other places).
     */
    private void arg(Expr a, VarType pt) {
        if (pt == VarType.STRING) {
            expr(a, VarType.DYN);
            c.invokestatic(OPS, "toStringSlot", "(" + OBJ_D + ")" + OBJ_D);
            return;
        }
        if (!pt.isPrimitive()) { expr(a, pt); return; }
        VarType at = st(a);
        boolean fits = at == pt || pt == VarType.LONG && at == VarType.INT
                || pt == VarType.DOUBLE && (at == VarType.INT || at == VarType.LONG);
        if (fits) { expr(a, pt); return; }
        expr(a, VarType.DYN);
        switch (pt) {
            case INT -> c.invokestatic(OPS, "argInt", "(" + OBJ_D + ")I");
            case LONG -> c.invokestatic(OPS, "argLong", "(" + OBJ_D + ")J");
            case DOUBLE -> c.invokestatic(OPS, "argDouble", "(" + OBJ_D + ")D");
            default -> c.invokestatic(OPS, "argBool", "(" + OBJ_D + ")Z");
        }
    }

    /** Can this call use the target's int entry: every int-entry parameter gets a statically int argument. */
    private boolean useSpec(Call call, FunctionNode target) {
        boolean[] sp = target.specInt;
        if (sp == null || target.specDesc == null || call.args.length != target.nparams) return false;
        for (int i = 0; i < sp.length; i++) if (sp[i] && st(call.args[i]) != VarType.INT) return false;
        return true;
    }

    /** Arguments for bodyI: ints for its int parameters, the rest as for body. */
    private void specArgs(Call call, FunctionNode target) {
        for (int i = 0; i < call.args.length; i++) {
            VarType pt = target.specInt[i] ? VarType.INT : target.paramTypes[i];
            arg(call.args[i], pt);
        }
    }

    /** Call arguments in the parameter types of the target (String - as Object with a check). */
    private void typedArgs(Call call, FunctionNode target) {
        for (int i = 0; i < call.args.length; i++) {
            arg(call.args[i], target.paramTypes[i]);
        }
    }

    private VarType call(Call call, VarType want) {
        FunctionNode target = call.target;
        if (target == null || target.jitFailed) {
            expr(call.callee, VarType.DYN);
            argsArray(call.args);
            c.invokestatic(OPS, "call", "(" + OBJ_D + "[" + OBJ_D + ")" + OBJ_D);
            return VarType.DYN;
        }
        String targetCls = Jit.className(target);
        Jit.compile(target); // with mutual recursion returns at once (in progress) - the class will be defined later
        VarType rt = want == VarType.STRING ? VarType.DYN : want;

        // Fast path: the target is fixed and its body is static - call directly, without reading
        // the variable and without a single check. This is what it was all for: C2 sees
        // a constant target and inlines the body into the caller.
        if (directCall(call)) {
            if (target.jitFailed) {
                // The static-body decision was already made on the assumption that this call is direct,
                // and there is no rollback: we stay in Tier 0 entirely, together with the target.
                throw new Unsupported("target " + target.name + " failed to compile");
            }
            if (useSpec(call, target)) {
                specArgs(call, target);
                c.invokestatic(targetCls, "bodyI", target.specDesc);
                convert(target.specReturn, rt);
                return rt;
            }
            typedArgs(call, target);
            c.invokestatic(targetCls, "body", bodyDesc(target));
            convert(target.returnType, rt);
            return rt;
        }

        // A top-level name in REPL/ScriptEngine: there is no direct binding (the host may replace the name
        // through engine.put), and previously every call - including every step of a recursion - re-read
        // the cell and checked that it still held the same function; C2 does not inline that, and fib_rec in the engine
        // ran 1.8x slower than in Interpreter. Now the call site is an invokedynamic whose target is
        // the function body under the cell's SwitchPoint (Indy.CellSite): there is no guard on the hot path at all,
        // and a write to the cell invalidates the switch point and moves the site to the generic path.
        boolean targetStatic = target == fn ? selfDirect : target.jitStaticBody;
        if (CELLSITE && targetStatic && call.callee instanceof GlobalGet g && g.cell != null) {
            // the site's type says which entry it links to: bodyI when the int arguments allow it
            // (arguments only: the result keeps body's return type, because the name may be rebound to a
            // function returning anything, and the site's generic path must be able to return that)
            boolean spec = useSpec(call, target);
            if (spec) specArgs(call, target); else typedArgs(call, target);
            String desc = spec ? target.specDesc.substring(0, target.specDesc.indexOf(')') + 1) + desc(target.returnType) : bodyDesc(target);
            c.invokedynamic(cb.bootstrap(Jit.PKG + "Indy", "bootstrapCell",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;II)Ljava/lang/invoke/CallSite;",
                    cb.cp.integer(konstIndex(g.cell)), cb.cp.integer(konstIndex(target))), "call", desc);
            convert(target.returnType, rt);
            return rt;
        }

        // Otherwise - a check that the variable still holds the same function. A CompiledFunction
        // instance is needed here only if the target body is not static.
        int callee = temp(VarType.DYN), cf = temp(VarType.DYN);
        Label lSlow = c.label(), lEnd = c.label();
        expr(call.callee, VarType.DYN);
        c.astore(callee);
        c.aload(callee);
        c.instanceOf(SF);
        c.ifeq(lSlow);
        c.aload(callee);
        c.checkcast(SF);
        c.getfield(SF, "node", "L" + FN + ";");
        konst(target, FN);
        c.if_acmpne(lSlow);
        if (!targetStatic) {
            c.aload(callee);
            c.checkcast(SF);
            c.invokevirtual(SF, "compiled", "()L" + CF + ";");
            c.astore(cf);
            c.aload(cf);
            c.ifnull(lSlow);
            c.aload(cf);
            c.checkcast(targetCls);
        }
        typedArgs(call, target);
        if (targetStatic) c.invokestatic(targetCls, "body", bodyDesc(target));
        else c.invokevirtual(targetCls, "body", bodyDesc(target));
        convert(target.returnType, rt);
        c.goTo(lEnd);
        c.mark(lSlow);
        c.aload(callee);
        argsArray(call.args);
        c.invokestatic(OPS, "call", "(" + OBJ_D + "[" + OBJ_D + ")" + OBJ_D);
        convert(VarType.DYN, rt);
        c.mark(lEnd);
        release(VarType.DYN, callee);
        release(VarType.DYN, cf);
        return rt;
    }

    /**
     * v.m(args) with the class of v statically known: guard "an instance of exactly this class" (a descendant
     * with an override goes to invokedynamic), then a direct typed call of body() of the compiled method.
     */
    private VarType directMethodCall(MethodCall mc, VarType want) {
        Classes.ClassNode cn = mc.recvClass;
        FunctionNode target = cn.methodNode(mc.name);
        int idx = cn.methodIndex(mc.name);
        if (target == null || idx < 0 || target.nparams != mc.args.length + 1) return null;
        Jit.compile(target);
        if (target.jitFailed) return null;
        String targetCls = Jit.className(target);
        VarType rt = want == VarType.STRING ? VarType.DYN : want;
        int recv = temp(VarType.DYN), cf = temp(VarType.DYN);
        Label lSlow = c.label(), lEnd = c.label();
        expr(mc.obj, VarType.DYN);
        c.astore(recv);
        String ic = InstanceGen.classFor(cn) == null ? null : InstanceGen.nameFor(cn);
        if (ic != null) {
            // an instance of exactly this class is an instance of exactly its generated class (one per
            // ClassNode, subclasses get their own): a klass compare instead of cls() + JClass.node + K[i],
            // which on a recursive method (binary_trees check()) was paid twice per node
            c.aload(recv);
            c.instanceOf(ic);
            c.ifeq(lSlow);
            c.aload(recv);
            c.invokevirtual("java/lang/Object", "getClass", "()Ljava/lang/Class;");
            c.ldcClass(ic);
            c.if_acmpne(lSlow);
        } else {
            c.aload(recv);
            c.instanceOf(JTABLE);
            c.ifeq(lSlow);
            c.aload(recv);
            c.checkcast(JTABLE);
            c.invokevirtual(JTABLE, "cls", "()Lme/padej/jumper/runtime/JClass;");
            c.astore(cf);             // temporarily: the receiver's JClass
            c.aload(cf);
            c.ifnull(lSlow);
            c.aload(cf);
            c.checkcast("me/padej/jumper/runtime/JClass");
            c.getfield("me/padej/jumper/runtime/JClass", "node", OBJ_D);
            konst(cn, CN);
            c.if_acmpne(lSlow);
        }
        if (!target.jitStaticBody) {
            c.iconst(idx);
            c.aload(recv);
            c.invokestatic(Jit.PKG + "Indy", "compiledMethod", "(I" + OBJ_D + ")L" + CF + ";");
            c.astore(cf);
            c.aload(cf);
            c.ifnull(lSlow);
            c.aload(cf);
            c.checkcast(targetCls);
        }
        c.aload(recv);
        for (int i = 0; i < mc.args.length; i++) {
            arg(mc.args[i], target.paramTypes[i + 1]);
        }
        if (target.jitStaticBody) c.invokestatic(targetCls, "body", bodyDesc(target));
        else c.invokevirtual(targetCls, "body", bodyDesc(target));
        convert(target.returnType, rt);
        c.goTo(lEnd);
        c.mark(lSlow);
        c.aload(recv);
        StringBuilder d = new StringBuilder("(").append(OBJ_D);
        for (Expr a : mc.args) { expr(a, VarType.DYN); d.append(OBJ_D); }
        d.append(')').append(OBJ_D);
        c.invokedynamic(indyBsm(), mc.name, d.toString());
        convert(VarType.DYN, rt);
        c.mark(lEnd);
        release(VarType.DYN, recv);
        release(VarType.DYN, cf);
        return rt;
    }

    /**
     * new Vec(args) with a statically known class: guard "the value is exactly this class", then
     * the instance is created in place, the initializers and the constructor are called directly (typed body()).
     */
    private boolean directNew(New n) {
        Classes.ClassNode cn = n.staticClass;
        if (!cn.bodyParsed || !cn.layoutKnown()) return false;
        FunctionNode ctor = cn.ctorNode;
        if (ctor != null) {
            if (ctor.nparams != n.args.length + 1) return false;
            Jit.compile(ctor);
            if (ctor.jitFailed) return false;
        }
        String JC = "me/padej/jumper/runtime/JClass";
        int k = temp(VarType.DYN), t = temp(VarType.DYN), cf = temp(VarType.DYN);
        Label lSlow = c.label(), lGeneric = c.label(), lEnd = c.label();
        if (CELLSITE && n.cls instanceof GlobalGet g && g.cell != null) {
            // the class behind a top-level name: a constant while the name is not rebound (Indy.ValueSite)
            c.invokedynamic(cb.bootstrap(Jit.PKG + "Indy", "bootstrapCellValue", REGION_GUARD_BSM, cb.cp.integer(konstIndex(g.cell))),
                    "cls", "()" + OBJ_D);
        } else expr(n.cls, VarType.DYN);
        c.astore(k);
        c.aload(k);
        c.instanceOf(JC);
        c.ifeq(lSlow);
        c.aload(k);
        c.checkcast(JC);
        c.getfield(JC, "node", OBJ_D);
        konst(cn, CN);
        c.if_acmpne(lSlow);
        // instance: a generated class with real fields - one allocation instead of two
        String ic = InstanceGen.classFor(cn) == null ? null : InstanceGen.nameFor(cn);
        c.newObj(ic != null ? ic : JTABLE + "$Plain");
        c.dup();
        c.aload(k);
        c.checkcast(JC);
        c.invokespecial(ic != null ? ic : JTABLE + "$Plain", "<init>", "(L" + JC + ";)V");
        c.astore(t);
        if (cn.hasFieldInits()) {
            c.aload(k);
            c.checkcast(JC);
            c.aload(t);
            c.checkcast(JTABLE);
            c.invokevirtual(JC, "runFieldInits", "(L" + JTABLE + ";)V");
        }
        if (ctor == null) {
            c.mark(lGeneric); // unreachable otherwise, but the label is needed below
            c.aload(k);
            c.checkcast(JC);
            c.aload(t);
            c.checkcast(JTABLE);
            argsArray(n.args);
            c.invokevirtual(JC, "runCtor", "(L" + JTABLE + ";[" + OBJ_D + ")V");
        } else {
            if (cn.hasParent && !cn.ctorCallsSuper) {
                c.aload(k);
                c.checkcast(JC);
                c.aload(t);
                c.checkcast(JTABLE);
                c.invokevirtual(JC, "runParentCtor", "(L" + JTABLE + ";)V");
            }
            String ctorCls = Jit.className(ctor);
            // A static constructor body needs no CompiledFunction instance: the class value was just
            // checked to be this ClassNode's, so its constructor is ctorNode, and the body is callable
            // as is. Before, every `new` asked Indy.compiledCtor for an instance only to null-check it.
            if (!ctor.jitStaticBody) {
                c.aload(k);
                c.invokestatic(Jit.PKG + "Indy", "compiledCtor", "(" + OBJ_D + ")L" + CF + ";");
                c.astore(cf);
                c.aload(cf);
                c.ifnull(lGeneric);
            }
            if (!ctor.jitStaticBody) {
                c.aload(cf);
                c.checkcast(ctorCls);
            }
            c.aload(t);
            for (int i = 0; i < n.args.length; i++) {
                arg(n.args[i], ctor.paramTypes[i + 1]);
            }
            if (ctor.jitStaticBody) c.invokestatic(ctorCls, "body", bodyDesc(ctor));
            else c.invokevirtual(ctorCls, "body", bodyDesc(ctor));
            pop(ctor.returnType);
            c.aload(t);
            c.goTo(lEnd);
            c.mark(lGeneric); // the constructor is not compiled: generic call
            c.aload(k);
            c.checkcast(JC);
            c.aload(t);
            c.checkcast(JTABLE);
            argsArray(n.args);
            c.invokevirtual(JC, "runCtor", "(L" + JTABLE + ";[" + OBJ_D + ")V");
        }
        c.aload(t);
        c.goTo(lEnd);
        c.mark(lSlow);
        c.aload(k);
        StringBuilder d = new StringBuilder("(").append(OBJ_D);
        for (Expr a : n.args) { expr(a, VarType.DYN); d.append(OBJ_D); }
        d.append(')').append(OBJ_D);
        c.invokedynamic(indyNewBsm(), "new", d.toString());
        c.mark(lEnd);
        release(VarType.DYN, k);
        release(VarType.DYN, t);
        release(VarType.DYN, cf);
        return true;
    }

    private void pop(VarType t) {
        c.pop1();
    }

    // ================= conditions =================

    /** Jump to target if the truthiness of e == whenTrue; otherwise fall through. */
    private void jump(Expr e, Label target, boolean whenTrue) {
        if (e instanceof BoolLit b) {
            if (b.v == whenTrue) c.goTo(target);
            return;
        }
        if (e instanceof Not n) { jump(n.e, target, !whenTrue); return; }
        if (e instanceof Logical lg) {
            if (lg.isAnd) {
                if (whenTrue) {
                    Label skip = c.label();
                    jump(lg.left, skip, false);
                    jump(lg.right, target, true);
                    c.mark(skip);
                } else {
                    jump(lg.left, target, false);
                    jump(lg.right, target, false);
                }
            } else {
                if (whenTrue) {
                    jump(lg.left, target, true);
                    jump(lg.right, target, true);
                } else {
                    Label skip = c.label();
                    jump(lg.left, skip, true);
                    jump(lg.right, target, false);
                    c.mark(skip);
                }
            }
            return;
        }
        if (e instanceof IntCmp k) {
            expr(k.l, VarType.INT);
            expr(k.r, VarType.INT);
            int op = whenTrue ? k.op : negate(k.op);
            switch (op) {
                case 0 -> c.if_icmplt(target); case 1 -> c.if_icmple(target); case 2 -> c.if_icmpgt(target);
                case 3 -> c.if_icmpge(target); case 4 -> c.if_icmpeq(target); case 5 -> c.if_icmpne(target);
                default -> throw new IllegalStateException();
            }
            return;
        }
        if (e instanceof LongCmp k) {
            expr(k.l, VarType.LONG);
            expr(k.r, VarType.LONG);
            c.lcmp();
            ifZero(whenTrue ? k.op : negate(k.op), target);
            return;
        }
        if (e instanceof DoubleCmp k) {
            expr(k.l, VarType.DOUBLE);
            expr(k.r, VarType.DOUBLE);
            // NaN: < and <= need dcmpg (NaN -> 1 -> condition false), > and >= need dcmpl;
            // the inversion is applied to the integer comparison result so that the NaN semantics is preserved
            if (k.op == 0 || k.op == 1) c.dcmpg(); else c.dcmpl();
            ifZero(whenTrue ? k.op : negate(k.op), target);
            return;
        }
        expr(e, VarType.BOOLEAN);
        if (whenTrue) c.ifne(target); else c.ifeq(target);
    }

    private static int negate(int op) {
        return switch (op) {
            case 0 -> 3; case 1 -> 2; case 2 -> 1; case 3 -> 0; case 4 -> 5; case 5 -> 4;
            default -> throw new IllegalStateException();
        };
    }

    private void ifZero(int op, Label target) {
        switch (op) {
            case 0 -> c.iflt(target); case 1 -> c.ifle(target); case 2 -> c.ifgt(target);
            case 3 -> c.ifge(target); case 4 -> c.ifeq(target); case 5 -> c.ifne(target);
            default -> throw new IllegalStateException();
        }
    }
}
