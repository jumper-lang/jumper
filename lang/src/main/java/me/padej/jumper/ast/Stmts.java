package me.padej.jumper.ast;

import me.padej.jumper.interp.Frame;
import me.padej.jumper.runtime.JArray;
import me.padej.jumper.runtime.JmpError;
import me.padej.jumper.runtime.JmpThrow;
import me.padej.jumper.runtime.JTable;
import me.padej.jumper.runtime.Ops;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;

/** All statement nodes. */
public final class Stmts {
    private Stmts() {}

    public static final class Block extends Stmt {
        public final Stmt[] stmts;

        public Block(Stmt[] stmts, int line) {
            super(line);
            this.stmts = stmts;
        }

        @Override
        public int exec(Frame f) {
            Stmt[] ss = stmts;
            for (int i = 0; i < ss.length; i++) {
                Stmt s = ss[i];
                int st;
                try {
                    st = s.exec(f);
                } catch (JmpError e) {
                    throw e.at(s.line);
                } catch (me.padej.jumper.runtime.ScriptSecurityException e) {
                    throw e.at(s.line);
                } catch (StackOverflowError e) {
                    throw Ops.stackOverflow().at(s.line);
                } catch (ArithmeticException e) {
                    throw new JmpError("Division by zero", e).at(s.line);
                } catch (ClassCastException | IndexOutOfBoundsException | NullPointerException | IllegalArgumentException e) {
                    throw JmpError.wrap(e).at(s.line);
                }
                if (st != NORMAL) return st;
            }
            return NORMAL;
        }
    }

    public static final class ExprStmt extends Stmt {
        public final Expr e;

        public ExprStmt(Expr e, int line) {
            super(line);
            this.e = e;
        }

        @Override
        public int exec(Frame f) {
            e.eval(f);
            return NORMAL;
        }
    }

    public static final class VarDecl extends Stmt {
        public final int slot;
        public final VarType type;
        public final Expr init; // may be null

        public VarDecl(int slot, VarType type, Expr init, int line) {
            super(line);
            this.slot = slot;
            this.type = type;
            this.init = init;
        }

        @Override
        public int exec(Frame f) {
            Object v = init == null ? type.defaultValue() : init.eval(f);
            f.slots[slot] = type == VarType.DYN ? v : type.coerce(v);
            return NORMAL;
        }
    }

    /** Named function declaration: creates a closure and puts it into the slot. */
    public static final class FuncDecl extends Stmt {
        public final int slot;
        public final FunctionNode fn;

        public FuncDecl(int slot, FunctionNode fn, int line) {
            super(line);
            this.slot = slot;
            this.fn = fn;
        }

        @Override
        public int exec(Frame f) {
            f.slots[slot] = new FunctionNode.ScriptFunction(fn, f);
            return NORMAL;
        }
    }

    public static final class If extends Stmt {
        public final Expr cond;
        public final Stmt then, otherwise; // otherwise may be null

        public If(Expr cond, Stmt then, Stmt otherwise, int line) {
            super(line);
            this.cond = cond;
            this.then = then;
            this.otherwise = otherwise;
        }

        @Override
        public int exec(Frame f) {
            if (cond.evalBool(f)) return then.exec(f);
            if (otherwise != null) return otherwise.exec(f);
            return NORMAL;
        }
    }

    /** Loop cancellation point (Tier 0): null - the host did not enable cancellation, no check. */
    public static me.padej.jumper.jit.ScriptLoader cancelOf(me.padej.jumper.jit.ScriptLoader l) {
        return l != null && l.cancellable ? l : null;
    }

    public static final class While extends Stmt {
        public final Expr cond;
        public final Stmt body;
        public me.padej.jumper.jit.ScriptLoader cancel;

        public While(Expr cond, Stmt body, int line) {
            super(line);
            this.cond = cond;
            this.body = body;
        }

        @Override
        public int exec(Frame f) {
            while (cond.evalBool(f)) {
                if (cancel != null && cancel.cancelled) throw Ops.cancelled();
                int st = body.exec(f);
                if (st == BREAK) break;
                if (st == RETURN) return RETURN;
            }
            return NORMAL;
        }
    }

    public static final class DoWhile extends Stmt {
        public final Expr cond;
        public final Stmt body;
        public me.padej.jumper.jit.ScriptLoader cancel;

        public DoWhile(Stmt body, Expr cond, int line) {
            super(line);
            this.cond = cond;
            this.body = body;
        }

        @Override
        public int exec(Frame f) {
            do {
                if (cancel != null && cancel.cancelled) throw Ops.cancelled();
                int st = body.exec(f);
                if (st == BREAK) break;
                if (st == RETURN) return RETURN;
            } while (cond.evalBool(f));
            return NORMAL;
        }
    }

    public static final class For extends Stmt {
        public me.padej.jumper.jit.ScriptLoader cancel;
        public final Stmt init;   // null allowed
        public final Expr cond;   // null -> true
        public final Expr update; // null allowed
        public final Stmt body;

        public For(Stmt init, Expr cond, Expr update, Stmt body, int line) {
            super(line);
            this.init = init;
            this.cond = cond;
            this.update = update;
            this.body = body;
        }

