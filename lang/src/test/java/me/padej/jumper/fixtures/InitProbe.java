package me.padej.jumper.fixtures;

/** A class whose static initializer leaves a trace: parsing a script must not run it (LspFoundationTest). */
public final class InitProbe {
    public static final java.util.concurrent.atomic.AtomicInteger INITS = InitTrace.COUNT;
    public static int value = 42;

    static { InitTrace.COUNT.incrementAndGet(); }

    public static int twice(int x) { return x * 2; }

    /** Holds the counter outside InitProbe, so reading it does not initialize the probe. */
    public static final class InitTrace {
        public static final java.util.concurrent.atomic.AtomicInteger COUNT = new java.util.concurrent.atomic.AtomicInteger();
    }
}
