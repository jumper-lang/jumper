package me.padej.jumper.jit;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Bytecode emitter for one method: simulates the operand stack (for max_stack and StackMapTable),
 * resolves labels, collects the LineNumberTable. Types: "I" (int/boolean), "J", "D", "null",
 * otherwise the internal name of the reference class.
 *
 * Invariants that Compiler guarantees:
 *  - every local variable has one type for the whole method and is initialized in the prologue;
 *  - no code is emitted after an unconditional jump until a label is placed.
 */
final class Code {
    static final class Label {
        int pos = -1;
        boolean targeted;
        List<String> stack; // expected stack at the label
        final List<int[]> fixups = new ArrayList<>(); // [branchInstrPos, operandPos]
    }

    static final class TooLarge extends RuntimeException {
        TooLarge() { super("method too large for Tier 1"); }
    }

    final ConstPool cp;
    private final ByteArrayOutputStream body = new ByteArrayOutputStream(1024);
    private final List<String> locals = new ArrayList<>(); // by slot; the second slot of a long/double = "TOP2"
    private final int firstNonParam;
    private final List<String> stack = new ArrayList<>();
    private int stackSlots = 0, maxStack = 0;
    private boolean reachable = true;
    private final List<Label> labels = new ArrayList<>();
    private final List<int[]> lines = new ArrayList<>(); // [pos, line]
    private int lastLine = -1;
    private final List<Object[]> handlers = new ArrayList<>(); // [Label start, Label end, Label handler, String type|null]

    /** @param paramTypes types of this (if any) and of the parameters - already initialized by the JVM. */
    Code(ConstPool cp, List<String> paramTypes) {
        this.cp = cp;
        for (String t : paramTypes) declareLocal(t);
        this.firstNonParam = locals.size();
    }

    // ---------- locals ----------
    int declareLocal(String type) {
        int slot = locals.size();
        locals.add(type);
        if (type.equals("J") || type.equals("D")) locals.add("TOP2");
        return slot;
    }

    String localType(int slot) {
        return locals.get(slot);
    }

    // ---------- stack sim ----------
    private static int width(String t) {
        return t.equals("J") || t.equals("D") ? 2 : 1;
    }

    private void push(String t) {
        stack.add(t);
        stackSlots += width(t);
        if (stackSlots > maxStack) maxStack = stackSlots;
    }

    private String pop() {
        if (stack.isEmpty()) throw new IllegalStateException("stack underflow at " + body.size());
        String t = stack.remove(stack.size() - 1);
        stackSlots -= width(t);
        return t;
    }

    private void pop(int n) {
        for (int i = 0; i < n; i++) pop();
    }

    String peek() {
        return stack.get(stack.size() - 1);
    }

    int stackDepth() {
        return stack.size();
    }

    // ---------- raw emit ----------
    private void u1(int b) {
        body.write(b);
    }

    private void u2(int v) {
        body.write((v >> 8) & 0xFF);
        body.write(v & 0xFF);
    }

    private void u4(int v) {
        u2((v >> 16) & 0xFFFF);
        u2(v & 0xFFFF);
    }

    int pos() {
        return body.size();
    }

    boolean reachable() {
        return reachable;
    }

    private boolean dead() {
        return !reachable;
    }

    void line(int line) {
        if (dead() || line <= 0 || line == lastLine) return;
        lines.add(new int[]{pos(), line});
        lastLine = line;
    }

    // ---------- labels ----------
    Label label() {
        Label l = new Label();
        labels.add(l);
        return l;
    }

    /** Merge of stack types where paths join: references of different classes -> Object, null is absorbed. */
    private static List<String> merge(List<String> a, List<String> b) {
        if (a.size() != b.size()) throw new IllegalStateException("stack depth mismatch: " + a + " vs " + b);
        List<String> out = new ArrayList<>(a.size());
        for (int i = 0; i < a.size(); i++) {
            String x = a.get(i), y = b.get(i);
            if (x.equals(y)) out.add(x);
            else if (x.equals("null")) out.add(y);
            else if (y.equals("null")) out.add(x);
            else if (isRef(x) && isRef(y)) out.add("java/lang/Object");
            else throw new IllegalStateException("stack type mismatch: " + a + " vs " + b);
        }
        return out;
    }

    private static boolean isRef(String t) {
        return !(t.equals("I") || t.equals("J") || t.equals("D"));
    }

