package me.padej.jumper.runtime;

/**
 * Cell of a top-level name in ScriptEngine/REPL mode.
 *
 * <p>Such names used to live directly in {@code Bindings}, and every access from compiled code
 * was a {@code Map.get} by string key: reading a top-level variable in a hot loop cost about
 * 12 ns, calling a top-level function about 7 ns. Now the name is resolved to a cell once, at
 * parse time, and an access becomes a field read through a constant reference - C2 folds the
 * reference itself into a constant, leaving a single load.
 *
 * <p>The semantics do not change at all: {@code engine.put}/{@code get} write and read the same
 * cell, so host and script still see each other.
 */
public final class Cell {
    public final String name;
    /** The value. Write only via {@link #set}: a direct write would not drop the direct calls (see sp). */
    public Object v;
    /** The name is declared (for containsKey/remove: a null value and an absent name are different things). */
    public boolean present;
    /**
     * Direct calls through this cell (Tier 1, `Indy.CellSite`): while the switch point is intact,
     * call sites invoke the function body from the cell as a constant - no cell read, no check,
     * and C2 inlines it into the caller. Any write to the cell invalidates the point and all such
     * sites fall back to the generic path (and later rebind to the new value). null = no direct
     * calls, a write costs nothing. This way top-level recursion in the REPL/ScriptEngine runs as
     * in Interpreter, while `engine.put("fib", other)` still intercepts the name.
     */
    private volatile java.lang.invoke.SwitchPoint sp;

    public Cell(String name) {
        this.name = name;
    }

    /** Store a value and drop the direct calls bound to the previous one. */
    public void set(Object value) {
        this.v = value;
        this.present = true;
        java.lang.invoke.SwitchPoint s = sp;
        if (s != null) invalidate(s);
    }

    /** Remove the value: the name is no longer declared. */
    public void clear() {
        this.v = null;
        this.present = false;
        java.lang.invoke.SwitchPoint s = sp;
        if (s != null) invalidate(s);
    }

    private synchronized void invalidate(java.lang.invoke.SwitchPoint s) {
        if (sp == s) sp = null;
        java.lang.invoke.SwitchPoint.invalidateAll(new java.lang.invoke.SwitchPoint[]{s});
    }

    /** Switch point the direct calls of the current value live under; created on first binding. */
    public synchronized java.lang.invoke.SwitchPoint switchPoint() {
        java.lang.invoke.SwitchPoint s = sp;
        if (s == null) sp = s = new java.lang.invoke.SwitchPoint();
        return s;
    }

    @Override
    public String toString() {
        return name + "=" + Ops.str(v);
    }
}
