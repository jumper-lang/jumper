package me.padej.jumper.runtime;

/**
 * A stack overflow in a sandboxed script (one that runs under an access policy). Fatal there, like a
 * cancellation ({@link Ops#fatal}): a script that could catch its own overflow would recurse again from
 * the catch - {@code void f() { try { f(); } catch (e) { f(); } }} is exponential, a thread the host never
 * gets back. Without a policy a stack overflow stays an ordinary, catchable error.
 *
 * <p>Preallocated and without a stack trace: it is thrown where there is no stack left.
 */
public final class JmpStackOverflow extends JmpError {
    public static final JmpStackOverflow INSTANCE = new JmpStackOverflow();

    private JmpStackOverflow() {
        super("Stack overflow", true);
    }

    /** Shared by every thread: no line is recorded in it. */
    @Override
    public JmpError at(int line) {
        return this;
    }
}
