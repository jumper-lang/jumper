package me.padej.jumper.workspace;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A class file read as data (JVMS chapter 4): the constant pool, the class, its fields and methods.
 * Nothing is loaded, linked or initialized - a broken or foreign class file is just an IOException, and a
 * class whose dependencies are missing reads as well as any other. Attributes (code, generics, annotations)
 * are skipped: a tool gets names and descriptors, which is what completion, hover and policy checks need.
 *
 * <p>Written by hand, without java.lang.classfile (final only in JDK 24) and without ASM: the language
 * has no dependencies and targets Java 21.
 */
public final class ClassFiles {
    private ClassFiles() {}

    public static ClassInfo read(byte[] bytes) throws IOException {
        try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0xCAFEBABE) throw new IOException("not a class file");
            in.readUnsignedShort();   // minor
            in.readUnsignedShort();   // major: any - the layout of what is read here has not changed
            int n = in.readUnsignedShort();
            String[] utf8 = new String[n];
            int[] classNameIndex = new int[n];
            for (int i = 1; i < n; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8[i] = in.readUTF();                       // Utf8 (modified UTF-8, as DataInput reads it)
                    case 7 -> classNameIndex[i] = in.readUnsignedShort();   // Class
                    case 8, 16, 19, 20 -> in.readUnsignedShort();           // String, MethodType, Module, Package
                    case 3, 4 -> in.readInt();                              // Integer, Float
                    case 5, 6 -> { in.readLong(); i++; }                    // Long, Double: two slots
                    case 9, 10, 11, 12, 17, 18 -> in.readInt();             // refs, NameAndType, Dynamic, InvokeDynamic
                    case 15 -> { in.readUnsignedByte(); in.readUnsignedShort(); }   // MethodHandle
                    default -> throw new IOException("bad constant pool tag " + tag + " at " + i);
                }
            }
            int access = in.readUnsignedShort();
            String name = className(utf8, classNameIndex, in.readUnsignedShort());
            int superIdx = in.readUnsignedShort();
            String superName = superIdx == 0 ? null : className(utf8, classNameIndex, superIdx);
            int ni = in.readUnsignedShort();
            List<String> interfaces = new ArrayList<>(ni);
            for (int i = 0; i < ni; i++) interfaces.add(className(utf8, classNameIndex, in.readUnsignedShort()));
            List<ClassInfo.Member> fields = members(in, utf8);
            List<ClassInfo.Member> methods = members(in, utf8);
            return new ClassInfo(name, access, superName, List.copyOf(interfaces), fields, methods);
        } catch (IndexOutOfBoundsException | NullPointerException e) {
            throw new IOException("broken class file: " + e, e);
        }
    }

    private static List<ClassInfo.Member> members(DataInputStream in, String[] utf8) throws IOException {
        int count = in.readUnsignedShort();
        List<ClassInfo.Member> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int access = in.readUnsignedShort();
            String name = utf8[in.readUnsignedShort()];
            String desc = utf8[in.readUnsignedShort()];
            skipAttributes(in);
            out.add(new ClassInfo.Member(name, desc, access));
        }
        return List.copyOf(out);
    }

    private static void skipAttributes(DataInputStream in) throws IOException {
        int n = in.readUnsignedShort();
        for (int i = 0; i < n; i++) {
            in.readUnsignedShort();
            in.skipNBytes(in.readInt() & 0xFFFFFFFFL);
        }
    }

    private static String className(String[] utf8, int[] classNameIndex, int idx) throws IOException {
        String internal = utf8[classNameIndex[idx]];
        if (internal == null) throw new IOException("bad class reference " + idx);
        return internal.replace('/', '.');
    }
}
