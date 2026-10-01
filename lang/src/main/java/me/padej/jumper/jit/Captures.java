package me.padej.jumper.jit;

import me.padej.jumper.ast.Expr;
import me.padej.jumper.ast.Exprs;
import me.padej.jumper.ast.FunctionNode;
import me.padej.jumper.ast.Prims;
import me.padej.jumper.ast.Stmt;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Analysis of a function before compilation: which of its variables are captured by nested functions
 * (those must live in the Frame, the rest in JVM locals) and whether there are nested functions at all.
 * The AST traversal is reflective over the public fields of the nodes (Expr/Stmt/arrays/FunctionNode).
 */
public final class Captures {
    final BitSet slots = new BitSet();
    final BitSet prims = new BitSet();
    boolean hasInner;

    /**
     * Predicate "Tier 1 will call this call site directly". The compiler sets it, and it must be
     * the same during analysis and during generation: if the analysis decided the callee need not be read
     * but generation read it anyway, a body without this would need the closure - and it would crash
     * with an NPE on local0. So there is one predicate and it is passed in here rather than duplicated.
     */
    private static Predicate<Exprs.Call> direct = c -> false;

    /** Conservative answer for callers outside Tier 1: assume there are no direct calls. */
    public static boolean usesOuter(FunctionNode fn) {
        return usesOuter(fn, c -> false);
    }

    /** Whether the function (or its nested functions) refers to variables of enclosing frames. */
    public static synchronized boolean usesOuter(FunctionNode fn, Predicate<Exprs.Call> directCall) {
        Predicate<Exprs.Call> saved = direct;
        direct = directCall;
        try {
            return usesOuter(fn.body, 0);
        } finally {
            direct = saved;
        }
    }

    private static boolean usesOuter(Object node, int level) {
        if (node == null) return false;
        if (node instanceof FunctionNode inner) return usesOuter(inner.body, level + 1);
        Integer depth = intField(node, "depth");
        if (depth != null && depth > level) return true;
        for (Object child : effectiveChildren(node)) if (usesOuter(child, level)) return true;
        return false;
    }

    /**
     * Child nodes, taking into account that a directly bound call does not evaluate the callee: Tier 1
     * emits an invokestatic for it, not a variable read. Without this a recursive function
     * would look like it reads the enclosing frame (it calls itself by the name from above),
     * and a static body would be impossible for any recursion.
     *
     * <p>The condition must match the one under which Compiler actually emits a direct call,
     * otherwise a body without this would suddenly need the closure. See Compiler.directCall().
     */
    static List<Object> effectiveChildren(Object node) {
        if (node instanceof Exprs.Call c && direct.test(c)) {
            List<Object> out = new ArrayList<>(c.args.length);
            for (Expr a : c.args) if (a != null) out.add(a);
            return out;
        }
        return children(node);
    }


    static synchronized Captures analyze(FunctionNode fn, Predicate<Exprs.Call> directCall) {
        Predicate<Exprs.Call> saved = direct;
        direct = directCall;
        try {
            Captures c = new Captures();
            c.walk(fn.body, 0);
            return c;
        } finally {
            direct = saved;
        }
    }

    private void walk(Object node, int level) {
        if (node == null) return;
        if (node instanceof FunctionNode inner) {
            hasInner |= level == 0;
            walk(inner.body, level + 1);
            return;
        }
        if (level > 0) noteAccess(node, level);
        for (Object child : effectiveChildren(node)) walk(child, level);
    }

    private void noteAccess(Object node, int level) {
        Integer depth = intField(node, "depth");
        if (depth == null || depth != level) return;
        boolean prim = node.getClass().getEnclosingClass() == Prims.class;
        Integer idx = intField(node, prim ? "idx" : "slot");
        if (idx == null) return;
        if (prim) prims.set(idx);
        else slots.set(idx);
    }

    private static Integer intField(Object node, String name) {
        for (Field f : fields(node.getClass())) {
            if (f.getName().equals(name) && f.getType() == int.class) {
                try {
                    return f.getInt(node);
                } catch (IllegalAccessException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static final Map<Class<?>, Field[]> FIELDS = new ConcurrentHashMap<>();

    static Field[] fields(Class<?> cls) {
        return FIELDS.computeIfAbsent(cls, c -> {
            List<Field> out = new ArrayList<>();
            for (Field f : c.getFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                out.add(f);
            }
            return out.toArray(new Field[0]);
        });
    }

    /**
     * Child nodes: Expr, Stmt, their arrays and FunctionNode. Computed by reflection once
     * and cached in the node: within one compilation the body is traversed by precompileTargets, analyze, usesOuter
     * and two Unbox passes, and each traversal through Field.get cost more than the generation itself.
     * The tree does not change after parsing, so the cache is safe.
     */
    static List<Object> children(Object node) {
        Object[] cached;
        if (node instanceof Expr e) { if (e.kids == null) e.kids = compute(node); cached = e.kids; }
        else if (node instanceof Stmt s) { if (s.kids == null) s.kids = compute(node); cached = s.kids; }
        else if (node instanceof FunctionNode f) { if (f.kids == null) f.kids = compute(node); cached = f.kids; }
        else cached = compute(node);
        return java.util.Arrays.asList(cached);
    }

    private static Object[] compute(Object node) {
        List<Object> out = new ArrayList<>();
        for (Field f : fields(node.getClass())) {
            Class<?> t = f.getType();
            try {
                if (t == FunctionNode.class && f.getName().equals("target")) continue; // Call.target is not a nested function
                if (Expr.class.isAssignableFrom(t) || Stmt.class.isAssignableFrom(t) || t == FunctionNode.class) {
                    Object v = f.get(node);
                    if (v != null) out.add(v);
                } else if (t.isArray() && (Expr.class.isAssignableFrom(t.getComponentType()) || Stmt.class.isAssignableFrom(t.getComponentType())
                        || t.getComponentType() == FunctionNode.class)) {
                    Object[] arr = (Object[]) f.get(node);
                    if (arr != null) for (Object v : arr) if (v != null) out.add(v);
                }
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
        return out.toArray();
    }
}