    void mark(Label l) {
        if (l.pos >= 0) throw new IllegalStateException("label marked twice");
        if (reachable) {
            l.stack = l.stack == null ? new ArrayList<>(stack) : merge(l.stack, stack);
            stack.clear();
            stackSlots = 0;
            for (String t : l.stack) push(t);
        } else {
            l.pos = pos();
            if (l.stack == null && !l.targeted) return; // dead code: nobody jumps here - we stay unreachable
            stack.clear();
            stackSlots = 0;
            for (String t : l.stack) push(t);
            reachable = true;
            return;
        }
        l.pos = pos();
    }

    /** Handler label: the stack at entry = [exception type]. Placed via markHandler. */
    Label handlerLabel(String type) {
        Label l = label();
        l.stack = new ArrayList<>(List.of(type == null ? "java/lang/Throwable" : type));
        l.targeted = true;
        return l;
    }

    void markHandler(Label l) {
        if (reachable) throw new IllegalStateException("handler must follow unconditional control transfer");
        mark(l);
    }

    /** Exception table entry: [start, end) -> handler for type (null = any). */
    void tryCatch(Label start, Label end, Label handler, String type) {
        handlers.add(new Object[]{start, end, handler, type});
    }

    /** Label without a jump - only a position (for try-block bounds). */
    void markPos(Label l) {
        l.pos = pos();
    }

    void athrowKeep() { if (dead()) return; pop(); u1(0xBF); reachable = false; }

    private void branch(int opcode, Label l, int pops) {
        if (dead()) return;
        pop(pops);
        int at = pos();
        u1(opcode);
        l.stack = l.stack == null ? new ArrayList<>(stack) : merge(l.stack, stack);
        l.targeted = true;
        l.fixups.add(new int[]{at, pos()});
        u2(0);
        if (opcode == 0xA7) reachable = false; // goto
    }

    void goTo(Label l) { branch(0xA7, l, 0); }
    void ifeq(Label l) { branch(0x99, l, 1); }
    void ifne(Label l) { branch(0x9A, l, 1); }
    void iflt(Label l) { branch(0x9B, l, 1); }
    void ifge(Label l) { branch(0x9C, l, 1); }
    void ifgt(Label l) { branch(0x9D, l, 1); }
    void ifle(Label l) { branch(0x9E, l, 1); }
    void if_icmpeq(Label l) { branch(0x9F, l, 2); }
    void if_icmpne(Label l) { branch(0xA0, l, 2); }
    void if_icmplt(Label l) { branch(0xA1, l, 2); }
    void if_icmpge(Label l) { branch(0xA2, l, 2); }
    void if_icmpgt(Label l) { branch(0xA3, l, 2); }
    void if_icmple(Label l) { branch(0xA4, l, 2); }
    void if_acmpeq(Label l) { branch(0xA5, l, 2); }
    void if_acmpne(Label l) { branch(0xA6, l, 2); }
    void ifnull(Label l) { branch(0xC6, l, 1); }
    void ifnonnull(Label l) { branch(0xC7, l, 1); }

    // ---------- constants ----------
    void iconst(int v) {
        if (dead()) return;
        if (v >= -1 && v <= 5) u1(0x03 + v);
        else if (v >= -128 && v <= 127) { u1(0x10); u1(v & 0xFF); }
        else if (v >= -32768 && v <= 32767) { u1(0x11); u2(v & 0xFFFF); }
        else ldc(cp.integer(v));
        push("I");
    }

    void lconst(long v) {
        if (dead()) return;
        if (v == 0 || v == 1) u1(0x09 + (int) v);
        else { u1(0x14); u2(cp.longConst(v)); }
        push("J");
    }

    void dconst(double v) {
        if (dead()) return;
        if (v == 0.0 && Double.doubleToRawLongBits(v) == 0L) u1(0x0E);
        else if (v == 1.0) u1(0x0F);
        else { u1(0x14); u2(cp.doubleConst(v)); }
        push("D");
    }

    private void ldc(int idx) {
        if (idx < 256) { u1(0x12); u1(idx); } else { u1(0x13); u2(idx); }
    }

    /** ldc of a class constant. */
    void ldcClass(String internalName) {
        if (dead()) return;
        ldc(cp.cls(internalName));
        push("java/lang/Class");
    }

