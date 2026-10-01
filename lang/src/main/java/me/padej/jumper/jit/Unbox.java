package me.padej.jumper.jit;

import me.padej.jumper.ast.Expr;
import me.padej.jumper.ast.Exprs;
import me.padej.jumper.ast.FunctionNode;
import me.padej.jumper.ast.Stmts;
import me.padej.jumper.ast.VarType;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which dynamic local variables Tier 1 may keep unboxed.
 *
 * <p>A `dyn` variable lives in a slot of type Object, so `dyn i = 0; i++` boxes an Integer
 * on every step. On `branch_predictable` that is 20 million iterations and about 640 MB of garbage per run:
 * 173 ms versus 7 ms for the same script with types spelled out - a 24x difference, and it is
 * the largest reserve of the dynamic mode.
 *
 * <p>This does not change the semantics: dynamic Jumper arithmetic on two Integers returns
 * {@code x + y}, i.e. ordinary int addition with wraparound overflow, and division by zero
 * yields {@code JmpError} in both cases. A variable that is always written an int already
 * behaves like an int - we merely stop storing it in a box.
 *
 * <h2>Why the compiler does this, not the parser</h2>
 *
 * The parser decides the slot kind at the declaration, when it has not yet seen the assignments further down.
 * The compiler sees the whole function body.
 *
 * <h2>Why a fixed point is needed</h2>
 *
 * The static type of the expression `c + 1` with `dyn c` is DYN, because `c` itself is DYN.
 * If we assume that `c` is an int, the expression type becomes INT, and the assumption
 * is confirmed. So the types are recomputed under the current assumption until the set of
 * unboxed slots stops changing; any disagreement retracts the assumption,
 * and a retraction may drag neighbours along - hence the loop until stabilization.
 *
 * <p>The result is a <b>side</b> table: the {@code Expr.type} fields are not changed, because the same
 * AST is executed by Tier 0, and replacing the type on a shared node would change its behaviour.
 *
 * <p>Disabled with {@code -Djmp.unbox=0}.
 */
final class Unbox {
    static final boolean ENABLED = me.padej.jumper.runtime.Opts.on("unbox");

    /** Slot -> primitive type it may be kept in; null - stays Object. */
    private final VarType[] slotType;
    /**
     * Slot -> kind of its speculative primitive representation ("dual" slot), or null.
     *
     * <p>A dyn slot that the proof above could not keep unboxed still usually holds numbers:
     * `dyn dx = bi.x - bj.x` is a double whenever the tables are what they look like, but the proof
     * fails because a field read has no static type. Such a slot gets two JVM locals - a primitive
     * of this kind and an Object - plus a flag saying which one is live. Every write decides at
     * run time (a typed source stores the primitive; anything else is checked with instanceof),
     * every read in a guarded expression takes the primitive after one flag test, and the generic
     * path re-boxes. Nothing is assumed, so the semantics cannot change; what changes is that C2
     * sees a branch that is always taken the same way and compiles only that side. The kind is
     * chosen from static evidence (a numeric initializer, arithmetic over table fields), double
     * when a slot is merely used in arithmetic.
     */
    private final VarType[] dualKind;
    /** Slots that appear as operands of arithmetic, comparisons or negation. */
    private final boolean[] arithUse;
    /**
     * Slot -> element kind of the script array it is expected to hold (INT/DOUBLE/BOOLEAN), or null.
     * Evidence: `array(n, 0)` / `array(n, false)` as the initializer, typed writes `a[i] = <int>`.
     * A guess checked by a guard (JArray.kind()) at the entry of a guarded region; under it `a[i]`
     * is a typed read or write, not a box.
     */
    private final VarType[] arrayKind;
    /** Refined node types under the current assumption (compared by reference - nodes are unique). */
    private final Map<Expr, VarType> inferred = new IdentityHashMap<>();
    /** Assignments to a slot: what exactly is written there. */
    private final List<Write> writes = new ArrayList<>();

