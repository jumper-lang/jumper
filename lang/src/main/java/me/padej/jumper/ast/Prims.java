package me.padej.jumper.ast;

import me.padej.jumper.interp.Frame;
import me.padej.jumper.lexer.TokenType;
import me.padej.jumper.runtime.JmpError;

/**
 * Nodes with a primitive static type. Values live in Frame.p[] and in the primitive
 * return values of evalInt/evalLong/evalDouble/evalBool - boxing happens only at the boundary
 * with dynamic code (eval()).
 */
public final class Prims {
    private Prims() {}

    // ---------- base classes: boxing and type widening ----------

    public abstract static class IntExpr extends Expr {
        protected IntExpr(int line) { super(line); type = VarType.INT; }
        @Override public abstract int evalInt(Frame f);
        @Override public Object eval(Frame f) { return evalInt(f); }
        @Override public long evalLong(Frame f) { return evalInt(f); }
        @Override public double evalDouble(Frame f) { return evalInt(f); }
        @Override public boolean evalBool(Frame f) { evalInt(f); return true; }
    }

    public abstract static class LongExpr extends Expr {
        protected LongExpr(int line) { super(line); type = VarType.LONG; }
        @Override public abstract long evalLong(Frame f);
        @Override public Object eval(Frame f) { return evalLong(f); }
        @Override public int evalInt(Frame f) { throw new JmpError("Expected int, got long"); }
        @Override public double evalDouble(Frame f) { return evalLong(f); }
        @Override public boolean evalBool(Frame f) { evalLong(f); return true; }
    }

    public abstract static class DoubleExpr extends Expr {
        protected DoubleExpr(int line) { super(line); type = VarType.DOUBLE; }
        @Override public abstract double evalDouble(Frame f);
        @Override public Object eval(Frame f) { return evalDouble(f); }
        @Override public int evalInt(Frame f) { throw new JmpError("Expected int, got double"); }
        @Override public long evalLong(Frame f) { throw new JmpError("Expected long, got double"); }
        @Override public boolean evalBool(Frame f) { evalDouble(f); return true; }
    }

    public abstract static class BoolExpr extends Expr {
        protected BoolExpr(int line) { super(line); type = VarType.BOOLEAN; }
        @Override public abstract boolean evalBool(Frame f);
        @Override public Object eval(Frame f) { return evalBool(f); }
    }

    // ---------- variables ----------

    public static final class IntLocal extends IntExpr {
        public final int idx, depth;
        public IntLocal(int idx, int depth, int line) { super(line); this.idx = idx; this.depth = depth; }
        @Override public int evalInt(Frame f) { return (int) (depth == 0 ? f : f.up(depth)).p[idx]; }
    }

    public static final class LongLocal extends LongExpr {
        public final int idx, depth;
        public LongLocal(int idx, int depth, int line) { super(line); this.idx = idx; this.depth = depth; }
        @Override public long evalLong(Frame f) { return (depth == 0 ? f : f.up(depth)).p[idx]; }
    }

    public static final class DoubleLocal extends DoubleExpr {
        public final int idx, depth;
        public DoubleLocal(int idx, int depth, int line) { super(line); this.idx = idx; this.depth = depth; }
        @Override public double evalDouble(Frame f) { return Double.longBitsToDouble((depth == 0 ? f : f.up(depth)).p[idx]); }
    }

    public static final class BoolLocal extends BoolExpr {
        public final int idx, depth;
        public BoolLocal(int idx, int depth, int line) { super(line); this.idx = idx; this.depth = depth; }
        @Override public boolean evalBool(Frame f) { return (depth == 0 ? f : f.up(depth)).p[idx] != 0; }
    }

    /** Index and depth of a primitive variable - common interface for the parser. */
    public static int[] location(Expr e) {
        if (e instanceof IntLocal l) return new int[]{l.idx, l.depth};
        if (e instanceof LongLocal l) return new int[]{l.idx, l.depth};
        if (e instanceof DoubleLocal l) return new int[]{l.idx, l.depth};
        if (e instanceof BoolLocal l) return new int[]{l.idx, l.depth};
        return null;
    }

    // ---------- assignment ----------

    public static final class IntAssign extends IntExpr {
        public final int idx, depth; public final Expr value;
        public IntAssign(int idx, int depth, Expr value, int line) { super(line); this.idx = idx; this.depth = depth; this.value = value; }
        @Override public int evalInt(Frame f) { int v = value.evalInt(f); (depth == 0 ? f : f.up(depth)).p[idx] = v; return v; }
    }

    public static final class LongAssign extends LongExpr {
        public final int idx, depth; public final Expr value;
        public LongAssign(int idx, int depth, Expr value, int line) { super(line); this.idx = idx; this.depth = depth; this.value = value; }
        @Override public long evalLong(Frame f) { long v = value.evalLong(f); (depth == 0 ? f : f.up(depth)).p[idx] = v; return v; }
    }