    void ldcString(String s) {
        if (dead()) return;
        ldc(cp.string(s));
        push("java/lang/String");
    }

    void aconstNull() {
        if (dead()) return;
        u1(0x01);
        push("null");
    }

    // ---------- locals ----------
    private void varInsn(int shortBase, int longOp, int slot) {
        if (slot <= 3) u1(shortBase + slot);
        else if (slot < 256) { u1(longOp); u1(slot); }
        else { u1(0xC4); u1(longOp); u2(slot); }
    }

    void iload(int s) { if (dead()) return; varInsn(0x1A, 0x15, s); push("I"); }
    void lload(int s) { if (dead()) return; varInsn(0x1E, 0x16, s); push("J"); }
    void dload(int s) { if (dead()) return; varInsn(0x26, 0x18, s); push("D"); }
    void aload(int s) { if (dead()) return; varInsn(0x2A, 0x19, s); push(locals.get(s)); }
    void istore(int s) { if (dead()) return; pop(); varInsn(0x3B, 0x36, s); }
    void lstore(int s) { if (dead()) return; pop(); varInsn(0x3F, 0x37, s); }
    void dstore(int s) { if (dead()) return; pop(); varInsn(0x47, 0x39, s); }
    void astore(int s) { if (dead()) return; pop(); varInsn(0x4B, 0x3A, s); }

    void iinc(int slot, int delta) {
        if (dead()) return;
        if (slot < 256 && delta >= -128 && delta <= 127) { u1(0x84); u1(slot); u1(delta & 0xFF); }
        else { u1(0xC4); u1(0x84); u2(slot); u2(delta & 0xFFFF); }
    }

    /** load/store by the type of the local variable. */
    void load(int slot) {
        switch (locals.get(slot)) {
            case "I" -> iload(slot);
            case "J" -> lload(slot);
            case "D" -> dload(slot);
            default -> aload(slot);
        }
    }

    void store(int slot) {
        switch (locals.get(slot)) {
            case "I" -> istore(slot);
            case "J" -> lstore(slot);
            case "D" -> dstore(slot);
            default -> astore(slot);
        }
    }

    // ---------- stack ops ----------
    void pop1() { if (dead()) return; String t = pop(); u1(width(t) == 2 ? 0x58 : 0x57); }

    void swap() {
        if (dead()) return;
        String a = pop(), b = pop();
        u1(0x5F);
        push(a);
        push(b);
    }

    /** dup_x1 for two single slots: ..., a, b -> ..., b, a, b */
    void dup_x1() {
        if (dead()) return;
        String b = pop(), a = pop();
        u1(0x5A);
        push(b); push(a); push(b);
    }

    /** dup_x2 for three single slots: ..., a, b, c -> ..., c, a, b, c */
    void dup_x2() {
        if (dead()) return;
        String cc = pop(), b = pop(), a = pop();
        u1(0x5B);
        push(cc); push(a); push(b); push(cc);
    }

    /** dup or dup2 depending on the width of the value on top. */
    void dupWide() {
        if (dead()) return;
        String t = stack.get(stack.size() - 1);
        push(t);
        u1(width(t) == 2 ? 0x5C : 0x59);
    }

    void dup() {
        if (dead()) return;
        String t = peek();
        u1(width(t) == 2 ? 0x5C : 0x59);
        push(t);
    }

    // ---------- arithmetic ----------
    private void binop(int opcode, String t) {
        if (dead()) return;
        pop(2);
        u1(opcode);
        push(t);
    }

    void iadd() { binop(0x60, "I"); }
    void isub() { binop(0x64, "I"); }
    void imul() { binop(0x68, "I"); }
    void idiv() { binop(0x6C, "I"); }
    void irem() { binop(0x70, "I"); }
    void iand() { binop(0x7E, "I"); }
    void ior() { binop(0x80, "I"); }
    void ixor() { binop(0x82, "I"); }
    void ishl() { binop(0x78, "I"); }
    void ishr() { binop(0x7A, "I"); }
    void iushr() { binop(0x7C, "I"); }
    void ladd() { binop(0x61, "J"); }
    void lsub() { binop(0x65, "J"); }
    void lmul() { binop(0x69, "J"); }
    void ldiv() { binop(0x6D, "J"); }
    void lrem() { binop(0x71, "J"); }
    void land() { binop(0x7F, "J"); }
    void lor() { binop(0x81, "J"); }
    void lxor() { binop(0x83, "J"); }
    void lshl() { binop(0x79, "J"); }
    void lshr() { binop(0x7B, "J"); }
    void lushr() { binop(0x7D, "J"); }
    void dadd() { binop(0x63, "D"); }
    void dsub() { binop(0x67, "D"); }
    void dmul() { binop(0x6B, "D"); }
    void ddiv() { binop(0x6F, "D"); }
    void drem() { binop(0x73, "D"); }
    void lcmp() { binop(0x94, "I"); }
    void dcmpl() { binop(0x97, "I"); }
    void dcmpg() { binop(0x98, "I"); }

