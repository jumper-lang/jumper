package me.padej.jumper.runtime;

/**
 * A script reached for something the access policy (*.jma) does not allow: a class, a member, a module.
 * A {@link SecurityException}, not a script error: the script cannot catch it ({@code try/catch} and
 * {@code finally} let it through - see {@link Ops#fatal}), it goes straight to the host that runs the
 * script, which decides what a violation means (log it, unload the mod...). Thrown at the moment of
 * linkage - name resolution at parse time, member selection at the first call of a call site (the
 * interpreter's and Tier 1's invokedynamic alike) - never after the forbidden code has run.
 */
public final class ScriptSecurityException extends SecurityException {
    private int line = -1;

    public ScriptSecurityException(String msg) {
        super(msg);
    }

    /** The script line (set once, by the innermost statement that saw it). */
    public ScriptSecurityException at(int line) {
        if (this.line < 0) this.line = line;
        return this;
    }

    public int line() {
        return line;
    }

    @Override
    public String getMessage() {
        return line >= 0 ? super.getMessage() + " (line " + line + ")" : super.getMessage();
    }
}
