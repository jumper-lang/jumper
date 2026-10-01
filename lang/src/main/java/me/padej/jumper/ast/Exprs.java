package me.padej.jumper.ast;

import me.padej.jumper.interp.Frame;
import me.padej.jumper.lexer.TokenType;
import me.padej.jumper.runtime.*;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** All expression nodes. */
public final class Exprs {
    private Exprs() {}

    // ---------------- literals & variables ----------------

    public static final class Literal extends Expr {
        public final Object value;

        public Literal(Object value, int line) {
            super(line);
            this.value = value;
        }

        @Override
        public Object eval(Frame f) {
            return value;
        }
    }

    /** Local variable of the current frame. */
    public static final class Local extends Expr {
        public final int slot;

        public Local(int slot, int line) {
            super(line);
            this.slot = slot;
        }

        @Override
        public Object eval(Frame f) {
            return f.slots[slot];
        }
    }

    /** Variable from a closure at depth depth. */
    public static final class Upvalue extends Expr {
        public final int depth, slot;

        public Upvalue(int depth, int slot, int line) {
            super(line);
            this.depth = depth;
            this.slot = slot;
        }

        @Override
        public Object eval(Frame f) {
            return f.up(depth).slots[slot];
        }
    }

    public static final class AssignLocal extends Expr {
        public final int slot;
        public final VarType type;
        public final Expr value;

        public AssignLocal(int slot, VarType type, Expr value, int line) {
            super(line);
            this.slot = slot;
            this.type = type;
            this.value = value;
        }

        @Override
        public Object eval(Frame f) {
            Object v = value.eval(f);
            if (type != VarType.DYN) v = type.coerce(v);
            f.slots[slot] = v;
            return v;
        }
    }

    public static final class AssignUpvalue extends Expr {
        public final int depth, slot;
        public final VarType type;
        public final Expr value;

        public AssignUpvalue(int depth, int slot, VarType type, Expr value, int line) {
            super(line);
            this.depth = depth;
            this.slot = slot;
            this.type = type;
            this.value = value;
        }

        @Override
        public Object eval(Frame f) {
            Object v = value.eval(f);
            if (type != VarType.DYN) v = type.coerce(v);
            f.up(depth).slots[slot] = v;
            return v;
        }
    }

    /** i++ / i-- / ++i / --i for a local variable. */
    public static final class IncLocal extends Expr {
        public final int slot;
        public final int delta;
        public final boolean prefix;
        public final VarType type;

        public IncLocal(int slot, int delta, boolean prefix, VarType type, int line) {
            super(line);
            this.slot = slot;
            this.delta = delta;
            this.prefix = prefix;
            this.type = type;
        }

        @Override
        public Object eval(Frame f) {
            Object[] s = f.slots;
            Object old = s[slot];
            Object nv;
            if (old instanceof Integer i) nv = i + delta;
            else nv = Ops.add(old, delta);
            if (type != VarType.DYN) nv = type.coerce(nv);
            s[slot] = nv;
            return prefix ? nv : old;
        }
    }

    /** Generic increment for any lvalue (field, index, upvalue). */
    public static final class IncGeneric extends Expr {
        public final Expr target;
        public final int delta;
        public final boolean prefix;

        public IncGeneric(Expr target, int delta, boolean prefix, int line) {
            super(line);
            this.target = target;
            this.delta = delta;
            this.prefix = prefix;
        }

        @Override
        public Object eval(Frame f) {
            Object old = target.eval(f);
            Object nv = Ops.add(old, delta);
            Assign.store(target, nv, f);
            return prefix ? nv : old;
        }
    }

    // ---------------- REPL globals ----------------

    /** REPL session variable: lives in a Map, survives input lines. */
    public static final class GlobalGet extends Expr {
        public final java.util.Map<String, Object> vars;
        public final String name;
        /**
         * Cell of the name, if the storage can hand them out (engine bindings). Then both Tier 0
         * and Tier 1 read the field through a constant reference instead of Map.get by string key.
         * null - a foreign Bindings implementation, the generic path through Map remains.
         */
        public final me.padej.jumper.runtime.Cell cell;

