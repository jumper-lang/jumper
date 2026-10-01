package me.padej.jumper.runtime;

/** Script runtime error. The line number is added by the interpreter. */
public class JmpError extends RuntimeException {
    private int line = -1;

    public JmpError(String msg) {
        super(msg);
    }

    public JmpError(String msg, Throwable cause) {
        super(msg, cause);
    }

    /** Without a stack trace or suppression: for a preallocated instance (see JmpCancelled). */
    protected JmpError(String msg, boolean stackless) {
        super(msg, null, !stackless, !stackless);
    }

    /** true = a wrapper around a Java exception: a catch in the script receives the Java exception itself. */
    public boolean wrapped;

    /**
     * A Java exception that came out of a call, as the script sees it: a JmpError and the fatal ones
     * ({@link Ops#fatal}: a policy violation, a cancellation - e.g. from a script callback Java called)
     * go on unchanged, anything else is wrapped.
     */
    public static RuntimeException rethrow(Throwable t) {
        if (t instanceof JmpError je) return je;
        if (t instanceof ScriptSecurityException se) return se;
        return wrap(t);
    }

    public static JmpError wrap(Throwable t) {
        JmpError e = new JmpError(t.getClass().getSimpleName() + ": " + t.getMessage(), t);
        e.wrapped = true;
        return e;
    }

    public int line() {
        return line;
    }

    /** Available from the script as e.message (without the line number). */
    public String message() {
        return super.getMessage();
    }

    public JmpError at(int line) {
        if (this.line < 0) this.line = line;
        return this;
    }

    @Override
    public String getMessage() {
        return line >= 0 ? super.getMessage() + " (line " + line + ")" : super.getMessage();
    }
}
