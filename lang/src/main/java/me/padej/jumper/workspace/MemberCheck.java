package me.padej.jumper.workspace;

import me.padej.jumper.ast.Expr;
import me.padej.jumper.ast.Exprs;
import me.padej.jumper.ast.FunctionNode;
import me.padej.jumper.ast.Stmt;
import me.padej.jumper.parser.Range;
import me.padej.jumper.runtime.Access;
import me.padej.jumper.runtime.JavaClass;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Warnings about Java calls that will fail when the script runs, found where the receiver's Java type is
 * known without running anything: a host global (`server`, typed by the host descriptor), an imported
 * class (`Math.max`), `new C(...)`, a string, and chains of those (`server.world().name()`) - and a local
 * variable whose every assignment has one such type: `dyn w = server.world(); w.nope()`, `Random r = ...;
 * r.nope()`, a parameter declared with a Java class, a `dyn` parameter of a function the host calls (a hook:
 * `void onJoin(dyn p)` - what the host passes, `ScriptEvents.onJoin(ScriptPlayer)`). A `dyn` variable that
 * gets values of different or unknown types is not checked: its type is only known at run time.
 *
 * <ul>
 *   <li>no method of that name - `server.nope()`;</li>
 *   <li>no overload with that many arguments - `server.log()`;</li>
 *   <li>the method exists but the access policy closes it - `Access denied` at run time.</li>
 * </ul>
 */
final class MemberCheck {
    private MemberCheck() {}

    /** A receiver type: instances of `cls`, or the class itself (static members). */
    record Type(Class<?> cls, boolean statics) {}

    /** The functions around a node, innermost first: a local is (its function, its slot), an upvalue is `depth` functions out. */
    record Chain(FunctionNode fn, Chain up) {
        FunctionNode out(int depth) {
            Chain c = this;
            for (int i = 0; i < depth && c != null; i++) c = c.up;
            return c == null ? null : c.fn;
        }
    }

    /** A variable given values of more than one type (or of an unknown one): no type. */
    private static final Type MIXED = new Type(Void.class, false);

    /** The types of the local variables: function -> slot -> type (or MIXED). */
    static final class Locals {
        final ClassLoader loader;
        /** The previous round's types (what reads see) and this round's (what assignments build). */
        Map<FunctionNode, Map<Integer, Type>> types = new IdentityHashMap<>(), next = new IdentityHashMap<>();

        /** What the host passes to the `dyn` parameters of the hooks: (function, slot, type), a value each round starts with. */
        final List<Object[]> seeds = new ArrayList<>();

        /** One more round: assignments seen again, with the types the last round found for the variables they read. */
        boolean round(FunctionNode program) {
            next = new IdentityHashMap<>();
            for (Object[] s : seeds) note((FunctionNode) s[0], (Integer) s[1], (Type) s[2]);
            walk(program, this::collect);
            boolean same = next.equals(types);
            types = next;
            return !same;
        }

        Locals(ClassLoader loader) {
            this.loader = loader;
        }

        Type get(FunctionNode fn, int slot) {
            if (fn == null) return null;
            Type t = types.getOrDefault(fn, Map.of()).get(slot);
            return t == MIXED ? null : t;
        }

        /** A value of type `t` (null: unknown) goes into the variable. */
        void note(FunctionNode fn, int slot, Type t) {
            if (fn == null) return;
            Map<Integer, Type> m = next.computeIfAbsent(fn, k -> new java.util.HashMap<>());
            Type old = m.get(slot);
            m.put(slot, t == null ? MIXED : old == null || old.equals(t) ? t : MIXED);
        }