        public GlobalGet(java.util.Map<String, Object> vars, String name, int line) {
            super(line);
            this.vars = vars;
            this.name = name;
            this.cell = vars instanceof me.padej.jumper.runtime.CellStore cs ? cs.cell(name) : null;
        }

        @Override
        public Object eval(Frame f) {
            return cell != null ? cell.v : vars.get(name);
        }
    }

    public static final class GlobalSet extends Expr {
        public final java.util.Map<String, Object> vars;
        public final String name;
        public final VarType type;
        public final Expr value;

        /** Cell of the name - see GlobalGet.cell. */
        public final me.padej.jumper.runtime.Cell cell;

        public GlobalSet(java.util.Map<String, Object> vars, String name, VarType type, Expr value, int line) {
            super(line);
            this.vars = vars;
            this.name = name;
            this.type = type;
            this.value = value;
            this.cell = vars instanceof me.padej.jumper.runtime.CellStore cs ? cs.cell(name) : null;
        }

        @Override
        public Object eval(Frame f) {
            Object v = value == null ? type.defaultValue() : value.eval(f);
            if (type != VarType.DYN) v = type.coerce(v);
            if (cell != null) cell.set(v);
            else vars.put(name, v);
            return v;
        }
    }

    // ---------------- assignment to fields/indices ----------------

    public static final class Assign extends Expr {
        public final Expr target, value;

        public Assign(Expr target, Expr value, int line) {
            super(line);
            this.target = target;
            this.value = value;
        }

        @Override
        public Object eval(Frame f) {
            Object v = value.eval(f);
            store(target, v, f);
            return v;
        }

        static void store(Expr target, Object v, Frame f) {
            if (target instanceof Local l) f.slots[l.slot] = v;
            else if (target instanceof GlobalGet g) g.vars.put(g.name, v);
            else if (target instanceof Upvalue u) f.up(u.depth).slots[u.slot] = v;
            else if (target instanceof Member m) {
                Object o = m.obj.eval(f);
                if (o instanceof JTable t) m.cache.set(t, v);
                else m.cache.setJava(o, v);
            }
            else if (target instanceof Fields.ThisField tf) tf.slowSet(tf.self.eval(f), v);
            else if (target instanceof Fields.StaticField sf) sf.set(v);
            else if (target instanceof Index ix) {
                Object o = ix.obj.eval(f);
                if (ix.key.type == VarType.INT) {
                    int k = ix.key.evalInt(f);
                    if (o instanceof JArray a) { a.set(k, v); return; }
                    Ops.setIndex(o, k, v);
                } else Ops.setIndex(o, ix.key.eval(f), v);
            }
            else throw new JmpError("Invalid assignment target");
        }
    }

    /** a op= b - compute and store. */
    public static final class CompoundAssign extends Expr {
        public final Expr target, value;
        public final TokenType op;

        public CompoundAssign(Expr target, TokenType op, Expr value, int line) {
            super(line);
            this.target = target;
            this.op = op;
            this.value = value;
        }

        @Override
        public Object eval(Frame f) {
            Object cur, result;
            if (target instanceof Local l) {
                cur = f.slots[l.slot];
                result = Binary.apply(op, cur, value.eval(f));
                f.slots[l.slot] = result;
            } else if (target instanceof Upvalue u) {
                Frame uf = f.up(u.depth);
                result = Binary.apply(op, uf.slots[u.slot], value.eval(f));
                uf.slots[u.slot] = result;
            } else if (target instanceof GlobalGet g) {
                result = Binary.apply(op, g.vars.get(g.name), value.eval(f));
                g.vars.put(g.name, result);
            } else if (target instanceof Member m) {
                Object obj = m.obj.eval(f);
                if (obj instanceof JTable t) {
                    result = Binary.apply(op, m.cache.get(t), value.eval(f));
                    m.cache.set(t, result);
                } else {
                    result = Binary.apply(op, Ops.member(obj, m.name), value.eval(f));
                    Ops.setMember(obj, m.name, result);
                }
            } else if (target instanceof Index ix) {
                Object obj = ix.obj.eval(f);
                Object key = ix.key.eval(f);
                result = Binary.apply(op, Ops.index(obj, key), value.eval(f));
                Ops.setIndex(obj, key, result);
            } else throw new JmpError("Invalid assignment target");
            return result;
        }
    }

