package me.padej.jumper.jit;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Assembly of a class file (version 52 = Java 8) from methods with ready Code attributes. */
final class ClassBuilder {
    /** public final super - an ordinary generated function class. */
    static final int ACC_DEFAULT = 0x0001 | 0x0010 | 0x0020;
    /** public super, without final - an instance class that script subclasses inherit from. */
    static final int ACC_OPEN = 0x0001 | 0x0020;

    final ConstPool cp = new ConstPool();
    final String name;
    final String superName;
    private int access = ACC_DEFAULT;
    private final List<byte[]> methods = new ArrayList<>();
    private final List<byte[]> fields = new ArrayList<>();
    private final List<Integer> interfaces = new ArrayList<>();
    /** Bootstrap methods: [methodHandleIndex, arg1, arg2, ...] - the arguments already as pool indices. */
    private final List<int[]> bootstraps = new ArrayList<>();

    ClassBuilder(String name, String superName) {
        this.name = name;
        this.superName = superName;
        cp.cls(name);
        cp.cls(superName);
    }

    /** Class access flags (ACC_DEFAULT by default). */
    void access(int a) {
        this.access = a;
    }

    /** Implemented interface (internal name). */
    void implement(String iface) {
        interfaces.add(cp.cls(iface));
    }

    /** Field without attributes: only one is needed - the static array of function constants. */
    void addField(int access, String fname, String desc) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(b);
        try {
            d.writeShort(access);
            d.writeShort(cp.utf8(fname));
            d.writeShort(cp.utf8(desc));
            d.writeShort(0); // attributes
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        fields.add(b.toByteArray());
    }

    void addMethod(int access, String mname, String desc, byte[] codeAttr) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(b);
        try {
            d.writeShort(access);
            d.writeShort(cp.utf8(mname));
            d.writeShort(cp.utf8(desc));
            d.writeShort(1);
            d.writeShort(cp.utf8("Code"));
            d.writeInt(codeAttr.length);
            d.write(codeAttr);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        methods.add(b.toByteArray());
    }

    /** Registers a bootstrap method; returns its index for CONSTANT_InvokeDynamic. */
    int bootstrap(String owner, String name, String desc, int... staticArgs) {
        int mh = cp.methodHandleStatic(owner, name, desc);
        int[] entry = new int[staticArgs.length + 1];
        entry[0] = mh;
        System.arraycopy(staticArgs, 0, entry, 1, staticArgs.length);
        bootstraps.add(entry);
        return bootstraps.size() - 1;
    }

    byte[] toBytes() {
        int thisIdx = cp.cls(name), superIdx = cp.cls(superName);
        int bsmAttrName = bootstraps.isEmpty() ? 0 : cp.utf8("BootstrapMethods");
        ByteArrayOutputStream b = new ByteArrayOutputStream(4096);
        DataOutputStream d = new DataOutputStream(b);
        try {
            d.writeInt(0xCAFEBABE);
            d.writeShort(0);
            d.writeShort(52);
            byte[] pool = cp.bytes(); // after all accesses to the pool
            d.writeShort(cp.size());
            d.write(pool);
            d.writeShort(access);
            d.writeShort(thisIdx);
            d.writeShort(superIdx);
            d.writeShort(interfaces.size());
            for (int i : interfaces) d.writeShort(i);
            d.writeShort(fields.size());
            for (byte[] f : fields) d.write(f);
            d.writeShort(methods.size());
            for (byte[] m : methods) d.write(m);
            if (bootstraps.isEmpty()) d.writeShort(0);
            else {
                d.writeShort(1);
                d.writeShort(bsmAttrName);
                int len = 2;
                for (int[] e : bootstraps) len += 4 + 2 * (e.length - 1);
                d.writeInt(len);
                d.writeShort(bootstraps.size());
                for (int[] e : bootstraps) {
                    d.writeShort(e[0]);
                    d.writeShort(e.length - 1);
                    for (int i = 1; i < e.length; i++) d.writeShort(e[i]);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return b.toByteArray();
    }
}