    private record Write(int slot, Expr value) {}

    private Unbox(int nslots) {
        this.slotType = new VarType[nslots];
        this.dualKind = new VarType[nslots];
        this.arithUse = new boolean[nslots];
        this.arrayKind = new VarType[nslots];
        this.paramDual = new boolean[nslots];
    }

    /** Expected element kind of the script array in a slot, or null. */
    VarType arrayKind(int slot) {
        return arrayKind[slot];
    }

    /** Kind of the dual representation of a slot, or null. */
    VarType dualKind(int slot) {
        return dualKind[slot];
    }

    /** Type of a node taking unboxing into account, or its own static type. */
    VarType typeOf(Expr e) {
        if (e instanceof Exprs.Local l) {
            VarType t = slotType[l.slot];
            if (t != null) return t;
        }
        VarType t = inferred.get(e);
        return t != null ? t : e.type;
    }

    /** Primitive type of a slot, or null. */
    VarType slotType(int slot) {
        return slotType[slot];
    }

    boolean any() {
        for (VarType t : slotType) {
            if (t != null) return true;
        }
        return false;
    }

    /**
     * @param inLocal the slot lives in a JVM local (not captured by a nested function and not in the Frame)
     */
    static Unbox analyze(FunctionNode fn, java.util.function.IntPredicate inLocal) {
        return analyze(fn, inLocal, s -> false);
    }

    /**
     * @param inLocal the slot lives in a JVM local (not captured by a nested function and not in the Frame)
     * @param paramInLocal the slot is a `dyn` parameter held in a JVM local: never unboxed (the body's
     *                     signature fixes it as Object), but it may become dual when the code around it says it is a number
     */
    static Unbox analyze(FunctionNode fn, java.util.function.IntPredicate inLocal, java.util.function.IntPredicate paramInLocal) {
        return analyze(fn, inLocal, paramInLocal, s -> false);
    }

    /**
     * @param intParam the slot is a `dyn` parameter the body being generated receives as an int (the
     *                 {@code bodyI} entry): it is analysed like a `dyn` local initialized with an int, and
     *                 the entry exists only if the analysis keeps it an int
     */
    static Unbox analyze(FunctionNode fn, java.util.function.IntPredicate inLocal, java.util.function.IntPredicate paramInLocal,
                         java.util.function.IntPredicate intParam) {
        Unbox u = new Unbox(fn.nslots);
        if (!ENABLED) return u;

        // parameters stay as they are: their type is fixed by the signature of the compiled body
        boolean[] banned = new boolean[fn.nslots];
        for (int i = 0; i < fn.nparams; i++) {
            int s = fn.paramIndex[i];
            if (s >= banned.length || fn.paramTypes[i].isPrimitive()) continue;
            if (fn.paramTypes[i] == VarType.DYN && intParam.test(s)) {
                u.writes.add(new Write(s, new me.padej.jumper.ast.Prims.IntLit(0, fn.line)));   // the argument
                continue;
            }
            banned[s] = true;
        }
        // (a primitive parameter's paramIndex is an index into p[], not a slot: nothing to ban in slots[] for it)
        u.body = fn.body;
        u.collect(fn.body, banned, 0);

        // The ban must land in banned BEFORE settle(): it is settle that fills slotType,
        // and striking out of an empty table is pointless. That is exactly where I got it wrong: a variable
        // captured by a closure was unboxed although it lives in the Frame, and the whole function silently
        // fell through to Tier 0 - the tests did not see it, only a measurement caught it.
        for (int s = 0; s < fn.nslots; s++) {
            if (!inLocal.test(s)) banned[s] = true;
        }
        u.settle(banned);
        for (int s = 0; s < fn.nslots; s++) {
            if (banned[s]) u.slotType[s] = null;
        }
        if (DUAL) {
            u.chooseDual(banned);
            for (int i = 0; i < fn.nparams; i++) {
                int s = fn.paramIndex[i];
                if (fn.paramTypes[i] == VarType.DYN && s < fn.nslots && paramInLocal.test(s) && !intParam.test(s)) {
                    // double only: the gain is the guarded region (`b.x += dt * b.vx` in primitives); an int
                    // parameter gains nothing over Ops' own Integer fast path and pays the classification
                    // and the split control flow around every use (call_static_1arg 29 -> 53 ms, fib_rec 77 -> 98)
                    VarType k = u.paramKind(s);
                    if (k == VarType.DOUBLE) { u.dualKind[s] = k; u.paramDual[s] = true; }
                }
            }
        }
        u.arrayKinds(fn.body);
        return u;
    }