    // ---------------- operators ----------------

    public static final class Binary extends Expr {
        public final TokenType op;
        public final Expr left, right;

        public Binary(TokenType op, Expr left, Expr right, int line) {
            super(line);
            this.op = op;
            this.left = left;
            this.right = right;
            switch (op) {
                case LT, LE, GT, GE, EQEQ, NE -> type = VarType.BOOLEAN;
                default -> {}
            }
        }

        @Override
        public Object eval(Frame f) {
            if (type == VarType.BOOLEAN) return evalBool(f);
            return apply(op, left.eval(f), right.eval(f));
        }

        @Override
        public boolean evalBool(Frame f) {
            Object a = left.eval(f), b = right.eval(f);
            return switch (op) {
                case LT -> Ops.lt(a, b);
                case LE -> Ops.le(a, b);
                case GT -> Ops.gt(a, b);
                case GE -> Ops.ge(a, b);
                case EQEQ -> Ops.eq(a, b);
                case NE -> !Ops.eq(a, b);
                default -> Ops.truthy(apply(op, a, b));
            };
        }

        static Object apply(TokenType op, Object a, Object b) {
            return switch (op) {
                case PLUS, PLUSEQ -> Ops.add(a, b);
                case MINUS, MINUSEQ -> Ops.sub(a, b);
                case STAR, STAREQ -> Ops.mul(a, b);
                case SLASH, SLASHEQ -> Ops.div(a, b);
                case PERCENT, PERCENTEQ -> Ops.mod(a, b);
                case LT -> Ops.lt(a, b);
                case LE -> Ops.le(a, b);
                case GT -> Ops.gt(a, b);
                case GE -> Ops.ge(a, b);
                case EQEQ -> Ops.eq(a, b);
                case NE -> !Ops.eq(a, b);
                case AMP -> Ops.band(a, b);
                case PIPE -> Ops.bor(a, b);
                case CARET -> Ops.bxor(a, b);
                case SHL -> Ops.shl(a, b);
                case SHR -> Ops.shr(a, b);
                case USHR -> Ops.ushr(a, b);
                default -> throw new JmpError("Unknown operator " + op);
            };
        }
    }

    /** Specialization: addition of two local ints - the most frequent case in hot loops. */
    public static final class AddLocalConst extends Expr {
        public final int slot;
        public final int c;

        public AddLocalConst(int slot, int c, int line) {
            super(line);
            this.slot = slot;
            this.c = c;
        }

        @Override
        public Object eval(Frame f) {
            Object v = f.slots[slot];
            if (v instanceof Integer i) return i + c;
            return Ops.add(v, c);
        }
    }

    public static final class Logical extends Expr {
        public final boolean isAnd;
        public final Expr left, right;

        public Logical(boolean isAnd, Expr left, Expr right, int line) {
            super(line);
            this.isAnd = isAnd;
            this.left = left;
            this.right = right;
            if (left.type == VarType.BOOLEAN && right.type == VarType.BOOLEAN) type = VarType.BOOLEAN;
        }

        @Override
        public Object eval(Frame f) {
            Object a = left.eval(f);
            if (isAnd) return Ops.truthy(a) ? right.eval(f) : a;
            return Ops.truthy(a) ? a : right.eval(f);
        }

        @Override
        public boolean evalBool(Frame f) {
            if (isAnd) return left.evalBool(f) && right.evalBool(f);
            return left.evalBool(f) || right.evalBool(f);
        }
    }

    public static final class Not extends Expr {
        public final Expr e;

        public Not(Expr e, int line) {
            super(line);
            this.e = e;
            type = VarType.BOOLEAN;
        }

