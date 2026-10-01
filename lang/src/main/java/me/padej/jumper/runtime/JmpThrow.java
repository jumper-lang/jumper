package me.padej.jumper.runtime;

/** `throw value` for values that are not Throwable: carries the script value; catch unwraps it. */
public final class JmpThrow extends JmpError {
    public final Object value;

    public JmpThrow(Object value) {
        super(Ops.str(value));
        this.value = value;
    }

    /** What catch (e) sees: the value from throw, or the Java exception itself. */
    public static Object caught(Throwable t) {
        if (Ops.fatal(t)) throw t instanceof StackOverflowError ? Ops.stackOverflow() : (RuntimeException) t;   // not the script's to catch
        if (t instanceof JmpThrow jt) return jt.value;
        if (t instanceof JmpError je && je.wrapped && je.getCause() != null) return je.getCause();
        return t;
    }

    /** throw expr: a Throwable is thrown as is, anything else gets wrapped. */
    public static RuntimeException raise(Object v) {
        if (v instanceof RuntimeException re) return re;
        if (v instanceof Throwable t) return JmpError.wrap(t);   // catch (e) receives t itself
        return new JmpThrow(v);
    }
}