    /** Does the code say this `dyn` parameter is an int (it meets only ints in arithmetic)? Evidence for an int entry. */
    static boolean paramIntEvidence(FunctionNode fn, int slot) {
        Unbox u = new Unbox(fn.nslots);
        u.body = fn.body;
        return u.paramKind(slot) == VarType.INT;
    }

    /**
     * INT when every `return` of the body (not of nested functions) returns a value this analysis
     * types as int, and the body cannot fall off its end (the last statement is a return); otherwise null.
     */
    VarType returnKind(FunctionNode fn) {
        if (!(fn.body instanceof Stmts.Block b) || b.stmts.length == 0 || !(b.stmts[b.stmts.length - 1] instanceof Stmts.Return)) return null;
        boolean[] ok = {true};
        returns(fn.body, ok);
        return ok[0] ? VarType.INT : null;
    }

    private void returns(Object node, boolean[] ok) {
        if (node == null || node instanceof FunctionNode || !ok[0]) return;
        if (node instanceof Stmts.Return r && (r.value == null || typeOf(r.value) != VarType.INT)) { ok[0] = false; return; }
        for (Object child : Captures.children(node)) returns(child, ok);
    }

    /** The slot is a dual parameter: never written, so its Object half stays valid and a generic read needs no re-boxing. */
    boolean paramDual(int slot) {
        return paramDual[slot];
    }

    private final boolean[] paramDual;

    /**
     * Kind of a dual parameter: a parameter is never written, so the evidence is what it meets in
     * arithmetic - a table field, a double, a double dual next to it say double; only ints say int.
     * The classification itself happens once, in the prologue (one instanceof per call), and a caller
     * passing something else just leaves the primitive half dead.
     */
    private VarType paramKind(int s) {
        VarType[] k = new VarType[1];
        paramEvidence(body, s, k);
        return k[0];
    }

    private void paramEvidence(Object node, int s, VarType[] k) {
        if (node == null || node instanceof FunctionNode) return;
        if (node instanceof Exprs.Binary b && b.type != VarType.STRING) {
            if (isSlot(b.left, s)) paramMeet(b.right, k);
            if (isSlot(b.right, s)) paramMeet(b.left, k);
        } else if (node instanceof Exprs.CompoundAssign ca && ca.type != VarType.STRING) {
            if (isSlot(ca.value, s)) paramMeet(ca.target, k);
        }
        for (Object child : Captures.children(node)) paramEvidence(child, s, k);
    }

    private static boolean isSlot(Expr e, int s) {
        return e instanceof Exprs.Local l && l.slot == s;
    }

    /** What the other operand says: double beats int; anything untyped says nothing. */
    private void paramMeet(Expr other, VarType[] k) {
        VarType t = null;
        if (other.type == VarType.DOUBLE || other instanceof Exprs.Member m && m.obj instanceof Exprs.Local) t = VarType.DOUBLE;
        else if (other instanceof Exprs.Local l) {
            VarType u = l.slot < slotType.length ? slotType[l.slot] : null;
            VarType d = l.slot < dualKind.length ? dualKind[l.slot] : null;
            if (u == VarType.DOUBLE || d == VarType.DOUBLE) t = VarType.DOUBLE;
            else if (u == VarType.INT || d == VarType.INT) t = VarType.INT;
        } else if (other.type == VarType.INT) t = VarType.INT;
        else if (other instanceof Exprs.Binary b && b.type != VarType.BOOLEAN) {
            VarType[] inner = new VarType[1];
            paramMeet(b.left, inner);
            paramMeet(b.right, inner);
            t = inner[0];
        }
        if (t == VarType.DOUBLE) k[0] = VarType.DOUBLE;
        else if (t == VarType.INT && k[0] == null) k[0] = VarType.INT;
    }