    public static final class DoubleAssign extends DoubleExpr {
        public final int idx, depth; public final Expr value;
        public DoubleAssign(int idx, int depth, Expr value, int line) { super(line); this.idx = idx; this.depth = depth; this.value = value; }
        @Override public double evalDouble(Frame f) { double v = value.evalDouble(f); (depth == 0 ? f : f.up(depth)).p[idx] = Double.doubleToRawLongBits(v); return v; }
    }

    public static final class BoolAssign extends BoolExpr {
        public final int idx, depth; public final Expr value;
        public BoolAssign(int idx, int depth, Expr value, int line) { super(line); this.idx = idx; this.depth = depth; this.value = value; }
        @Override public boolean evalBool(Frame f) { boolean v = value.evalBool(f); (depth == 0 ? f : f.up(depth)).p[idx] = v ? 1 : 0; return v; }
    }

    public static Expr assign(VarType t, int idx, int depth, Expr value, int line) {
        return switch (t) {
            case INT -> new IntAssign(idx, depth, value, line);
            case LONG -> new LongAssign(idx, depth, value, line);
            case DOUBLE -> new DoubleAssign(idx, depth, value, line);
            case BOOLEAN -> new BoolAssign(idx, depth, value, line);
            default -> throw new IllegalArgumentException();
        };
    }

    /** i++ / --i for an int variable. */
    public static final class IntInc extends IntExpr {
        public final int idx, depth, delta; public final boolean prefix;
        public IntInc(int idx, int depth, int delta, boolean prefix, int line) { super(line); this.idx = idx; this.depth = depth; this.delta = delta; this.prefix = prefix; }
        @Override public int evalInt(Frame f) {
            long[] p = (depth == 0 ? f : f.up(depth)).p;
            int old = (int) p[idx];
            int nv = old + delta;
            p[idx] = nv;
            return prefix ? nv : old;
        }
    }

    // ---------- declaration ----------

    public static final class PrimVarDecl extends Stmt {
        public final int idx; public final VarType t; public final Expr init;
        public PrimVarDecl(int idx, VarType t, Expr init, int line) { super(line); this.idx = idx; this.t = t; this.init = init; }
        @Override public int exec(Frame f) {
            long bits;
            if (init == null) bits = 0;
            else bits = switch (t) {
                case INT -> init.evalInt(f);
                case LONG -> init.evalLong(f);
                case DOUBLE -> Double.doubleToRawLongBits(init.evalDouble(f));
                case BOOLEAN -> init.evalBool(f) ? 1 : 0;
                default -> throw new IllegalStateException();
            };
            f.p[idx] = bits;
            return NORMAL;
        }
    }

    // ---------- literals ----------

    public static final class IntLit extends IntExpr {
        public final int v;
        public IntLit(int v, int line) { super(line); this.v = v; }
        @Override public int evalInt(Frame f) { return v; }
    }

    public static final class LongLit extends LongExpr {
        public final long v;
        public LongLit(long v, int line) { super(line); this.v = v; }
        @Override public long evalLong(Frame f) { return v; }
    }

    public static final class DoubleLit extends DoubleExpr {
        public final double v;
        public DoubleLit(double v, int line) { super(line); this.v = v; }
        @Override public double evalDouble(Frame f) { return v; }
    }

    public static final class BoolLit extends BoolExpr {
        public final boolean v;
        public BoolLit(boolean v, int line) { super(line); this.v = v; }
        @Override public boolean evalBool(Frame f) { return v; }
    }

    // ---------- arithmetic ----------

    static final int ADD = 0, SUB = 1, MUL = 2, DIV = 3, MOD = 4, AND = 5, OR = 6, XOR = 7, SHL = 8, SHR = 9, USHR = 10;

    public static int opcode(TokenType t) {
        return switch (t) {
            case PLUS, PLUSEQ -> ADD;
            case MINUS, MINUSEQ -> SUB;
            case STAR, STAREQ -> MUL;
            case SLASH, SLASHEQ -> DIV;
            case PERCENT, PERCENTEQ -> MOD;
            case AMP -> AND;
            case PIPE -> OR;
            case CARET -> XOR;
            case SHL -> SHL;
            case SHR -> SHR;
            case USHR -> USHR;
            default -> -1;
        };
    }

