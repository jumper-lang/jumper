package me.padej.jumper.workspace;

import java.util.List;

/**
 * What a tool needs to know about a Java class, read from its class file without loading it: its name,
 * supertypes and members with their JVM descriptors. Names are binary names with dots
 * ({@code example.api.World}, {@code java.util.Map$Entry}).
 */
public record ClassInfo(String name, int access, String superName, List<String> interfaces,
                        List<Member> fields, List<Member> methods) {

    public static final int ACC_PUBLIC = 0x0001, ACC_PRIVATE = 0x0002, ACC_PROTECTED = 0x0004, ACC_STATIC = 0x0008,
            ACC_FINAL = 0x0010, ACC_SYNTHETIC = 0x1000, ACC_INTERFACE = 0x0200, ACC_ABSTRACT = 0x0400,
            ACC_ANNOTATION = 0x2000, ACC_ENUM = 0x4000, ACC_BRIDGE = 0x0040, ACC_VARARGS = 0x0080;

    /** A field or method: `name`, JVM `descriptor` (`(ILjava/lang/String;)V`), access flags. */
    public record Member(String name, String descriptor, int access) {
        public boolean isPublic() { return (access & ACC_PUBLIC) != 0; }
        public boolean isStatic() { return (access & ACC_STATIC) != 0; }
    }

    public boolean isPublic() { return (access & ACC_PUBLIC) != 0; }
    public boolean isInterface() { return (access & ACC_INTERFACE) != 0; }

    /** The package: `example.api` for `example.api.World`, "" for the default package. */
    public String packageName() {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(0, i);
    }

    /** Simple name as source code writes it: `Entry` for `java.util.Map$Entry`. */
    public String simpleName() {
        String s = name.substring(name.lastIndexOf('.') + 1);
        return s.substring(s.lastIndexOf('$') + 1);
    }
}