    private void arrayKinds(Object node) {
        if (node == null || node instanceof FunctionNode) return;
        if (node instanceof Stmts.VarDecl d && d.type == VarType.DYN && d.init != null) arrayEvidence(d.slot, d.init);
        else if (node instanceof Exprs.AssignLocal a && a.type == VarType.DYN) arrayEvidence(a.slot, a.value);
        else if (node instanceof Exprs.Assign a && a.target instanceof Exprs.Index ix && ix.obj instanceof Exprs.Local l
                && l.slot < arrayKind.length && a.value.type.isPrimitive() && arrayKind[l.slot] == null) {
            arrayKind[l.slot] = a.value.type;
        }
        for (Object child : Captures.children(node)) arrayKinds(child);
    }

    /** `array(n, fill)` with a typed fill value says what the array will hold. */
    private void arrayEvidence(int slot, Expr init) {
        if (slot >= arrayKind.length) return;
        if (init instanceof Exprs.Call c && c.callee instanceof Exprs.Literal lit && lit.value == me.padej.jumper.runtime.Builtins.ARRAY
                && c.args.length == 2 && c.args[1].type.isPrimitive() && c.args[1].type != VarType.LONG) {
            arrayKind[slot] = c.args[1].type;
        }
    }

    static final boolean DUAL = me.padej.jumper.runtime.Opts.on("dual");

    /** Pick the dual slots and their kinds; only slots that are written and not already unboxed qualify. */
    private void chooseDual(boolean[] banned) {
        arithUses(body);
        for (int s = 0; s < dualKind.length; s++) {
            if (banned[s] || slotType[s] != null) continue;
            VarType k = null;
            boolean written = false, fieldArith = false;
            for (Write w : writes) {
                if (w.slot() != s) continue;
                written = true;
                VarType t = evidence(w.value());
                if (t == VarType.DOUBLE) k = VarType.DOUBLE;
                else if (t == VarType.LONG && k != VarType.DOUBLE) k = VarType.LONG;
                else if (t == VarType.INT && k == null) k = VarType.INT;
                if (overFields(w.value())) fieldArith = true;
            }
            if (!written) continue;
            if (k == null && (fieldArith || arithUse[s])) k = VarType.DOUBLE;
            dualKind[s] = k;
        }
    }

    /** What kind a write suggests: the value's static type, or the target kind of int(x)/long(x)/double(x). */
    private static VarType evidence(Expr e) {
        if (e instanceof Exprs.BuiltinCall bc) {
            switch (bc.kind) {
                case Exprs.BuiltinCall.DYN_INT: return VarType.INT;
                case Exprs.BuiltinCall.DYN_LONG: return VarType.LONG;
                case Exprs.BuiltinCall.DYN_DOUBLE: return VarType.DOUBLE;
                default: break;
            }
        }
        return e.type;
    }

    /** Arithmetic over table fields (and locals/literals): the shape speculation of the compiler makes it double. */
    private static boolean overFields(Expr e) {
        if (e instanceof Exprs.Binary b) {
            if (b.type == VarType.BOOLEAN || b.type == VarType.STRING) return false;
            return overFields(b.left) || overFields(b.right);
        }
        if (e instanceof Exprs.Neg n) return overFields(n.e);
        return e instanceof Exprs.Member m && m.obj instanceof Exprs.Local;
    }

