package me.padej.jumper.jit;

import me.padej.jumper.ast.FunctionNode;
import me.padej.jumper.interp.Frame;
import me.padej.jumper.runtime.JFunction;
import me.padej.jumper.runtime.JmpError;

/**
 * Base class of generated functions (Tier 1). The subclass is generated in bytecode and implements
 * invoke(Object[]) - a wrapper that unboxes the arguments and calls the typed body(...).
 */
public abstract class CompiledFunction implements JFunction {
    public final FunctionNode node;
    public final Frame closure;
    /** Constants unavailable through the constant pool: AST nodes, caches, builtin functions. */
    public final Object[] k;
    /** Frame of the last call, if the function was compiled with forceFrame. */
    public Frame lastFrame;

    protected CompiledFunction(FunctionNode node, Frame closure, Object[] k) {
        this.node = node;
        this.closure = closure;
        this.k = k;
    }

    protected abstract Object invoke(Object[] args);

    @Override
    public final Object call(Object[] args) {
        try {
            return invoke(args);
        } catch (JmpError e) {
            throw e.at(lineOf(e));
        } catch (me.padej.jumper.runtime.ScriptSecurityException e) {
            throw e.at(lineOf(e));
        } catch (StackOverflowError e) {
            throw me.padej.jumper.runtime.Ops.stackOverflow();
        } catch (ArithmeticException e) {
            throw new JmpError("Division by zero", e).at(lineOf(e));
        } catch (ClassCastException | IndexOutOfBoundsException | NullPointerException | IllegalArgumentException e) {
            throw JmpError.wrap(e).at(lineOf(e));
        }
    }

    /** Script line number from the LineNumberTable of the generated class. */
    private int lineOf(Throwable e) {
        String me = getClass().getName();
        for (StackTraceElement el : e.getStackTrace()) {
            if (me.equals(el.getClassName()) && el.getLineNumber() > 0) return el.getLineNumber();
        }
        return -1;
    }

    @Override
    public String name() {
        return node.name;
    }

    @Override
    public String toString() {
        return "function " + node.name + " [jit]";
    }
}