        @Override
        public Object eval(Frame f) {
            return !e.evalBool(f);
        }

        @Override
        public boolean evalBool(Frame f) {
            return !e.evalBool(f);
        }
    }

    public static final class Neg extends Expr {
        public final Expr e;

        public Neg(Expr e, int line) {
            super(line);
            this.e = e;
        }

        @Override
        public Object eval(Frame f) {
            return Ops.neg(e.eval(f));
        }
    }

    /**
     * A dynamic value stored into a typed primitive location (variable, field, typed return): the one
     * conversion every such location applies - a value of the type (or a widening number) as is,
     * null as the type's default (0, 0L, 0.0, false), anything else `Expected int, got string`.
     * No truthiness: `boolean b = 1` from a dyn is an error, as for a parameter. Parameters and
     * `int f()` returns convert the same way (FunctionNode.interpret, Tier 1's arg()).
     */
    public static final class ToSlot extends Expr {
        public final Expr value;

        public ToSlot(Expr value, VarType type, int line) {
            super(line);
            this.value = value;
            this.type = type;
        }

        @Override
        public Object eval(Frame f) {
            Object v = value.eval(f);
            return switch (type) {
                case INT -> Ops.slotInt(v);
                case LONG -> Ops.slotLong(v);
                case DOUBLE -> Ops.slotDouble(v);
                default -> Ops.slotBool(v);
            };
        }

        @Override public int evalInt(Frame f) { return Ops.slotInt(value.eval(f)); }
        @Override public long evalLong(Frame f) { return Ops.slotLong(value.eval(f)); }
        @Override public double evalDouble(Frame f) { return Ops.slotDouble(value.eval(f)); }
        @Override public boolean evalBool(Frame f) { return Ops.slotBool(value.eval(f)); }
    }

    public static final class Ternary extends Expr {
        public final Expr cond, a, b;

        public Ternary(Expr cond, Expr a, Expr b, int line) {
            super(line);
            this.cond = cond;
            this.a = a;
            this.b = b;
            if (a.type == b.type) type = a.type;
            else if (a.type.isNumeric() && b.type.isNumeric()) type = VarType.arith(a.type, b.type);
        }

        @Override
        public Object eval(Frame f) {
            if (type.isPrimitive()) return type.fromBits(switch (type) {
                case INT -> evalInt(f);
                case LONG -> evalLong(f);
                case DOUBLE -> Double.doubleToRawLongBits(evalDouble(f));
                default -> evalBool(f) ? 1L : 0L;
            });
            return cond.evalBool(f) ? a.eval(f) : b.eval(f);
        }

        @Override public int evalInt(Frame f) { return cond.evalBool(f) ? a.evalInt(f) : b.evalInt(f); }
        @Override public long evalLong(Frame f) { return cond.evalBool(f) ? a.evalLong(f) : b.evalLong(f); }
        @Override public double evalDouble(Frame f) { return cond.evalBool(f) ? a.evalDouble(f) : b.evalDouble(f); }
        @Override public boolean evalBool(Frame f) { return cond.evalBool(f) ? a.evalBool(f) : b.evalBool(f); }
    }

    // ---------------- access ----------------

    public static final class Member extends Expr {
        public final Expr obj;
        public final String name;
        public final FieldCache cache;

        public Member(Expr obj, String name, int line) {
            super(line);
            this.obj = obj;
            this.name = name;
            this.cache = new FieldCache(name);
        }

        @Override
        public Object eval(Frame f) {
            Object o = obj.eval(f);
            if (o instanceof JTable t) return cache.get(t);
            return cache.getJava(o);
        }
    }

    public static final class Index extends Expr {
        public final Expr obj, key;

        public Index(Expr obj, Expr key, int line) {
            super(line);
            this.obj = obj;
            this.key = key;
        }

        @Override
        public Object eval(Frame f) {
            Object o = obj.eval(f);
            if (key.type == VarType.INT) {
                int k = key.evalInt(f);
                if (o instanceof JArray a) return a.get(k);
                return Ops.index(o, k);
            }
            return Ops.index(o, key.eval(f));
        }
    }