        /** What an assignment, declaration or check tells about a variable's type. */
        void collect(Object node, Chain chain) {
            if (node instanceof me.padej.jumper.ast.Stmts.VarDecl vd && !vd.type.isPrimitive() && vd.init != null) {
                if (!isNull(vd.init)) note(chain.fn, vd.slot, vd.type == me.padej.jumper.ast.VarType.STRING
                        ? new Type(String.class, false) : typeOf(vd.init, loader, chain, this));
            } else if (node instanceof Exprs.AssignLocal a && !a.type.isPrimitive()) {
                if (!isNull(a.value)) note(chain.fn, a.slot, typeOf(a.value, loader, chain, this));
            } else if (node instanceof Exprs.AssignUpvalue a && !a.type.isPrimitive()) {
                if (!isNull(a.value)) note(chain.out(a.depth), a.slot, typeOf(a.value, loader, chain, this));
            } else if (node instanceof Exprs.Assign a && !isNull(a.value)) {
                Type t = typeOf(a.value, loader, chain, this);
                if (a.target instanceof Exprs.Local l) note(chain.fn, l.slot, t);
                else if (a.target instanceof Exprs.Upvalue u) note(chain.out(u.depth), u.slot, t);
            } else if (node instanceof Exprs.CompoundAssign ca) {
                if (ca.target instanceof Exprs.Local l) note(chain.fn, l.slot, null);
                else if (ca.target instanceof Exprs.Upvalue u) note(chain.out(u.depth), u.slot, null);
            } else if (node instanceof Exprs.IncLocal inc && !inc.type.isPrimitive()) {
                note(chain.fn, inc.slot, null);
            } else if (node instanceof me.padej.jumper.ast.Fields.CheckJava cj && cj.value instanceof Exprs.Local l) {
                note(chain.fn, l.slot, new Type(cj.cls, false));   // a parameter or a for-each variable declared with a Java class
            }
        }

        private static boolean isNull(Expr e) {
            return e instanceof Exprs.Literal lit && lit.value == null;
        }
    }

    /** Every node of the tree with the functions around it (iterative: a deep tree must not overflow the stack). */
    static void walk(FunctionNode program, java.util.function.BiConsumer<Object, Chain> visit) {
        Set<Object> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        List<Object> stack = new ArrayList<>();
        List<Chain> chains = new ArrayList<>();
        stack.add(program);
        chains.add(null);
        List<Object> kids = new ArrayList<>();
        while (!stack.isEmpty()) {
            Object node = stack.remove(stack.size() - 1);
            Chain chain = chains.remove(chains.size() - 1);
            if (node == null || !seen.add(node)) continue;
            if (node instanceof FunctionNode fn) chain = new Chain(fn, chain);
            else visit.accept(node, chain);
            kids.clear();
            children(node, kids);
            for (Object k : kids) {
                stack.add(k);
                chains.add(chain);
            }
        }
    }

    static List<Checker.Problem> run(FunctionNode program, Map<Object, Range> ranges, String source, ClassLoader loader, Access policy) {
        return run(program, ranges, source, loader, policy, null);
    }

    static List<Checker.Problem> run(FunctionNode program, Map<Object, Range> ranges, String source, ClassLoader loader, Access policy,
                                     FileContext ctx) {
        List<Checker.Problem> out = new ArrayList<>();
        if (program == null) return out;
        int[] lineStarts = lineStarts(source);
        // the types of the locals: a type found for one variable can type another (`dyn a = x; dyn b = a;`)
        Locals locals = new Locals(loader);
        if (ctx != null) hookSeeds(program, ctx, loader, locals.seeds);
        for (int round = 0; round < 6 && locals.round(program); round++) { }
        walk(program, (node, chain) -> {
            if (node instanceof Exprs.MethodCall mc) check(mc, chain, locals, ranges, source, lineStarts, loader, policy, out);
        });
        out.sort(java.util.Comparator.comparingInt(Checker.Problem::line).thenComparingInt(Checker.Problem::col));
        return out;
    }

