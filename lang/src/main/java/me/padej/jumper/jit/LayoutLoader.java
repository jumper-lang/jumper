package me.padej.jumper.jit;

/**
 * Loader of the layout classes ({@link LayoutGen}): one for the process, the parent of every
 * {@link ScriptLoader}. Shapes are shared by all interpreters, so the class of a shape's tables
 * must be visible to the compiled code of all of them - a class defined in one interpreter's loader
 * could not be named from another's. Layout classes hold no script code and are as permanent as
 * the shapes they belong to.
 */
final class LayoutLoader extends ClassLoader {
    static { registerAsParallelCapable(); }

    static final LayoutLoader INSTANCE = new LayoutLoader();

    private LayoutLoader() {
        super(Jit.class.getClassLoader());
    }

    Class<?> define(String internalName, byte[] bytes) {
        return defineClass(internalName.replace('/', '.'), bytes, 0, bytes.length);
    }
}