    // ---------------- calls ----------------

    public static final class Call extends Expr {
        public final Expr callee;
        public final Expr[] args;
        /** Declared function the call is statically bound to (for a direct typed call in Tier 1). */
        public FunctionNode target;
        /**
         * The target name is a declared function that is never assigned to (the parser checks this).
         * Then Tier 1 calls the body directly, without the "the variable still holds the same function" check.
         * Not set for top-level names in REPL/ScriptEngine: the host may replace them.
         */
        public boolean fixedTarget;

        public Call(Expr callee, Expr[] args, int line) {
            super(line);
            this.callee = callee;
            this.args = args;
        }

        @Override
        public Object eval(Frame f) {
            Object fn = callee.eval(f);
            Expr[] a = args;
            Object[] vals = new Object[a.length];
            for (int i = 0; i < a.length; i++) vals[i] = a[i].eval(f);
            if (fn instanceof JFunction jf) return jf.call(vals);
            throw new JmpError("Attempt to call " + Ops.typeName(fn) + " (" + describe(callee) + ")");
        }

        private static String describe(Expr e) {
            if (e instanceof Member m) return m.name;
            if (e instanceof Local l) return "local #" + l.slot;
            return e.getClass().getSimpleName();
        }
    }

    /** len(x) / str(x) / type(x) with the builtin recognized by reference: direct call, static result type. */
    public static final class BuiltinCall extends Expr {
        /**
         * TO_INT/TO_LONG/TO_DOUBLE - the builtins `int(x)`, `long(x)`, `double(x)` over an argument
         * with a known numeric type: then it is just a cast (`d2i` etc.), not a function call
         * through Object[] with boxing. Previously `int c = int(r.c)` in the hot loop of
         * oop_construct cost an array allocation, a Double box and a generic JFunction.call on every
         * iteration. With an argument of type dyn the ordinary call remains: there the result may be null
         * (a string that does not parse).
         */
        public static final int LEN = 0, STR = 1, TYPE = 2, TO_INT = 3, TO_LONG = 4, TO_DOUBLE = 5;
        /**
         * The same builtins over a dyn argument: still a node rather than a generic call, so the
         * compiler can turn it into a cast when the argument turns out typed under a guard (a table
         * field in a region) and call Builtins.toInt otherwise. The static type stays dyn.
         */
        public static final int DYN_INT = 6, DYN_LONG = 7, DYN_DOUBLE = 8;
        public final int kind;
        public final Expr arg;

        public BuiltinCall(int kind, Expr arg, int line) {
            super(line);
            this.kind = kind;
            this.arg = arg;
            this.type = switch (kind) {
                case LEN, TO_INT -> VarType.INT;
                case TO_LONG -> VarType.LONG;
                case TO_DOUBLE -> VarType.DOUBLE;
                case DYN_INT, DYN_LONG, DYN_DOUBLE -> VarType.DYN;
                default -> VarType.STRING;
            };
        }

        @Override
        public Object eval(Frame f) {
            return switch (kind) {
                case LEN -> Ops.len(arg.eval(f));
                case STR -> Ops.str(arg.eval(f));
                case TO_INT -> evalInt(f);
                case TO_LONG -> evalLong(f);
                case TO_DOUBLE -> evalDouble(f);
                case DYN_INT -> me.padej.jumper.runtime.Builtins.toInt(arg.eval(f));
                case DYN_LONG -> me.padej.jumper.runtime.Builtins.toLong(arg.eval(f));
                case DYN_DOUBLE -> me.padej.jumper.runtime.Builtins.toDouble(arg.eval(f));
                default -> Ops.typeName(arg.eval(f));
            };
        }

        @Override
        public int evalInt(Frame f) {
            return switch (kind) {
                case LEN -> Ops.len(arg.eval(f));
                case TO_INT -> switch (arg.type) {
                    case INT -> arg.evalInt(f);
                    case LONG -> (int) arg.evalLong(f);
                    case DOUBLE -> (int) arg.evalDouble(f);
                    default -> super.evalInt(f);
                };
                default -> super.evalInt(f);
            };
        }