    private void arithUses(Object node) {
        if (node == null || node instanceof FunctionNode) return;
        if (node instanceof Exprs.Binary b && b.type != VarType.STRING) { markUse(b.left); markUse(b.right); }
        else if (node instanceof Exprs.Neg n) markUse(n.e);
        else if (node instanceof Exprs.CompoundAssign ca) { markUse(ca.target); markUse(ca.value); }
        else if (node instanceof Exprs.IncLocal il && il.slot < arithUse.length) arithUse[il.slot] = true;
        for (Object child : Captures.children(node)) arithUses(child);
    }

    private void markUse(Expr e) {
        if (e instanceof Exprs.Local l && l.slot < arithUse.length) arithUse[l.slot] = true;
    }

    // ---------- collection ----------

    /**
     * Traversal of the body: declarations set the initial assumption, other writes to a slot
     * are collected for checking, and forms that put anything at all into a slot ban it.
     */
    private void collect(Object node, boolean[] banned, int level) {
        if (node == null) return;
        if (node instanceof FunctionNode) return;   // body of a nested function - foreign slots
        if (node instanceof Stmts.VarDecl d) {
            if (d.type == VarType.DYN && d.slot < banned.length) {
                if (d.init == null) banned[d.slot] = true;   // `dyn x;` - null there
                else writes.add(new Write(d.slot, d.init));
            }
        } else if (node instanceof Stmts.FuncDecl f) {
            if (f.slot < banned.length) banned[f.slot] = true;
        } else if (node instanceof Stmts.ForEach fe) {
            if (fe.slot < banned.length) banned[fe.slot] = true;   // writes whatever the iterator yields
        } else if (node instanceof Exprs.AssignLocal a) {
            if (a.type == VarType.DYN && a.slot < banned.length) writes.add(new Write(a.slot, a.value));
        } else if (node instanceof Exprs.Assign a && a.target instanceof Exprs.Local l) {
            if (l.slot < banned.length) writes.add(new Write(l.slot, a.value));
        } else if (node instanceof Exprs.CompoundAssign ca && ca.target instanceof Exprs.Local l) {
            if (l.slot < banned.length) writes.add(new Write(l.slot, ca));
        }
        banSlotsOfOtherKinds(node, banned);
        for (Object child : Captures.children(node)) collect(child, banned, level);
    }

    /**
     * Slots written by forms outside our analysis (catch, class declaration) are banned
     * by field name: there are few of them, and missing such a write would mean unboxing a slot
     * that receives a reference.
     */
    private void banSlotsOfOtherKinds(Object node, boolean[] banned) {
        String n = node.getClass().getSimpleName();
        if (!n.equals("TryCatch") && !n.equals("ClassDecl")) return;
        for (java.lang.reflect.Field f : Captures.fields(node.getClass())) {
            if (f.getType() != int.class || !f.getName().toLowerCase(java.util.Locale.ROOT).contains("slot")) continue;
            try {
                int s = f.getInt(node);
                if (s >= 0 && s < banned.length) banned[s] = true;
            } catch (IllegalAccessException ignored) {
                // the field is unreadable - silently treating the slot as banned is wrong, but failing is not an option either:
                // the safe side here is to ban all slots of this function
                java.util.Arrays.fill(banned, true);
            }
        }
    }

    // ---------- fixed point ----------

