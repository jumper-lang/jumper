package me.padej.jumper.ast;

import me.padej.jumper.interp.Frame;
import me.padej.jumper.runtime.JClass;
import me.padej.jumper.runtime.JFunction;
import me.padej.jumper.runtime.JmpError;
import me.padej.jumper.runtime.JTable;
import me.padej.jumper.runtime.Ops;

import java.util.HashMap;
import java.util.Map;

/** Nodes for classes: declaration, super(...), super.m(...). */
public final class Classes {
    private Classes() {}

    static final String[] EMPTY_NAMES = new String[0];
    static final VarType[] EMPTY_TYPES = new VarType[0];
    static final ClassNode[] EMPTY_CLASSES = new ClassNode[0];

    /** Class description from the source. runtime - the last created JClass (for super inside methods). */
    public static final class ClassNode {
        public final String name;
        public final String[] fieldNames;
        /** Declared field types (parallel to fieldNames). */
        public final VarType[] fieldTypes;
        /** Classes of fields with a class type (`Vec next;`), parallel to fieldNames; null - none. */
        public ClassNode[] fieldClasses;
        /** Names of the class types of fields before resolution (parser, between the prescan pass and parsing the bodies); null afterwards. */
        public String[] pendingFieldClassNames, pendingStaticClassNames;
        /** Static members: fields and methods in one table (a method is a function value). */
        public String[] staticNames = EMPTY_NAMES;
        /** Static method nodes (name -> function), filled after the class body is parsed. */
        public java.util.Map<String, FunctionNode> staticMethodNodes = java.util.Map.of();
        public VarType[] staticTypes = EMPTY_TYPES;
        public ClassNode[] staticClasses = EMPTY_CLASSES;
        /** Refined by the parser after the bodies are parsed (order = ClassDecl.methods). */
        public String[] methodNames;
        /** Method nodes (parallel to methodNames), filled after the class body is parsed. */
        public FunctionNode[] methodNodes;
        /** Constructor (this, ...) and field initializers (this) - after the body is parsed; null if absent. */
        public FunctionNode ctorNode, fieldInitNode;
        public boolean ctorCallsSuper;
        /** Whether the class body has been parsed (methods/constructor are known). */
        public boolean bodyParsed;
        /** Whether there is an `extends` at all. */
        public boolean hasParent;
        /** Parent, if it is declared earlier in the same source (for bare names of inherited members). */
        public ClassNode parentNode;
        public volatile JClass runtime;
        /** Interpreter loader for the instance class (null - the shared one). Set by the parser. */
        public me.padej.jumper.jit.ScriptLoader loader;
        /** InstanceGen state for this node: reserved name, finished class, failure flag. */
        public String instName;
        public Class<?> instClass;
        public boolean instFailed;
        /** JClass layouts keyed by the parent layout (see JClass.layout) - live with the node, not in statics. */
        public final java.util.Map<Object, Object> layouts = new java.util.HashMap<>();

        public ClassNode(String name, String[] fieldNames, VarType[] fieldTypes, String[] methodNames) {
            this.name = name;
            this.fieldNames = fieldNames;
            this.fieldTypes = fieldTypes;
            this.methodNames = methodNames;
        }

        /** The field layout is known statically: the whole parent chain is declared in this same source. */
        public boolean layoutKnown() {
            for (ClassNode k = this; ; k = k.parentNode) {
                if (!k.hasParent) return true;
                if (k.parentNode == null) return false;
            }
        }

        /** Field index in the instance (parent fields first) or -1. Only with layoutKnown(). */
        public int fieldIndex(String field) {
            int base = 0;
            if (parentNode != null) {
                int i = parentNode.fieldIndex(field);
                if (i >= 0) return i;
                base = parentNode.fieldCount();
            }
            for (int i = 0; i < fieldNames.length; i++) if (fieldNames[i].equals(field)) return base + i;
            return -1;
        }

        public int fieldCount() {
            return (parentNode == null ? 0 : parentNode.fieldCount()) + fieldNames.length;
        }

        /**
         * Number of constructor arguments that `new C(...)` will run with: our own, otherwise the nearest
         * parent one (implicit super()), otherwise 0. -1 - unknown: the body is not parsed yet or
         * the parent is declared outside this source.
         */
        public static int ctorArity(ClassNode c) {
            for (ClassNode k = c; ; k = k.parentNode) {
                if (!k.bodyParsed) return -1;
                if (k.ctorNode != null) return k.ctorNode.nparams - 1;   // minus this
                if (!k.hasParent) return 0;
                if (k.parentNode == null) return -1;
            }
        }