    /**
     * `void onJoin(dyn p)` at the top level, a function the host calls (Hooks): its `dyn` parameters start with the
     * types of the API method's parameters - as if the host assigned them; the function's own assignments still count.
     */
    private static void hookSeeds(FunctionNode program, FileContext ctx, ClassLoader loader, List<Object[]> out) {
        if (ctx.hookTypes().isEmpty() && ctx.hooks().isEmpty()) return;
        Stmt body = program.body;
        Stmt[] stmts = body instanceof me.padej.jumper.ast.Stmts.Block b ? b.stmts : body == null ? new Stmt[0] : new Stmt[] {body};
        for (Stmt st : stmts) {
            if (!(st instanceof me.padej.jumper.ast.Stmts.FuncDecl fd)) continue;
            FunctionNode fn = fd.fn;
            Hooks.Hook h = Hooks.find(ctx, fn.name, loader);
            Method m = h == null ? null : h.method(fn.nparams);
            if (m == null || m.getParameterCount() != fn.nparams) continue;
            Class<?>[] ps = m.getParameterTypes();
            for (int i = 0; i < fn.nparams; i++) {
                if (fn.paramTypes[i] != me.padej.jumper.ast.VarType.DYN) continue;
                Type t = objectType(ps[i]);
                if (t != null) out.add(new Object[] {fn, fn.paramIndex[i], t});
            }
        }
    }

    private static void check(Exprs.MethodCall mc, Chain chain, Locals locals, Map<Object, Range> ranges, String source,
                              int[] lineStarts, ClassLoader loader, Access policy, List<Checker.Problem> out) {
        Type t = typeOf(mc.obj, loader, chain, locals);
        if (t == null) return;
        String owner = Members.simple(t.cls());
        List<Method> byName = Members.named(Members.methods(t.cls(), t.statics()), mc.name);
        String msg;
        if (byName.isEmpty()) {
            msg = "No " + (t.statics() ? "static " : "") + "method '" + mc.name + "' in " + owner;
        } else {
            List<Method> fit = arity(byName, mc.args.length);
            if (fit.isEmpty()) {
                msg = owner + "." + mc.name + " takes " + arities(byName) + ", not " + mc.args.length;
            } else if (policy != null && Members.allowed(fit, t.cls(), policy).isEmpty()) {
                msg = "Access denied: " + t.cls().getName() + "." + mc.name + " (closed by the access policy)";
            } else return;
        }
        int[] at = namePosition(mc, ranges, source, lineStarts);
        out.add(new Checker.Problem(at[0], at[1], msg, Checker.Severity.WARNING));
    }

    /** The static type of an expression, where it is known without running the script; null otherwise. */
    static Type typeOf(Expr e, ClassLoader loader) {
        return typeOf(e, loader, null, null);
    }

    /** The same, with the types of the local variables (`chain` - the functions around the expression). */
    static Type typeOf(Expr e, ClassLoader loader, Chain chain, Locals locals) {
        if (e instanceof Exprs.Literal lit) {
            if (lit.value instanceof HostGlobal g) {
                Class<?> c = Members.load(g.type(), loader);
                return c == null ? null : new Type(c, false);
            }
            if (lit.value instanceof JavaClass jc) return new Type(jc.cls(), true);
            if (lit.value instanceof String) return new Type(String.class, false);
            return null;
        }
        if (e instanceof me.padej.jumper.ast.Fields.CheckJava cj) return new Type(cj.cls, false);
        if (locals != null && chain != null) {
            if (e instanceof Exprs.Local l) return locals.get(chain.fn, l.slot);
            if (e instanceof Exprs.Upvalue u) return locals.get(chain.out(u.depth), u.slot);
        }
        if (e instanceof Exprs.New n && n.cls instanceof Exprs.Literal lit && lit.value instanceof JavaClass jc) return new Type(jc.cls(), false);
        if (e instanceof Exprs.MethodCall mc) {
            Type t = typeOf(mc.obj, loader, chain, locals);
            if (t == null) return null;
            Class<?> r = Members.returnType(arity(Members.named(Members.methods(t.cls(), t.statics()), mc.name), mc.args.length));
            return objectType(r);
        }
        if (e instanceof Exprs.Member m) {
            Type t = typeOf(m.obj, loader, chain, locals);
            if (t == null) return null;
            return objectType(Members.propertyType(t.cls(), m.name));
        }
        return null;
    }