    private void settle(boolean[] banned) {
        // initial assumption - the type of the declaration initializer
        for (Write w : writes) {
            if (banned[w.slot()]) continue;
            VarType t = w.value().type;
            if (t.isPrimitive() && slotType[w.slot()] == null) slotType[w.slot()] = t;
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            // upward: a slot without a type whose every write has one and the same primitive type
            // under the current assumptions takes it - `dyn j = i * i` with an unboxed int i is an int
            // (the static type of `i * i` is dyn, only the assumption about i makes it int)
            inferred.clear();
            for (int s = 0; s < slotType.length; s++) {
                if (banned[s] || slotType[s] != null) continue;
                // candidate: the first write whose value does not depend on the slot itself
                // (`j += i` says nothing until j has a type; `dyn j = i * i` does)
                VarType cand = null;
                for (Write w : writes) {
                    if (w.slot() != s || w.value() instanceof Exprs.CompoundAssign) continue;
                    VarType wt = typeUnder(w.value());
                    if (wt.isPrimitive()) { cand = wt; break; }
                }
                if (cand == null) continue;
                slotType[s] = cand;   // tentatively; every write must agree under it
                inferred.clear();
                boolean ok = true;
                for (Write w : writes) {
                    if (w.slot() == s && typeUnder(w.value()) != cand) { ok = false; break; }
                }
                if (ok) changed = true; else slotType[s] = null;
                inferred.clear();
            }
            // downward: retract any assumption a write contradicts
            inferred.clear();
            for (Write w : writes) {
                VarType want = slotType[w.slot()];
                if (want == null) continue;
                if (typeUnder(w.value()) != want) {
                    slotType[w.slot()] = null;
                    changed = true;
                }
            }
        }
        // Final filling of the table - over the WHOLE body, not just the assignments.
        // The loop condition `i < n` and the expression `i & 1023` are not part of an assignment, but they
        // are what decides whether the value stays unboxed: without their types the slot sits in an int local
        // while every operation boxes it back anyway.
        inferred.clear();
        fill(body);
    }

    /** Node the traversal started from - needed for the final pass. */
    private Object body;

    private void fill(Object node) {
        if (node == null || node instanceof FunctionNode) return;
        if (node instanceof Expr e) typeUnder(e);
        for (Object child : Captures.children(node)) fill(child);
    }

    /** Type of an expression under the current slot assumption; fills the table along the way. */
    private VarType typeUnder(Expr e) {
        if (e == null) return VarType.DYN;
        VarType t = compute(e);
        if (t != e.type) inferred.put(e, t);
        return t;
    }

    private VarType compute(Expr e) {
        if (e instanceof Exprs.Local l) {
            VarType t = slotType[l.slot];
            return t == null ? VarType.DYN : t;
        }
        if (e instanceof Exprs.AddLocalConst a) {
            VarType t = slotType[a.slot];
            return t == null ? VarType.DYN : VarType.arith(t, VarType.INT);
        }
        if (e instanceof Exprs.IncLocal i) {
            VarType t = slotType[i.slot];
            return t == null ? VarType.DYN : t;
        }
        if (e instanceof Exprs.AssignLocal a && a.type == VarType.DYN) {
            // the value of an assignment used as an expression: the primitive of an unboxed slot
            // (the compiler stores and re-loads it as such), otherwise the Object written
            typeUnder(a.value);
            VarType t = slotType[a.slot];
            return t == null ? VarType.DYN : t;
        }
        if (e instanceof Exprs.Binary b) {
            VarType l = typeUnder(b.left), r = typeUnder(b.right);
            if (e.type == VarType.BOOLEAN) return VarType.BOOLEAN;      // comparison - the type is fixed by the node
            if (e.type == VarType.STRING) return VarType.STRING;        // concatenation
            VarType a = VarType.arith(l, r);
            // shifts and bitwise ops - integers only, and the result is no wider than the left operand
            return a;
        }
        if (e instanceof Exprs.CompoundAssign ca) {
            VarType l = typeUnder(ca.target), r = typeUnder(ca.value);
            return VarType.arith(l, r);
        }
        if (e instanceof Exprs.Neg n) {
            VarType t = typeUnder(n.e);
            return t.isNumeric() ? t : VarType.DYN;
        }
        if (e instanceof Exprs.Ternary t) {
            // Tier 0 widens only by the static types (Ternary.type): `c ? n : 2.5` with a `dyn n` holding
            // an int yields that Integer, not 1.0 - widening here by what unboxing learnt would differ
            if (t.type.isPrimitive()) return t.type;
            VarType a = typeUnder(t.a), b = typeUnder(t.b);
            return a == b ? a : VarType.DYN;
        }
        return e.type.isPrimitive() ? e.type : VarType.DYN;
    }
}
