package me.padej.jumper.runtime;

/** Reference to a Java class as a script value: `Math`, `ArrayList` after import. */
public record JavaClass(Class<?> cls) {
    @Override
    public String toString() {
        return "class " + cls.getName();
    }
}
