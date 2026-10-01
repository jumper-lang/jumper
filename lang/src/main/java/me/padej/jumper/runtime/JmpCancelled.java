package me.padej.jumper.runtime;

/**
 * The host cancelled the script ({@code Interpreter.cancel()}, {@code JmpScriptEngine.cancel()}): thrown at
 * the next loop back-edge or function entry once the volatile flag is set. The script cannot catch it -
 * {@code try/catch} and {@code finally} let it through ({@link Ops#fatal}) - so a script cannot keep
 * itself alive by swallowing its own cancellation.
 *
 * <p>One preallocated instance without a stack trace ({@link #INSTANCE}): throwing it allocates nothing
 * and needs no stack, so it goes up even from the bottom of a recursion that is out of stack - where a
 * new exception would itself be a StackOverflowError, which a script may catch.
 */
public final class JmpCancelled extends JmpError {
    public static final JmpCancelled INSTANCE = new JmpCancelled();

    private JmpCancelled() {
        super("Cancelled", true);
    }

    /** Shared by every thread: no line is recorded in it. */
    @Override
    public JmpError at(int line) {
        return this;
    }
}