        @Override
        public long evalLong(Frame f) {
            return switch (kind) {
                case LEN, TO_INT -> evalInt(f);
                case TO_LONG -> switch (arg.type) {
                    case INT -> arg.evalInt(f);
                    case LONG -> arg.evalLong(f);
                    case DOUBLE -> (long) arg.evalDouble(f);
                    default -> super.evalLong(f);
                };
                default -> super.evalLong(f);
            };
        }

        @Override
        public double evalDouble(Frame f) {
            return switch (kind) {
                case LEN, TO_INT -> evalInt(f);
                case TO_LONG -> evalLong(f);
                case TO_DOUBLE -> switch (arg.type) {
                    case INT -> arg.evalInt(f);
                    case LONG -> arg.evalLong(f);
                    case DOUBLE -> arg.evalDouble(f);
                    default -> super.evalDouble(f);
                };
                default -> super.evalDouble(f);
            };
        }
    }

    /** obj.name(args): a function field of a table, a static method of a Java class or a method of a Java object. */
    public static final class MethodCall extends Expr {
        public final Expr obj;
        public final String name;
        public final Expr[] args;
        /** Static class of the receiver (`Vec v; v.dot(...)`): Tier 1 calls the method directly with a class check. */
        public Classes.ClassNode recvClass;

        // inline cache: receiver class + argument classes -> selected method
        private Class<?> cachedClass;
        private Class<?>[] cachedArgClasses;
        private Method cachedMethod;
        private final FieldCache fieldCache;

        public MethodCall(Expr obj, String name, Expr[] args, int line) {
            super(line);
            this.obj = obj;
            this.name = name;
            this.args = args;
            this.fieldCache = new FieldCache(name);
        }

        @Override
        public Object eval(Frame f) {
            Object recv = obj.eval(f);
            Expr[] a = args;
            Object[] vals = new Object[a.length];
            for (int i = 0; i < a.length; i++) vals[i] = a[i].eval(f);
            return invoke(recv, vals);
        }

        /** Dispatch with caches - interpreter. */
        public Object invoke(Object recv, Object[] vals) {
            if (recv instanceof JTable || recv instanceof JArray || recv instanceof JClass || recv == null) return invokeDynamic(recv, name, fieldCache, vals);
            if (recv instanceof JavaClass jc) return invokeCached(jc.cls(), null, vals, true);
            return invokeCached(recv.getClass(), recv, vals, false);
        }

        /** Non-Java receivers (a table with a function field, builtin array methods) and generic errors. Shared with Tier 1. */
        public static Object invokeDynamic(Object recv, String name, FieldCache fieldCache, Object[] vals) {
            if (recv instanceof JTable t) {
                if (t.cls() != null) {
                    JFunction m = t.cls().findMethod(name);
                    if (m != null) return m.call(JClass.withThis(t, vals));
                }
                Object fn = fieldCache.get(t);
                if (fn instanceof JFunction jf) return jf.call(vals);
                throw new JmpError("Attempt to call field '" + name + "' (a " + Ops.typeName(fn) + " value)");
            }
            if (recv instanceof JClass jc) {
                Object f = jc.staticGet(name);
                if (f instanceof JFunction fn) return fn.call(vals);
                throw new JmpError("Static member '" + name + "' of " + jc.name + " is not a function");
            }
            if (recv == null) throw new JmpError("Attempt to call method '" + name + "' on null");
            if (recv instanceof JArray arr) {
                switch (name) {
                    case "add", "push" -> { for (Object v : vals) arr.add(v); return null; }
                    case "pop" -> { return arr.pop(); }
                    case "size" -> { return arr.size(); }
                }
                return Interop.invoke(arr, name, vals);
            }
            if (recv instanceof JavaClass jc) return Interop.invokeStatic(jc.cls(), name, vals);
            return Interop.invoke(recv, name, vals);
        }