    private void unop(int opcode, String t) {
        if (dead()) return;
        pop();
        u1(opcode);
        push(t);
    }

    void ineg() { unop(0x74, "I"); }
    void lneg() { unop(0x75, "J"); }
    void dneg() { unop(0x77, "D"); }
    void i2l() { unop(0x85, "J"); }
    void i2d() { unop(0x87, "D"); }
    void l2i() { unop(0x88, "I"); }
    void l2d() { unop(0x8A, "D"); }
    void d2i() { unop(0x8E, "I"); }
    void d2l() { unop(0x8F, "J"); }

    // ---------- objects / arrays ----------
    void getfield(String owner, String name, String desc) {
        if (dead()) return;
        pop();
        u1(0xB4);
        u2(cp.field(owner, name, desc));
        push(typeOf(desc));
    }

    void putfield(String owner, String name, String desc) {
        if (dead()) return;
        pop(2);
        u1(0xB5);
        u2(cp.field(owner, name, desc));
    }

    void getstatic(String owner, String name, String desc) {
        if (dead()) return;
        u1(0xB2);
        u2(cp.field(owner, name, desc));
        push(typeOf(desc));
    }

    void newObj(String cls) {
        if (dead()) return;
        u1(0xBB);
        u2(cp.cls(cls));
        push(cls); // simplification: uninitialized is not tracked - new is always immediately followed by dup+invokespecial with no labels in between
    }

    void anewarray(String cls) {
        if (dead()) return;
        pop();
        u1(0xBD);
        u2(cp.cls(cls));
        push("[L" + cls + ";");
    }

    /** newarray long: the count is on the stack. */
    void newarrayLong() {
        if (dead()) return;
        pop();
        u1(0xBC);
        u1(11);
        push("[J");
    }

    void aaload() { if (dead()) return; pop(2); u1(0x32); push("java/lang/Object"); }
    void aastore() { if (dead()) return; pop(3); u1(0x53); }
    void laload() { if (dead()) return; pop(2); u1(0x2F); push("J"); }
    void lastore() { if (dead()) return; pop(3); u1(0x50); }
    // Direct access to a Java array when the element type is known statically (Compiler.directArray).
    void iaload() { if (dead()) return; pop(2); u1(0x2E); push("I"); }
    void daload() { if (dead()) return; pop(2); u1(0x31); push("D"); }
    void baload() { if (dead()) return; pop(2); u1(0x33); push("I"); }
    void iastore() { if (dead()) return; pop(3); u1(0x4F); }
    void dastore() { if (dead()) return; pop(3); u1(0x52); }
    void bastore() { if (dead()) return; pop(3); u1(0x54); }
    void arraylength() { if (dead()) return; pop(); u1(0xBE); push("I"); }

    void checkcast(String cls) {
        if (dead()) return;
        pop();
        u1(0xC0);
        u2(cp.cls(cls));
        push(cls);
    }

    void instanceOf(String cls) {
        if (dead()) return;
        pop();
        u1(0xC1);
        u2(cp.cls(cls));
        push("I");
    }

    void athrow() { if (dead()) return; pop(); u1(0xBF); reachable = false; }

    // ---------- invocations ----------
    private void invoke(int opcode, int ref, String desc, boolean hasReceiver) {
        if (dead()) return;
        int n = argCount(desc) + (hasReceiver ? 1 : 0);
        pop(n);
        u1(opcode);
        u2(ref);
        if (opcode == 0xB9) { u1(argSlots(desc) + 1); u1(0); }
        String ret = desc.substring(desc.indexOf(')') + 1);
        if (!ret.equals("V")) push(typeOf(ret));
    }

