package me.padej.jumper.jit;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Constant pool of a class file (JVMS 4.4). Deduplicated by key.
 *
 * <p>The keys are not concatenated strings but the strings themselves and records made of them: a {@code String} caches
 * its hash in the object, and the owner/method/descriptor names in the compiler are literals, i.e. the very
 * same objects. Previously every {@code invokestatic} built four new strings ({@code "M" + owner
 * + "." + name + ":" + desc} plus one each for utf8/cls/nameAndType) and hashed them character by character -
 * that was a third of the class generation time (2026-09-23, Tier 1 profile: utf8 15.7 %, put 11.7 %).
 */
final class ConstPool {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(2048);
    private final DataOutputStream out = new DataOutputStream(bytes);
    private int count = 1; // index 0 is reserved

    private final Map<String, Integer> utf8 = new HashMap<>(128);
    private final Map<String, Integer> classes = new HashMap<>(128);
    private final Map<String, Integer> strings = new HashMap<>(128);
    private final Map<String, Integer> methodTypes = new HashMap<>();
    private final Map<Integer, Integer> ints = new HashMap<>();
    private final Map<Long, Integer> longs = new HashMap<>();
    private final Map<Long, Integer> doubles = new HashMap<>();
    private final Map<NT, Integer> nameAndTypes = new HashMap<>(128);
    private final Map<Ref, Integer> refs = new HashMap<>(128);
    private final Map<Ref, Integer> handles = new HashMap<>();
    private final Map<Indy, Integer> indys = new HashMap<>();

    private record NT(String name, String desc) {}
    private record Ref(int tag, String owner, String name, String desc) {}
    private record Indy(int bsm, String name, String desc) {}

    int size() {
        return count;
    }

    byte[] bytes() {
        return bytes.toByteArray();
    }

    int utf8(String s) {
        Integer i = utf8.get(s);
        if (i != null) return i;
        try {
            out.writeByte(1);
            out.writeUTF(s);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int idx = count++;
        utf8.put(s, idx);
        return idx;
    }

    private int one(Map<String, Integer> map, String key, int tag, int ref) {
        Integer i = map.get(key);
        if (i != null) return i;
        try {
            out.writeByte(tag);
            out.writeShort(ref);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int idx = count++;
        map.put(key, idx);
        return idx;
    }

    int cls(String internalName) {
        Integer i = classes.get(internalName);
        if (i != null) return i;
        return one(classes, internalName, 7, utf8(internalName));
    }

    int string(String s) {
        Integer i = strings.get(s);
        if (i != null) return i;
        return one(strings, s, 8, utf8(s));
    }

    int methodType(String desc) {
        Integer i = methodTypes.get(desc);
        if (i != null) return i;
        return one(methodTypes, desc, 16, utf8(desc));
    }

    int integer(int v) {
        Integer i = ints.get(v);
        if (i != null) return i;
        try {
            out.writeByte(3);
            out.writeInt(v);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int idx = count++;
        ints.put(v, idx);
        return idx;
    }

    int longConst(long v) {
        Integer i = longs.get(v);
        if (i != null) return i;
        try {
            out.writeByte(5);
            out.writeLong(v);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int idx = count;
        count += 2;
        longs.put(v, idx);
        return idx;
    }

    int doubleConst(double v) {
        long bits = Double.doubleToRawLongBits(v);
        Integer i = doubles.get(bits);
        if (i != null) return i;
        try {
            out.writeByte(6);
            out.writeDouble(v);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int idx = count;
        count += 2;
        doubles.put(bits, idx);
        return idx;
    }

    int nameAndType(String name, String desc) {
        NT key = new NT(name, desc);
        Integer i = nameAndTypes.get(key);
        if (i != null) return i;
        int n = utf8(name), d = utf8(desc);
        try {
            out.writeByte(12);
            out.writeShort(n);
            out.writeShort(d);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int idx = count++;
        nameAndTypes.put(key, idx);
        return idx;
    }

    private int ref(int tag, String owner, String name, String desc) {
        Ref key = new Ref(tag, owner, name, desc);
        Integer i = refs.get(key);
        if (i != null) return i;
        int c = cls(owner), nt = nameAndType(name, desc);
        try {
            out.writeByte(tag);
            out.writeShort(c);
            out.writeShort(nt);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int idx = count++;
        refs.put(key, idx);
        return idx;
    }

    int field(String owner, String name, String desc) {
        return ref(9, owner, name, desc);
    }

    int method(String owner, String name, String desc) {
        return ref(10, owner, name, desc);
    }

    int interfaceMethod(String owner, String name, String desc) {
        return ref(11, owner, name, desc);
    }

    /** CONSTANT_MethodHandle: refKind 6 = invokeStatic. */
    int methodHandleStatic(String owner, String name, String desc) {
        Ref key = new Ref(6, owner, name, desc);
        Integer i = handles.get(key);
        if (i != null) return i;
        int ref = method(owner, name, desc);
        try {
            out.writeByte(15);
            out.writeByte(6);
            out.writeShort(ref);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int idx = count++;
        handles.put(key, idx);
        return idx;
    }

    /** CONSTANT_InvokeDynamic: bootstrap method index + call name/type. */
    int invokeDynamic(int bsmIndex, String name, String desc) {
        Indy key = new Indy(bsmIndex, name, desc);
        Integer i = indys.get(key);
        if (i != null) return i;
        int nt = nameAndType(name, desc);
        try {
            out.writeByte(18);
            out.writeShort(bsmIndex);
            out.writeShort(nt);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int idx = count++;
        indys.put(key, idx);
        return idx;
    }

    static final class UncheckedIOException extends RuntimeException {
        UncheckedIOException(IOException e) {
            super(e);
        }
    }
}