        private Object invokeCached(Class<?> cls, Object recv, Object[] vals, boolean isStatic) {
            Method m = cachedMethod;
            if (m != null && cachedClass == cls && sameArgClasses(vals)) {
                return Interop.invokeMethod(m, recv, vals);
            }
            Method[] cands = Interop.candidates(cls, name, vals.length, isStatic);
            m = Interop.selectMethod(cands, vals, isStatic);
            if (m == null) {
                Interop.checkDenied(cls, name, vals.length);   // the method exists but is closed by the policy - "Access denied"
                if (isStatic) {
                    Object r = Interop.classMember(cls, name, vals);   // Foo.getName(): a question to the class value
                    if (r != Interop.NO_CLASS_MEMBER) return r;
                }
                if (!isStatic) {
                    // a function field of a Java object? (e.g. a Map with a function value)
                    throw new JmpError("No method '" + name + "' with " + vals.length + " args on " + cls.getName());
                }
                throw new JmpError("No static method '" + name + "' with " + vals.length + " args on " + cls.getName());
            }
            Class<?>[] ac = new Class<?>[vals.length];
            for (int i = 0; i < vals.length; i++) ac[i] = vals[i] == null ? null : vals[i].getClass();
            cachedClass = cls;
            cachedArgClasses = ac;
            cachedMethod = m;
            return Interop.invokeMethod(m, recv, vals);
        }

        private boolean sameArgClasses(Object[] vals) {
            Class<?>[] ac = cachedArgClasses;
            for (int i = 0; i < vals.length; i++) {
                Object v = vals[i];
                if ((v == null ? null : v.getClass()) != ac[i]) return false;
            }
            return true;
        }
    }

    /** new int[n] / new Foo[n] / new int[]{...} - a real Java array (for interop). */
    public static final class NewArray extends Expr {
        public final Class<?> comp;
        public final Expr size;        // null if items is present
        public final Expr[] items;     // null if size is present

        public NewArray(Class<?> comp, Expr size, Expr[] items, int line) {
            super(line);
            this.arrayComp = comp;
            this.comp = comp;
            this.size = size;
            this.items = items;
        }

        @Override
        public Object eval(Frame f) {
            if (items == null) return java.lang.reflect.Array.newInstance(comp, size.evalInt(f));
            Object a = java.lang.reflect.Array.newInstance(comp, items.length);
            for (int i = 0; i < items.length; i++) java.lang.reflect.Array.set(a, i, Interop.convert(comp, items[i].eval(f)));
            return a;
        }

        /** Called from Tier 1. */
        public Object create(int n) {
            return java.lang.reflect.Array.newInstance(comp, n);
        }

        public Object fill(Object[] vals) {
            Object a = java.lang.reflect.Array.newInstance(comp, vals.length);
            for (int i = 0; i < vals.length; i++) java.lang.reflect.Array.set(a, i, Interop.convert(comp, vals[i]));
            return a;
        }
    }

    public static final class New extends Expr {
        public final Expr cls;
        public final Expr[] args;

        // inline cache for a Java class: class + argument classes -> selected constructor
        private Class<?> cachedClass;
        private Class<?>[] cachedArgClasses;
        private java.lang.reflect.Constructor<?> cachedCtor;

        public New(Expr cls, Expr[] args, int line) {
            super(line);
            this.cls = cls;
            this.args = args;
        }

        @Override
        public Object eval(Frame f) {
            Object c = cls.eval(f);
            Object[] vals = new Object[args.length];
            for (int i = 0; i < args.length; i++) vals[i] = args[i].eval(f);
            if (c instanceof JavaClass jc) {
                java.lang.reflect.Constructor<?> k = cachedCtor;
                if (k != null && cachedClass == jc.cls() && sameClasses(cachedArgClasses, vals)) return Interop.invokeConstructor(k, vals);
                if (Modifier.isAbstract(jc.cls().getModifiers()) || jc.cls().isInterface())
                    throw new JmpError("Cannot instantiate " + jc.cls().getName());
                k = Interop.selectConstructor(jc.cls(), vals);
                if (k == null) throw new JmpError("No constructor " + jc.cls().getSimpleName() + " with " + vals.length + " args");
                cachedClass = jc.cls();
                cachedArgClasses = classesOf(vals);
                cachedCtor = k;
                return Interop.invokeConstructor(k, vals);
            }
            return construct(c, vals);
        }