        /** Declared type of a field (own or inherited). */
        public VarType fieldType(String field) {
            for (int i = 0; i < fieldNames.length; i++) if (fieldNames[i].equals(field)) return fieldTypes[i];
            return parentNode == null ? VarType.DYN : parentNode.fieldType(field);
        }

        public ClassNode fieldClass(String field) {
            for (int i = 0; i < fieldNames.length; i++) if (fieldNames[i].equals(field)) return fieldClasses == null ? null : fieldClasses[i];
            return parentNode == null ? null : parentNode.fieldClass(field);
        }

        /** Method index in JClass.methodArr (same scheme: parent ones, then our new ones) or -1. Only with layoutKnown(). */
        public int methodIndex(String m) {
            int base = 0;
            if (parentNode != null) {
                int i = parentNode.methodIndex(m);
                if (i >= 0) return i;
                base = parentNode.methodCount();
            }
            int fresh = 0;
            for (String n : methodNames) {
                boolean inherited = parentNode != null && parentNode.methodIndex(n) >= 0;
                if (n.equals(m)) return inherited ? -1 : base + fresh;
                if (!inherited) fresh++;
            }
            return -1;
        }

        /** The class or its ancestors have field initializers. */
        public boolean hasFieldInits() {
            return fieldInitNode != null || (parentNode != null && parentNode.hasFieldInits());
        }

        public int methodCount() {
            int base = parentNode == null ? 0 : parentNode.methodCount();
            int fresh = 0;
            for (String n : methodNames) if (parentNode == null || parentNode.methodIndex(n) < 0) fresh++;
            return base + fresh;
        }

        /** Node of method m (own or inherited) or null if the body is not parsed yet / there is no such method. */
        public FunctionNode methodNode(String m) {
            if (methodNodes != null) for (int i = 0; i < methodNames.length; i++) if (methodNames[i].equals(m)) return methodNodes[i];
            return parentNode == null ? null : parentNode.methodNode(m);
        }

        /** Class in which static member name is declared (own or a parent), or null. */
        public ClassNode staticOwner(String name) {
            for (String n : staticNames) if (n.equals(name)) return this;
            return parentNode == null ? null : parentNode.staticOwner(name);
        }

        public int staticIndex(String name) {
            for (int i = 0; i < staticNames.length; i++) if (staticNames[i].equals(name)) return i;
            return -1;
        }

        public VarType staticType(String name) {
            int i = staticIndex(name);
            return i < 0 ? VarType.DYN : staticTypes[i];
        }

        public ClassNode staticClass(String name) {
            int i = staticIndex(name);
            return i < 0 ? null : staticClasses[i];
        }

        /** Node of static method name (own or a parent one) or null. */
        public FunctionNode staticMethodNode(String name) {
            FunctionNode fn = staticMethodNodes.get(name);
            if (fn != null) return fn;
            return parentNode == null ? null : parentNode.staticMethodNode(name);
        }

        /** This class is node or its descendant (by the parent chain known at parse time). */
        public boolean isSubclassOf(ClassNode node) {
            for (ClassNode k = this; k != null; k = k.parentNode) if (k == node) return true;
            return false;
        }
    }

    /**
     * class Name [extends Parent] { ... } - on execution creates a JClass (methods close over
     * the current frame, like ordinary functions) and puts it into the slot.
     */
    public static final class ClassDecl extends Stmt {
        public final int slot;
        public final ClassNode node;
        public final Expr parent;        // may be null
        public final FunctionNode fieldInit; // (this) or null
        public final FunctionNode ctor;      // (this, ...) or null
        public final boolean ctorCallsSuper;
        public final FunctionNode[] methods; // parallel to node.methodNames
        /** Static methods: name -> node (the values are put into JClass.statics when the class is created). */
        public String[] staticMethodNames = EMPTY_NAMES;
        public FunctionNode[] staticMethods = new FunctionNode[0];
        /** Static field initializers (run once when the class is created). */
        public FunctionNode staticInit;

        /** No method closes over outer variables -> the JClass can be created once and reused. */
        private final boolean closed;
        private JClass cached;
        private Object cachedParent;

        public ClassDecl(int slot, ClassNode node, Expr parent, FunctionNode fieldInit, FunctionNode ctor,
                         boolean ctorCallsSuper, FunctionNode[] methods, int line) {
            super(line);
            this.slot = slot;
            this.node = node;
            this.parent = parent;
            this.fieldInit = fieldInit;
            this.ctor = ctor;
            this.ctorCallsSuper = ctorCallsSuper;
            this.methods = methods;
            boolean c = fieldInit == null || !me.padej.jumper.jit.Captures.usesOuter(fieldInit);
            if (ctor != null && me.padej.jumper.jit.Captures.usesOuter(ctor)) c = false;
            for (FunctionNode m : methods) if (me.padej.jumper.jit.Captures.usesOuter(m)) c = false;
            this.closed = c;
        }

