package me.padej.jumper.jit;

import me.padej.jumper.ast.Classes;
import me.padej.jumper.ast.VarType;
import me.padej.jumper.runtime.JTable;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Instance class for a script class: a real JVM class with typed fields.
 *
 * <p>Why. An instance of a script class is a {@link JTable}, and the field values live in its
 * {@code values}/{@code prims} arrays. That means two allocations instead of one (the object itself plus the array) and two
 * dependent memory accesses per field read instead of a single {@code getfield}. On
 * {@code game_tick} - 30 million iterations over a dozen fields - that is the entire gap to Java.
 *
 * <p>Jumper classes are sealed: the set of fields is known at parse time. So for every class node
 * we can generate {@code Inst_P extends JTable} with real fields {@code f0, f1, ...},
 * where the field index matches its index in the shape.
 *
 * <p><b>Inheritance mirrors the script inheritance</b> - {@code Inst_Leaf extends Inst_Mid
 * extends Inst_Base extends JTable}. This matters not for elegance: a field declared in {@code Base}
 * becomes a real field of {@code Inst_Base}, and a method of {@code Base} called on a
 * {@code Leaf} instance reads it with an ordinary {@code getfield} - exactly as Java does. If
 * every class were flat and final, the same method would have to be guarded by an exact class
 * check, and on a hierarchy it would become polymorphic.
 *
 * <p>The rest of the runtime knows nothing about this: {@code values}/{@code prims} of such instances
 * are empty, and access goes through the overridden {@link JTable#bitsAt}/{@link JTable#setBits}/
 * {@link JTable#refAt}/{@link JTable#setRef}. All inline caches compare the shape, and the shape of
 * a generated instance is unchanged - so not a single guard had to change.
 *
 * <p>Disabled with {@code -Djmp.genclass=0}: then instances are ordinary JTables again.
 */
public final class InstanceGen {
    private InstanceGen() {}

    public static final boolean ENABLED = me.padej.jumper.runtime.Opts.on("genclass");

    private static final String JTABLE = "me/padej/jumper/runtime/JTable";
    private static final String JCLASS = "me/padej/jumper/runtime/JClass";
    private static final String OBJ_D = "Ljava/lang/Object;";

    // The state (reserved name, class, failure flag) lives in the ClassNode itself, not in
    // static maps: a map would keep the node and the class until the end of the process, and the
    // interpreter's loader (ScriptLoader) would never be unloaded.
    private static int seq;

    /** Whether the node qualifies: the layout is known statically, i.e. the whole parent chain is in this source. */
    public static boolean suitable(Classes.ClassNode cn) {
        return ENABLED && cn != null && cn.layoutKnown() && !cn.instFailed;
    }

    /** Internal name of the instance class (reserved in advance), or null. */
    public static synchronized String nameFor(Classes.ClassNode cn) {
        if (!suitable(cn)) return null;
        String n = cn.instName;
        if (n != null) return n;
        n = Jit.PKG + "Inst" + (seq++) + "_" + sanitize(cn.name);
        cn.instName = n;
        return n;
    }

    /** The instance class, generated on first access. null - the representation stays as before. */
    public static synchronized Class<?> classFor(Classes.ClassNode cn) {
        if (!suitable(cn)) return null;
        Class<?> c = cn.instClass;
        if (c != null) return c;
        try {
            c = define(cn);
        } catch (Throwable t) {
            if (Jit.DEBUG) System.err.println("[inst] " + cn.name + " not generated: " + t);
            cn.instFailed = true;
            cn.instName = null;
            return null;
        }
        cn.instClass = c;
        return c;
    }

    /**
     * Constructor {@code (JClass)JTable} of the generated class, or null - then the instance
     * stays an ordinary JTable with arrays. Called from JClass so that the runtime does not need
     * its own Lookup into the jit package.
     */
    public static java.lang.invoke.MethodHandle ctorFor(Object node) {
        if (!(node instanceof Classes.ClassNode cn)) return null;
        Class<?> c = classFor(cn);
        if (c == null) return null;
        try {
            return Jit.LOOKUP.findConstructor(c,
                    java.lang.invoke.MethodType.methodType(void.class, me.padej.jumper.runtime.JClass.class));
        } catch (Throwable t) {
            if (Jit.DEBUG) System.err.println("[inst] no constructor in " + c.getName() + ": " + t);
            return null;
        }
    }

    private static Class<?> define(Classes.ClassNode cn) throws Throwable {
        Classes.ClassNode parent = cn.parentNode;
        String superName = JTABLE;
        if (parent != null) {
            Class<?> pc = classFor(parent);
            if (pc == null) throw new IllegalStateException("parent " + parent.name + " not generated");
            superName = pc.getName().replace('.', '/');
        }
        String name = nameFor(cn);
        if (name == null) throw new IllegalStateException("name not reserved");

        int base = parent == null ? 0 : parent.fieldCount();
        int total = cn.fieldCount();
        List<int[]> own = new ArrayList<>();       // [idx] - own fields
        ClassBuilder cb = new ClassBuilder(name, superName);
        cb.access(ClassBuilder.ACC_OPEN);          // script subclasses inherit from it
        for (int i = base; i < total; i++) {
            cb.addField(0x0001, "f" + i, desc(typeAt(cn, i)));
            own.add(new int[]{i});
        }

        genInit(cb, name, superName, parent == null);
        genBitsAt(cb, name, superName, cn, base, total);
        genSetBits(cb, name, superName, cn, base, total);
        genRefAt(cb, name, superName, cn, base, total);
        genSetRef(cb, name, superName, cn, base, total);

        byte[] bytes = cb.toBytes();
        Jit.dump(name, bytes);
        Class<?> c = Jit.loaderOf(cn.loader).define(name, bytes);
        if (Jit.DEBUG) System.err.println("[inst] " + cn.name + " -> " + c.getName()
                + " (fields " + base + ".." + (total - 1) + ", " + bytes.length + " bytes)");
        return c;
    }

    // ---------- methods ----------

    /** {@code <init>(JClass)}: the root calls the JTable constructor for an instance with fields in the object. */
    private static void genInit(ClassBuilder cb, String name, String superName, boolean root) {
        Code k = new Code(cb.cp, List.of(name, JCLASS));
        k.aload(0);
        k.aload(1);
        if (root) {
            k.iconst(1);
            k.invokespecial(JTABLE, "<init>", "(L" + JCLASS + ";Z)V");
        } else {
            k.invokespecial(superName, "<init>", "(L" + JCLASS + ";)V");
        }
        k.vreturn();
        cb.addMethod(0x0001, "<init>", "(L" + JCLASS + ";)V", k.finish());
    }

    private static void genBitsAt(ClassBuilder cb, String name, String superName,
                                  Classes.ClassNode cn, int base, int total) {
        Code k = new Code(cb.cp, List.of(name, "I"));
        for (int i = base; i < total; i++) {
            VarType t = typeAt(cn, i);
            if (!t.isPrimitive()) continue;
            Code.Label next = k.label();
            k.iload(1);
            k.iconst(i);
            k.if_icmpne(next);
            k.aload(0);
            k.getfield(name, "f" + i, desc(t));
            switch (t) {
                case INT, BOOLEAN -> k.i2l();
                case DOUBLE -> k.invokestatic("java/lang/Double", "doubleToRawLongBits", "(D)J");
                default -> { }   // LONG - already a long
            }
            k.lreturn();
            k.mark(next);
        }
        k.aload(0);
        k.iload(1);
        k.invokespecial(superName, "bitsAt", "(I)J");
        k.lreturn();
        cb.addMethod(0x0001, "bitsAt", "(I)J", k.finish());
    }

    private static void genSetBits(ClassBuilder cb, String name, String superName,
                                   Classes.ClassNode cn, int base, int total) {
        Code k = new Code(cb.cp, List.of(name, "I", "J"));
        for (int i = base; i < total; i++) {
            VarType t = typeAt(cn, i);
            if (!t.isPrimitive()) continue;
            Code.Label next = k.label();
            k.iload(1);
            k.iconst(i);
            k.if_icmpne(next);
            k.aload(0);
            k.lload(2);
            switch (t) {
                case INT, BOOLEAN -> k.l2i();
                case DOUBLE -> k.invokestatic("java/lang/Double", "longBitsToDouble", "(J)D");
                default -> { }
            }
            k.putfield(name, "f" + i, desc(t));
            k.vreturn();
            k.mark(next);
        }
        k.aload(0);
        k.iload(1);
        k.lload(2);
        k.invokespecial(superName, "setBits", "(IJ)V");
        k.vreturn();
        cb.addMethod(0x0001, "setBits", "(IJ)V", k.finish());
    }

    private static void genRefAt(ClassBuilder cb, String name, String superName,
                                 Classes.ClassNode cn, int base, int total) {
        Code k = new Code(cb.cp, List.of(name, "I"));
        for (int i = base; i < total; i++) {
            if (typeAt(cn, i).isPrimitive()) continue;
            Code.Label next = k.label();
            k.iload(1);
            k.iconst(i);
            k.if_icmpne(next);
            k.aload(0);
            k.getfield(name, "f" + i, OBJ_D);
            k.areturn();
            k.mark(next);
        }
        k.aload(0);
        k.iload(1);
        k.invokespecial(superName, "refAt", "(I)" + OBJ_D);
        k.areturn();
        cb.addMethod(0x0001, "refAt", "(I)" + OBJ_D, k.finish());
    }

    private static void genSetRef(ClassBuilder cb, String name, String superName,
                                  Classes.ClassNode cn, int base, int total) {
        Code k = new Code(cb.cp, List.of(name, "I", "java/lang/Object"));
        for (int i = base; i < total; i++) {
            if (typeAt(cn, i).isPrimitive()) continue;
            Code.Label next = k.label();
            k.iload(1);
            k.iconst(i);
            k.if_icmpne(next);
            k.aload(0);
            k.aload(2);
            k.putfield(name, "f" + i, OBJ_D);
            k.vreturn();
            k.mark(next);
        }
        k.aload(0);
        k.iload(1);
        k.aload(2);
        k.invokespecial(superName, "setRef", "(I" + OBJ_D + ")V");
        k.vreturn();
        cb.addMethod(0x0001, "setRef", "(I" + OBJ_D + ")V", k.finish());
    }

    // ---------- helpers ----------

    /** Field type by absolute index: search along the chain the way ClassNode.fieldIndex builds it. */
    static VarType typeAt(Classes.ClassNode cn, int idx) {
        for (Classes.ClassNode k = cn; k != null; k = k.parentNode) {
            int base = k.parentNode == null ? 0 : k.parentNode.fieldCount();
            if (idx >= base) return k.fieldTypes[idx - base];
        }
        return VarType.DYN;
    }

    /** Field name by absolute index - for debugging and cross-checking against the shape. */
    static String keyAt(Classes.ClassNode cn, int idx) {
        for (Classes.ClassNode k = cn; k != null; k = k.parentNode) {
            int base = k.parentNode == null ? 0 : k.parentNode.fieldCount();
            if (idx >= base) return k.fieldNames[idx - base];
        }
        return null;
    }

    static String desc(VarType t) {
        return switch (t) {
            case INT -> "I";
            case LONG -> "J";
            case DOUBLE -> "D";
            case BOOLEAN -> "Z";
            default -> OBJ_D;
        };
    }

    private static String sanitize(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(Character.isJavaIdentifierPart(c) ? c : '_');
        }
        return b.toString();
    }
}