        static Class<?>[] classesOf(Object[] vals) {
            Class<?>[] ac = new Class<?>[vals.length];
            for (int i = 0; i < vals.length; i++) ac[i] = vals[i] == null ? null : vals[i].getClass();
            return ac;
        }

        static boolean sameClasses(Class<?>[] ac, Object[] vals) {
            for (int i = 0; i < vals.length; i++) {
                Object v = vals[i];
                if ((v == null ? null : v.getClass()) != ac[i]) return false;
            }
            return true;
        }

        public static Object construct(Object c, Object[] vals) {
            if (c instanceof JClass jc) return jc.instantiate(vals);
            if (c instanceof JavaClass jc) {
                if (Modifier.isAbstract(jc.cls().getModifiers()) || jc.cls().isInterface())
                    throw new JmpError("Cannot instantiate " + jc.cls().getName());
                return Interop.construct(jc.cls(), vals);
            }
            throw new JmpError("'new' expects a class, got " + Ops.typeName(c));
        }
    }

    // ---------------- constructors of Jumper values ----------------

    public static final class Lambda extends Expr {
        public final FunctionNode fn;

        public Lambda(FunctionNode fn, int line) {
            super(line);
            this.fn = fn;
        }

        @Override
        public Object eval(Frame f) {
            return new FunctionNode.ScriptFunction(fn, f);
        }
    }

    public static final class TableLit extends Expr {
        public final Expr[] keys, values;
        /** Shape of the literal, if all keys are constants without duplicates (computed once). */
        private final Shape shape;

        public TableLit(Expr[] keys, Expr[] values, int line) {
            super(line);
            this.keys = keys;
            this.values = values;
            Shape s = Shape.ROOT;
            if (keys.length <= Shape.MAX_KEYS) {
                for (Expr k : keys) {
                    Object c = constKey(k);
                    if (c == null || s.indexOf(c) >= 0) { s = null; break; }
                    s = s.child(c);
                }
            } else s = null;
            this.shape = s;
        }

        private static Object constKey(Expr k) {
            if (k instanceof Literal l) return l.value;
            if (k instanceof Prims.IntLit i) return i.v;
            return null;
        }

        @Override
        public Object eval(Frame f) {
            Expr[] vs = values;
            if (shape != null) {
                Object[] arr = new Object[Math.max(4, vs.length)];
                for (int i = 0; i < vs.length; i++) arr[i] = vs[i].eval(f);
                return build(arr);
            }
            JTable t = JTable.plain(keys.length);
            for (int i = 0; i < keys.length; i++) t.put(keys[i].eval(f), vs[i].eval(f));
            return t;
        }

        /** Build the table from already evaluated values (order as in the literal). Only with shape != null. */
        public Object build(Object[] arr) {
            int n = values.length;
            for (int i = 0; i < n; i++) {
                if (arr[i] == null) { // null values = absent key: build the ordinary way
                    JTable t = JTable.plain(n);
                    for (int j = 0; j < n; j++) t.put(shape.keyAt(j), arr[j]);
                    return t;
                }
            }
            return JTable.literal(shape, arr.length >= 4 ? arr : java.util.Arrays.copyOf(arr, 4));
        }

        public boolean hasShape() {
            return shape != null;
        }

        /** The literal's untyped shape (null when keys are not constants). */
        public Shape shape() {
            return shape;
        }
    }

    public static final class ArrayLit extends Expr {
        public final Expr[] items;

        public ArrayLit(Expr[] items, int line) {
            super(line);
            this.items = items;
        }

        @Override
        public Object eval(Frame f) {
            Object[] vals = new Object[Math.max(items.length, 4)];
            for (int i = 0; i < items.length; i++) vals[i] = items[i].eval(f);
            return new JArray(vals, items.length);
        }
    }
}