        public JClass create(Frame f) {
            return create(f, parent == null ? null : parent.eval(f));
        }

        /** The parent is already evaluated (Tier 1 evaluates it itself - the variable may live in a JVM local). */
        public JClass create(Frame f, Object pv) {
            if (closed && cached != null && cachedParent == pv) {
                node.runtime = cached;
                return cached;
            }
            JClass c = build(f, pv);
            if (closed) { cached = c; cachedParent = pv; }
            return c;
        }

        private JClass build(Frame f, Object pv) {
            JClass p = null;
            if (parent != null) {
                if (!(pv instanceof JClass pc)) {
                    if (pv instanceof me.padej.jumper.runtime.JavaClass jc)
                        throw new JmpError("Cannot extend Java class " + jc.cls().getSimpleName()
                                + ": a Jumper class can only extend a Jumper class (Java interfaces are implemented by passing functions)");
                    throw new JmpError("Class " + node.name + " extends " + Ops.typeName(pv) + ", expected a class");
                }
                p = pc;
            }
            Map<String, JFunction> ms = new HashMap<>();
            for (int i = 0; i < methods.length; i++) ms.put(node.methodNames[i], new FunctionNode.ScriptFunction(methods[i], f));
            JClass c = new JClass(node, node.name, p, node.fieldNames, node.fieldTypes, node.fieldClasses,
                    fieldInit == null ? null : new FunctionNode.ScriptFunction(fieldInit, f),
                    ctor == null ? null : new FunctionNode.ScriptFunction(ctor, f),
                    ctorCallsSuper, ms, node.methodNames);
            JClass prev = node.runtime;
            node.runtime = c;
            try {
                for (int i = 0; i < staticMethods.length; i++)
                    c.statics.setAt(node.staticIndex(staticMethodNames[i]), new FunctionNode.ScriptFunction(staticMethods[i], f));
                if (staticInit != null) new FunctionNode.ScriptFunction(staticInit, f).call(new Object[0]);
            } catch (RuntimeException | Error e) {
                node.runtime = prev;
                throw e;
            }
            return c;
        }

        /** REPL: the class goes into the session variables, not into a slot (slot == -1). */
        public java.util.Map<String, Object> globalVars;

        @Override
        public int exec(Frame f) {
            JClass c = create(f);
            if (globalVars != null) globalVars.put(node.name, c);
            else f.slots[slot] = c;
            return NORMAL;
        }
    }

    /** super(args) - the parent constructor for this. */
    public static final class SuperCall extends Expr {
        public final ClassNode cls;
        public final Expr self;
        public final Expr[] args;

        public SuperCall(ClassNode cls, Expr self, Expr[] args, int line) {
            super(line);
            this.cls = cls;
            this.self = self;
            this.args = args;
        }

        public Object invoke(Object self, Object[] vals) {
            JClass c = cls.runtime;
            if (c == null) throw new JmpError("super() outside of class " + cls.name);
            c.superCtor((JTable) self, vals);
            return null;
        }

        @Override
        public Object eval(Frame f) {
            Object[] vals = new Object[args.length];
            for (int i = 0; i < args.length; i++) vals[i] = args[i].eval(f);
            return invoke(self.eval(f), vals);
        }
    }

    /** super.name(args) - the parent method for this. */
    public static final class SuperMethodCall extends Expr {
        public final ClassNode cls;
        public final String name;
        public final Expr self;
        public final Expr[] args;

        public SuperMethodCall(ClassNode cls, String name, Expr self, Expr[] args, int line) {
            super(line);
            this.cls = cls;
            this.name = name;
            this.self = self;
            this.args = args;
        }

        public Object invoke(Object self, Object[] vals) {
            JClass c = cls.runtime;
            if (c == null || c.parent == null) throw new JmpError("super." + name + ": class " + cls.name + " has no superclass");
            JFunction m = c.parent.findMethod(name);
            if (m == null) throw new JmpError("super." + name + ": no such method in " + c.parent.name);
            return m.call(JClass.withThis(self, vals));
        }

        @Override
        public Object eval(Frame f) {
            Object[] vals = new Object[args.length];
            for (int i = 0; i < args.length; i++) vals[i] = args[i].eval(f);
            return invoke(self.eval(f), vals);
        }
    }
}