    void invokedynamic(int bsmIndex, String name, String desc) {
        if (dead()) return;
        pop(argCount(desc));
        u1(0xBA);
        u2(cp.invokeDynamic(bsmIndex, name, desc));
        u1(0);
        u1(0);
        String ret = desc.substring(desc.indexOf(')') + 1);
        if (!ret.equals("V")) push(typeOf(ret));
    }

    void invokevirtual(String owner, String name, String desc) { invoke(0xB6, cp.method(owner, name, desc), desc, true); }
    void invokespecial(String owner, String name, String desc) { invoke(0xB7, cp.method(owner, name, desc), desc, true); }
    void invokestatic(String owner, String name, String desc) { invoke(0xB8, cp.method(owner, name, desc), desc, false); }
    void invokeinterface(String owner, String name, String desc) { invoke(0xB9, cp.interfaceMethod(owner, name, desc), desc, true); }

    // ---------- returns ----------
    void ireturn() { if (dead()) return; pop(); u1(0xAC); reachable = false; }
    void lreturn() { if (dead()) return; pop(); u1(0xAD); reachable = false; }
    void dreturn() { if (dead()) return; pop(); u1(0xAF); reachable = false; }
    void areturn() { if (dead()) return; pop(); u1(0xB0); reachable = false; }
    void vreturn() { if (dead()) return; u1(0xB1); reachable = false; }

    // ---------- descriptors ----------
    static String typeOf(String desc) {
        return switch (desc.charAt(0)) {
            case 'I', 'Z', 'B', 'S', 'C' -> "I";
            case 'J' -> "J";
            case 'D' -> "D";
            case 'F' -> throw new IllegalArgumentException("float unsupported");
            case 'L' -> desc.substring(1, desc.length() - 1);
            case '[' -> desc;
            default -> throw new IllegalArgumentException(desc);
        };
    }

    static List<String> argTypes(String desc) {
        List<String> out = new ArrayList<>();
        int i = 1;
        while (desc.charAt(i) != ')') {
            int start = i;
            while (desc.charAt(i) == '[') i++;
            if (desc.charAt(i) == 'L') i = desc.indexOf(';', i);
            i++;
            out.add(typeOf(desc.substring(start, i)));
        }
        return out;
    }

    static int argCount(String desc) {
        return argTypes(desc).size();
    }

    static int argSlots(String desc) {
        int n = 0;
        for (String t : argTypes(desc)) n += width(t);
        return n;
    }

    // ---------- assembly ----------
    /** Prologue: initialization of all non-parameter local variables. */
    private byte[] prologue() {
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        for (int s = firstNonParam; s < locals.size(); s++) {
            String t = locals.get(s);
            if (t.equals("TOP2")) continue;
            switch (t) {
                case "I" -> { p.write(0x03); storeInsn(p, 0x3B, 0x36, s); }
                case "J" -> { p.write(0x09); storeInsn(p, 0x3F, 0x37, s); }
                case "D" -> { p.write(0x0E); storeInsn(p, 0x47, 0x39, s); }
                default -> { p.write(0x01); storeInsn(p, 0x4B, 0x3A, s); }
            }
        }
        return p.toByteArray();
    }

    private static void storeInsn(ByteArrayOutputStream p, int shortBase, int longOp, int slot) {
        if (slot <= 3) p.write(shortBase + slot);
        else if (slot < 256) { p.write(longOp); p.write(slot); }
        else { p.write(0xC4); p.write(longOp); p.write(slot >> 8); p.write(slot & 0xFF); }
    }