        @Override
        public int exec(Frame f) {
            if (init != null) init.exec(f);
            Expr c = cond, u = update;
            Stmt b = body;
            while (c == null || c.evalBool(f)) {
                if (cancel != null && cancel.cancelled) throw Ops.cancelled();
                int st = b.exec(f);
                if (st == BREAK) break;
                if (st == RETURN) return RETURN;
                if (u != null) u.eval(f);
            }
            return NORMAL;
        }
    }

    /** for (var x : iterable) - arrays, tables (keys), Iterable, Map (keys), String (characters). */
    public static final class ForEach extends Stmt {
        public me.padej.jumper.jit.ScriptLoader cancel;
        public final int slot;
        public final VarType type;
        public final Expr iterable;
        public final Stmt body;
        /** `for (Vec v : ...)` - class check of the element. */
        public Classes.ClassNode cls;

        public ForEach(int slot, VarType type, Expr iterable, Stmt body, int line) {
            super(line);
            this.slot = slot;
            this.type = type;
            this.iterable = iterable;
            this.body = body;
        }

        private void store(Frame f, Object v) {
            if (type.isPrimitive()) f.p[slot] = type.toBits(v);
            else if (cls != null) f.slots[slot] = Ops.checkClass(v, cls);
            else f.slots[slot] = type == VarType.DYN ? v : type.coerce(v);
        }

        @Override
        public int exec(Frame f) {
            Object it = iterable.eval(f);
            if (it instanceof JArray arr) {
                for (int i = 0; i < arr.size(); i++) {
                    if (cancel != null && cancel.cancelled) throw Ops.cancelled();
                    store(f, arr.get(i));
                    int st = body.exec(f);
                    if (st == BREAK) break;
                    if (st == RETURN) return RETURN;
                }
                return NORMAL;
            }
            // the same iteration as Tier 1's: Java array elements normalized (char -> String, float -> double...)
            Iterator<?> iter = Ops.iter(it);
            while (iter.hasNext()) {
                if (cancel != null && cancel.cancelled) throw Ops.cancelled();
                store(f, iter.next());
                int st = body.exec(f);
                if (st == BREAK) break;
                if (st == RETURN) return RETURN;
            }
            return NORMAL;
        }
    }

    public static final class Return extends Stmt {
        public final Expr value; // null allowed

        public Return(Expr value, int line) {
            super(line);
            this.value = value;
        }

        @Override
        public int exec(Frame f) {
            f.ret = value == null ? null : value.eval(f);
            return RETURN;
        }
    }

    public static final class Break extends Stmt {
        public Break(int line) {
            super(line);
        }

        @Override
        public int exec(Frame f) {
            return BREAK;
        }
    }

    public static final class Continue extends Stmt {
        public Continue(int line) {
            super(line);
        }

        @Override
        public int exec(Frame f) {
            return CONTINUE;
        }
    }

    /** throw expr; */
    public static final class Throw extends Stmt {
        public final Expr value;

        public Throw(Expr value, int line) {
            super(line);
            this.value = value;
        }

        @Override
        public int exec(Frame f) {
            RuntimeException e = JmpThrow.raise(value.eval(f));
            if (e instanceof JmpError je) je.at(line);
            throw e;
        }
    }

    /**
     * try { } catch (e) { } finally { }. catch catches any Throwable (the thrown value is unwrapped);
     * finally always runs, its return/break overrides the result of try/catch.
     */
    public static final class Try extends Stmt {
        public final Stmt body;
        public final int catchSlot;   // -1 if there is no catch
        public final Stmt handler;    // null if there is no catch
        public final Stmt fin;        // null if there is no finally

        public Try(Stmt body, int catchSlot, Stmt handler, Stmt fin, int line) {
            super(line);
            this.body = body;
            this.catchSlot = catchSlot;
            this.handler = handler;
            this.fin = fin;
        }

        @Override
        public int exec(Frame f) {
            int st;
            try {
                try {
                    st = body.exec(f);
                } catch (RuntimeException | Error t0) {
                    // a policy violation or a cancellation is not the script's to catch (Ops.fatal)
                    if (handler == null || Ops.fatal(t0)) throw t0;
                    Throwable t = t0 instanceof StackOverflowError ? new JmpError("Stack overflow") : t0;
                    f.slots[catchSlot] = JmpThrow.caught(t);
                    st = handler.exec(f);
                }
            } catch (RuntimeException | Error t) {
                // finally on the way out of an exception - except a fatal one: a finally with a return
                // or a break would swallow it, one with a loop or a call would outlive the cancellation
                if (fin != null && !Ops.fatal(t)) {
                    int fst = fin.exec(f);
                    if (fst != NORMAL) return fst; // finally overrides (and swallows the exception, as in Java)
                }
                throw t;
            }
            if (fin != null) {
                Object savedRet = f.ret;
                int fst = fin.exec(f);
                if (fst != NORMAL) return fst; // finally overrides the result of try/catch
                f.ret = savedRet;
            }
            return st;
        }
    }

    public static final class Empty extends Stmt {
        public Empty(int line) {
            super(line);
        }

        @Override
        public int exec(Frame f) {
            return NORMAL;
        }
    }
}