    /** Members are looked up only on object types: a primitive or a void result ends the chain. */
    private static Type objectType(Class<?> c) {
        return c == null || c.isPrimitive() || c.isArray() ? null : new Type(c, false);
    }

    static List<Method> arity(List<Method> ms, int n) {
        List<Method> out = new ArrayList<>();
        for (Method m : ms) if (m.getParameterCount() == n || m.isVarArgs() && n >= m.getParameterCount() - 1) out.add(m);
        return out;
    }

    private static String arities(List<Method> ms) {
        java.util.TreeSet<Integer> ns = new java.util.TreeSet<>();
        for (Method m : ms) ns.add(m.getParameterCount());
        StringBuilder sb = new StringBuilder();
        for (int n : ns) {
            if (sb.length() > 0) sb.append(" or ");
            sb.append(n);
        }
        return sb.append(ns.size() == 1 && ns.first() == 1 ? " argument" : " arguments").toString();
    }

    /** 1-based line:col of the method name in `recv.name(...)`: after the receiver, the name that follows a dot. */
    private static int[] namePosition(Exprs.MethodCall mc, Map<Object, Range> ranges, String source, int[] lineStarts) {
        Range whole = ranges.get(mc), recv = ranges.get(mc.obj);
        int from = recv != null ? offset(lineStarts, recv.endLine(), recv.endCol()) : whole != null ? offset(lineStarts, whole.startLine(), whole.startCol()) : 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\.\\s*(" + java.util.regex.Pattern.quote(mc.name) + ")\\b").matcher(source);
        int pos = m.find(Math.max(0, Math.min(from, source.length()))) ? m.start(1) : from;
        return position(lineStarts, pos);
    }

    static int[] lineStarts(String s) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') starts.add(i + 1);
        int[] out = new int[starts.size()];
        for (int i = 0; i < out.length; i++) out[i] = starts.get(i);
        return out;
    }

    static int offset(int[] lineStarts, int line, int col) {
        int l = Math.max(1, Math.min(line, lineStarts.length));
        return lineStarts[l - 1] + col - 1;
    }

    static int[] position(int[] lineStarts, int offset) {
        int lo = 0, hi = lineStarts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (lineStarts[mid] <= offset) lo = mid; else hi = mid - 1;
        }
        return new int[] {lo + 1, offset - lineStarts[lo] + 1};
    }

    // ------------------------------------------------------------------ the tree, by reflection (cached per class)

    private static final Map<Class<?>, Field[]> CHILD_FIELDS = new ConcurrentHashMap<>();

    private static void children(Object node, List<Object> out) {
        for (Field f : CHILD_FIELDS.computeIfAbsent(node.getClass(), MemberCheck::childFields)) {
            try {
                Object v = f.get(node);
                if (v instanceof Object[] arr) { for (Object o : arr) if (o != null) out.add(o); }
                else if (v != null) out.add(v);
            } catch (IllegalAccessException e) {
                // not a child
            }
        }
    }

    private static Field[] childFields(Class<?> c) {
        List<Field> out = new ArrayList<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getName().equals("kids") || f.getName().equals("target")) continue;
                Class<?> t = f.getType();
                boolean node = Expr.class.isAssignableFrom(t) || Stmt.class.isAssignableFrom(t) || t == FunctionNode.class;
                boolean nodes = t.isArray() && (Expr.class.isAssignableFrom(t.getComponentType()) || Stmt.class.isAssignableFrom(t.getComponentType())
                        || t.getComponentType() == FunctionNode.class);
                if (!node && !nodes) continue;
                try { f.setAccessible(true); } catch (RuntimeException e) { continue; }
                out.add(f);
            }
        }
        return out.toArray(new Field[0]);
    }
}
