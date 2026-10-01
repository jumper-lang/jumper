package me.padej.jumper.runtime;

import me.padej.jumper.ast.VarType;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Script class. An instance is a JTable with a fixed shape (all fields of the class and its parents
 * in declaration order) and a cls reference, so field access goes through the same inline caches as tables.
 * Methods are ordinary functions with an implicit first parameter this.
 */
public final class JClass {
    public final String name;
    public final JClass parent;
    /** Declaration node (Classes.ClassNode) - for static type checks of `Vec v`. */
    public final Object node;
    public final Shape shape;
    /** Static fields and methods of the class (a sealed table; the parent has its own). */
    public final JTable statics;
    private final String[] fieldNames;
    private final VarType[] fieldTypes;
    private final Object[] fieldClasses;
    /** All methods including inherited ones (overridden ones replaced). */
    private final Map<String, JFunction> methods = new HashMap<>();
    /** The same methods in a stable order: the index is identical for all classes with one layout. */
    public final JFunction[] methodArr;
    private final Map<String, Integer> methodIndex = new HashMap<>();
    /**
     * Layout key: declaration node + parent layout (interned). Two JClass with one layout have
     * identical method indices and identical FunctionNodes behind them - inline caches rely on this.
     */
    public final Object layout;
    private static final Map<Object, Object> LAYOUTS = new HashMap<>();
    private final JFunction fieldInit; // (this): field initializers of this class; may be null
    public final JFunction ctor;       // (this, args...): may be null
    private final boolean ctorCallsSuper;

    public JClass(Object node, String name, JClass parent, String[] ownFields, VarType[] ownTypes, Object[] ownClasses, JFunction fieldInit,
                  JFunction ctor, boolean ctorCallsSuper, Map<String, JFunction> ownMethods, String[] ownMethodOrder) {
        this.name = name;
        this.node = node;
        Object pk = parent == null ? "" : parent.layout;
        if (node instanceof me.padej.jumper.ast.Classes.ClassNode cn) {
            // layouts live in the node: a static map would keep the nodes of every class ever parsed
            synchronized (cn.layouts) { this.layout = cn.layouts.computeIfAbsent(pk, k -> new Object()); }
        } else {
            Object key = java.util.List.of(node, pk);
            synchronized (LAYOUTS) { this.layout = LAYOUTS.computeIfAbsent(key, k -> new Object()); }
        }
        this.parent = parent;
        this.fieldInit = fieldInit;
        this.ctor = ctor;
        this.ctorCallsSuper = ctorCallsSuper;
        // fields: parent's first (indices coincide across all subclasses), redeclaration is an error
        LinkedHashMap<String, VarType> all = new LinkedHashMap<>();
        java.util.ArrayList<Object> cls = new java.util.ArrayList<>();
        if (parent != null) for (int i = 0; i < parent.fieldNames.length; i++) { all.put(parent.fieldNames[i], parent.fieldTypes[i]); cls.add(parent.fieldClasses[i]); }
        for (int i = 0; i < ownFields.length; i++) {
            if (all.containsKey(ownFields[i]))
                throw new JmpError("Field '" + ownFields[i] + "' is already declared" + (parent != null && parent.hasField(ownFields[i]) ? " in " + parent.name : ""));
            all.put(ownFields[i], ownTypes[i]);
            cls.add(ownClasses == null ? null : ownClasses[i]);
        }
        fieldNames = all.keySet().toArray(new String[0]);
        fieldTypes = all.values().toArray(new VarType[0]);
        fieldClasses = cls.toArray();
        shape = Shape.forClass(fieldNames, fieldTypes, fieldClasses);
        shape.owner = this;
        shape.ownerLayout = layout;
        shape.ownerNode = node;
        statics = node instanceof me.padej.jumper.ast.Classes.ClassNode cn && cn.staticNames.length > 0
                ? JTable.statics(Shape.forClass(cn.staticNames, cn.staticTypes, cn.staticClasses), this)
                : null;
        if (parent != null) methods.putAll(parent.methods);
        methods.putAll(ownMethods);
        java.util.ArrayList<JFunction> arr = new java.util.ArrayList<>();
        if (parent != null) {
            for (JFunction m : parent.methodArr) arr.add(m);
            methodIndex.putAll(parent.methodIndex);
        }
        for (String mn : ownMethodOrder) {
            Integer i = methodIndex.get(mn);
            if (i != null) arr.set(i, ownMethods.get(mn));
            else { methodIndex.put(mn, arr.size()); arr.add(ownMethods.get(mn)); }
        }
        methodArr = arr.toArray(new JFunction[0]);
    }

