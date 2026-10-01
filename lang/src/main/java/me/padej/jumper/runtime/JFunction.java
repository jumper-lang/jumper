package me.padej.jumper.runtime;

/** Any callable value: a script function, a builtin, a Java method. */
public interface JFunction {
    Object call(Object[] args);

    default String name() {
        return "function";
    }

    /** Builtin function defined by a lambda. */
    static JFunction of(String name, JFunction f) {
        return new JFunction() {
            @Override
            public Object call(Object[] args) {
                return f.call(args);
            }

            @Override
            public String name() {
                return name;
            }

            @Override
            public String toString() {
                return "builtin " + name;
            }
        };
    }
}