    public static final class IntBin extends IntExpr {
        public final int op; public final Expr l, r;
        public IntBin(int op, Expr l, Expr r, int line) { super(line); this.op = op; this.l = l; this.r = r; }
        @Override public int evalInt(Frame f) {
            int a = l.evalInt(f), b = r.evalInt(f);
            return switch (op) {
                case ADD -> a + b;
                case SUB -> a - b;
                case MUL -> a * b;
                case DIV -> { if (b == 0) throw new JmpError("Division by zero"); yield a / b; }
                case MOD -> { if (b == 0) throw new JmpError("Division by zero"); yield a % b; }
                case AND -> a & b;
                case OR -> a | b;
                case XOR -> a ^ b;
                case SHL -> a << b;
                case SHR -> a >> b;
                case USHR -> a >>> b;
                default -> throw new IllegalStateException();
            };
        }
    }

    public static final class LongBin extends LongExpr {
        public final int op; public final Expr l, r;
        public LongBin(int op, Expr l, Expr r, int line) { super(line); this.op = op; this.l = l; this.r = r; }
        @Override public long evalLong(Frame f) {
            long a = l.evalLong(f), b = r.evalLong(f);
            return switch (op) {
                case ADD -> a + b;
                case SUB -> a - b;
                case MUL -> a * b;
                case DIV -> { if (b == 0) throw new JmpError("Division by zero"); yield a / b; }
                case MOD -> { if (b == 0) throw new JmpError("Division by zero"); yield a % b; }
                case AND -> a & b;
                case OR -> a | b;
                case XOR -> a ^ b;
                case SHL -> a << (int) b;
                case SHR -> a >> (int) b;
                case USHR -> a >>> (int) b;
                default -> throw new IllegalStateException();
            };
        }
    }

    public static final class DoubleBin extends DoubleExpr {
        public final int op; public final Expr l, r;
        public DoubleBin(int op, Expr l, Expr r, int line) { super(line); this.op = op; this.l = l; this.r = r; }
        @Override public double evalDouble(Frame f) {
            double a = l.evalDouble(f), b = r.evalDouble(f);
            return switch (op) {
                case ADD -> a + b;
                case SUB -> a - b;
                case MUL -> a * b;
                case DIV -> a / b;
                case MOD -> a % b;
                default -> throw new IllegalStateException();
            };
        }
    }

    public static final class IntNeg extends IntExpr {
        public final Expr e;
        public IntNeg(Expr e, int line) { super(line); this.e = e; }
        @Override public int evalInt(Frame f) { return -e.evalInt(f); }
    }

    public static final class LongNeg extends LongExpr {
        public final Expr e;
        public LongNeg(Expr e, int line) { super(line); this.e = e; }
        @Override public long evalLong(Frame f) { return -e.evalLong(f); }
    }

    public static final class DoubleNeg extends DoubleExpr {
        public final Expr e;
        public DoubleNeg(Expr e, int line) { super(line); this.e = e; }
        @Override public double evalDouble(Frame f) { return -e.evalDouble(f); }
    }

    // ---------- comparisons ----------

    static final int LT = 0, LE = 1, GT = 2, GE = 3, EQ = 4, NE = 5;

    public static int cmpcode(TokenType t) {
        return switch (t) {
            case LT -> LT;
            case LE -> LE;
            case GT -> GT;
            case GE -> GE;
            case EQEQ -> EQ;
            case NE -> NE;
            default -> -1;
        };
    }

    public static final class IntCmp extends BoolExpr {
        public final int op; public final Expr l, r;
        public IntCmp(int op, Expr l, Expr r, int line) { super(line); this.op = op; this.l = l; this.r = r; }
        @Override public boolean evalBool(Frame f) {
            int a = l.evalInt(f), b = r.evalInt(f);
            return switch (op) {
                case LT -> a < b;
                case LE -> a <= b;
                case GT -> a > b;
                case GE -> a >= b;
                case EQ -> a == b;
                case NE -> a != b;
                default -> throw new IllegalStateException();
            };
        }
    }

    public static final class LongCmp extends BoolExpr {
        public final int op; public final Expr l, r;
        public LongCmp(int op, Expr l, Expr r, int line) { super(line); this.op = op; this.l = l; this.r = r; }
        @Override public boolean evalBool(Frame f) {
            long a = l.evalLong(f), b = r.evalLong(f);
            return switch (op) {
                case LT -> a < b;
                case LE -> a <= b;
                case GT -> a > b;
                case GE -> a >= b;
                case EQ -> a == b;
                case NE -> a != b;
                default -> throw new IllegalStateException();
            };
        }
    }

    public static final class DoubleCmp extends BoolExpr {
        public final int op; public final Expr l, r;
        public DoubleCmp(int op, Expr l, Expr r, int line) { super(line); this.op = op; this.l = l; this.r = r; }
        @Override public boolean evalBool(Frame f) {
            double a = l.evalDouble(f), b = r.evalDouble(f);
            return switch (op) {
                case LT -> a < b;
                case LE -> a <= b;
                case GT -> a > b;
                case GE -> a >= b;
                case EQ -> a == b;
                case NE -> a != b;
                default -> throw new IllegalStateException();
            };
        }
    }
}