    /** Method index in methodArr or -1. */
    public int methodIndex(String n) {
        Integer i = methodIndex.get(n);
        return i == null ? -1 : i;
    }

    public JFunction findMethod(String n) {
        return methods.get(n);
    }

    public boolean hasField(String n) {
        return shape.indexOf(n) >= 0;
    }

    /** The class (this one or a parent) that declares static member n, or null. */
    public JClass staticOwner(String n) {
        for (JClass k = this; k != null; k = k.parent)
            if (k.statics != null && k.statics.shape.indexOf(n) >= 0) return k;
        return null;
    }

    /** Value of a static member (field or method); an error if there is none. */
    public Object staticGet(String n) {
        JClass k = staticOwner(n);
        if (k == null) throw new JmpError("No static member '" + n + "' in " + name);
        return k.statics.getField(n);
    }

    public void staticSet(String n, Object v) {
        JClass k = staticOwner(n);
        if (k == null) throw new JmpError("No static member '" + n + "' in " + name);
        k.statics.put(n, v);
    }

    /** The class is declared by node or inherits from such a class. */
    public boolean isa(Object node) {
        for (JClass k = this; k != null; k = k.parent) if (k.node == node) return true;
        return false;
    }

    public boolean isSubclassOf(JClass c) {
        for (JClass k = this; k != null; k = k.parent) if (k == c) return true;
        return false;
    }

    public JTable instantiate(Object[] args) {
        JTable t = newInstance();
        runFieldInits(t);
        runCtor(t, args);
        return t;
    }

    /**
     * Empty instance: the generated class with real fields if there is one, otherwise a plain
     * array-backed JTable. Both variants coexist - a class whose parent is declared outside this
     * source does not know its layout statically and stays on arrays.
     */
    public JTable newInstance() {
        if (!instResolved) resolveInst();
        java.lang.invoke.MethodHandle mh = instCtor;
        if (mh == null) return JTable.instance(this);
        try {
            return (JTable) mh.invoke(this);
        } catch (Throwable t) {
            throw JmpError.rethrow(t);
        }
    }

    /**
     * Lazy resolution: the instance class is generated on the first object creation, not at
     * class declaration - a script may declare a class and never instantiate it.
     */
    private volatile java.lang.invoke.MethodHandle instCtor;
    private volatile boolean instResolved;

    private synchronized void resolveInst() {
        if (instResolved) return;
        try {
            instCtor = me.padej.jumper.jit.InstanceGen.ctorFor(node);
        } catch (Throwable t) {
            instCtor = null;   // stay on arrays: always correct, just slower
        }
        instResolved = true;
    }



    public void runFieldInits(JTable t) {
        if (parent != null) parent.runFieldInits(t);
        if (fieldInit != null) fieldInit.call(new Object[]{t});
    }

    /** Constructor: own or the parent's (as in Java, super() is called implicitly unless written explicitly). */
    public void runCtor(JTable t, Object[] args) {
        if (ctor == null) {
            if (parent != null) parent.runCtor(t, args);
            return;
        }
        if (parent != null && !ctorCallsSuper) parent.runCtor(t, new Object[0]);
        ctor.call(withThis(t, args));
    }

    /** Implicit super() before the own constructor (Tier 1, direct instance creation). */
    public void runParentCtor(JTable t) {
        if (parent != null) parent.runCtor(t, new Object[0]);
    }

    /** super(args) from a subclass constructor. */
    public void superCtor(JTable t, Object[] args) {
        if (parent == null) throw new JmpError("Class " + name + " has no superclass");
        parent.runCtor(t, args);
    }

    public static Object[] withThis(Object self, Object[] args) {
        Object[] a = new Object[args.length + 1];
        a[0] = self;
        System.arraycopy(args, 0, a, 1, args.length);
        return a;
    }

    /** Method as a value: bound to the instance. */
    public static JFunction bind(JFunction m, Object self) {
        return new JFunction() {
            @Override
            public Object call(Object[] args) {
                return m.call(withThis(self, args));
            }

            @Override
            public String name() {
                return m.name();
            }

            @Override
            public String toString() {
                return "bound " + m.name();
            }
        };
    }

    @Override
    public String toString() {
        return "class " + name;
    }
}