    /** Returns the whole Code attribute (without the attribute name/length). */
    byte[] finish() {
        if (reachable) throw new IllegalStateException("method body falls off the end");
        byte[] pro = prologue();
        byte[] code = body.toByteArray();
        if (pro.length + code.length >= 65535) throw new TooLarge();
        // fixups
        for (Label l : labels) {
            if (!l.targeted) continue;
            if (l.pos < 0) throw new IllegalStateException("unmarked label");
            for (int[] fx : l.fixups) {
                int off = l.pos - fx[0];
                if (off < -32768 || off > 32767) throw new TooLarge();
                code[fx[1]] = (byte) (off >> 8);
                code[fx[1] + 1] = (byte) off;
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(1536);
        java.io.DataOutputStream d = new java.io.DataOutputStream(out);
        try {
            d.writeShort(Math.max(maxStack, 1) + 1);
            d.writeShort(locals.size());
            d.writeInt(pro.length + code.length);
            d.write(pro);
            d.write(code);
            List<Object[]> hs = new ArrayList<>();
            for (Object[] h : handlers) {
                Label a = (Label) h[0], b = (Label) h[1];
                if (a.pos < 0 || b.pos < 0 || a.pos >= b.pos) continue; // empty range
                hs.add(h);
            }
            d.writeShort(hs.size());
            for (Object[] h : hs) {
                d.writeShort(((Label) h[0]).pos + pro.length);
                d.writeShort(((Label) h[1]).pos + pro.length);
                d.writeShort(((Label) h[2]).pos + pro.length);
                d.writeShort(h[3] == null ? 0 : cp.cls((String) h[3]));
            }
            // attributes: StackMapTable, LineNumberTable
            byte[] smt = stackMapTable(pro.length, pro.length + code.length);
            byte[] lnt = lineNumberTable(pro.length);
            int nattr = (smt != null ? 1 : 0) + (lnt != null ? 1 : 0);
            d.writeShort(nattr);
            if (smt != null) {
                d.writeShort(cp.utf8("StackMapTable"));
                d.writeInt(smt.length);
                d.write(smt);
            }
            if (lnt != null) {
                d.writeShort(cp.utf8("LineNumberTable"));
                d.writeInt(lnt.length);
                d.write(lnt);
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        return out.toByteArray();
    }

    private byte[] stackMapTable(int shift, int codeLen) {
        List<Label> targets = new ArrayList<>();
        for (Label l : labels) if (l.targeted && l.pos + shift < codeLen) targets.add(l);
        targets.sort((a, b) -> Integer.compare(a.pos, b.pos));
        // deduplication by position
        List<Label> uniq = new ArrayList<>();
        for (Label l : targets) if (uniq.isEmpty() || uniq.get(uniq.size() - 1).pos != l.pos) uniq.add(l);
        if (uniq.isEmpty()) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        java.io.DataOutputStream d = new java.io.DataOutputStream(out);
        try {
            d.writeShort(uniq.size());
            // All local variables are declared up front and initialized by the prologue, so the set
            // of locals is the same in every frame. The first frame is a full_frame (it differs from
            // the implicit initial frame, which holds only the parameters), the rest are the compressed
            // forms of JVMS 4.7.4: same_frame (1 byte), same_locals_1_stack_item (2-4). Previously every frame
            // was a full_frame with the whole list of locals - up to half the size of the class file.
            List<String> ls = new ArrayList<>();
            for (String t : locals) if (!t.equals("TOP2")) ls.add(t);
            int prev = -1;
            boolean first = true;
            for (Label l : uniq) {
                int off = l.pos + shift;
                int delta = prev < 0 ? off : off - prev - 1;
                prev = off;
                List<String> st = l.stack == null ? List.of() : l.stack;
                if (!first && st.isEmpty()) {
                    if (delta < 64) d.writeByte(delta);                       // same_frame
                    else { d.writeByte(251); d.writeShort(delta); }           // same_frame_extended
                } else if (!first && st.size() == 1) {
                    if (delta < 64) d.writeByte(64 + delta);                  // same_locals_1_stack_item_frame
                    else { d.writeByte(247); d.writeShort(delta); }           // ..._extended
                    writeVType(d, st.get(0));
                } else {
                    d.writeByte(255); // full_frame
                    d.writeShort(delta);
                    d.writeShort(ls.size());
                    for (String t : ls) writeVType(d, t);
                    d.writeShort(st.size());
                    for (String t : st) writeVType(d, t);
                }
                first = false;
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        return out.toByteArray();
    }

    private void writeVType(java.io.DataOutputStream d, String t) throws java.io.IOException {
        switch (t) {
            case "I" -> d.writeByte(1);
            case "J" -> d.writeByte(4);
            case "D" -> d.writeByte(3);
            case "null" -> d.writeByte(5);
            default -> {
                d.writeByte(7);
                d.writeShort(cp.cls(t));
            }
        }
    }

    private byte[] lineNumberTable(int shift) {
        if (lines.isEmpty()) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        java.io.DataOutputStream d = new java.io.DataOutputStream(out);
        try {
            d.writeShort(lines.size());
            for (int[] e : lines) {
                d.writeShort(e[0] + shift);
                d.writeShort(e[1]);
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        return out.toByteArray();
    }
}
