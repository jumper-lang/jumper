package me.padej.jumper.jit;

import me.padej.jumper.ast.FunctionNode;
import me.padej.jumper.ast.Stmts;
import me.padej.jumper.ast.VarType;
import me.padej.jumper.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** R3 of lang-issues.md: a primitive parameter must not ban the `dyn` local that shares its index number. */
class UnboxParamsTest {
    private static FunctionNode function(String src, String name) {
        FunctionNode main = new Parser(src).parseProgram();
        me.padej.jumper.ast.Stmt[] stmts = main.body instanceof Stmts.Block b ? b.stmts : new me.padej.jumper.ast.Stmt[]{main.body};
        for (Object s : stmts) if (s instanceof Stmts.FuncDecl d && d.fn.name.equals(name)) return d.fn;
        throw new AssertionError("no function " + name);
    }

    private static boolean anyInt(FunctionNode fn) {
        Unbox u = Unbox.analyze(fn, s -> true);
        for (int s = 0; s < fn.nslots; s++) if (u.slotType(s) == VarType.INT) return true;
        return false;
    }

    @Test
    void dynLocalNextToAPrimitiveParameterIsUnboxed() {
        org.junit.jupiter.api.Assumptions.assumeTrue(me.padej.jumper.runtime.Opts.on("unbox"), "unboxing is off");
        String body = "{ dyn n = 0; for (int i = 0; i < 10; i++) n += i; return n; }";
        for (String params : new String[] {"boolean c", "int k", "double d, boolean c", "long a, int b"}) {
            FunctionNode h = function("dyn h(" + params + ") " + body + " return 0;", "h");
            assertTrue(anyInt(h), params);
        }
        // a `dyn` parameter itself stays boxed (its type is fixed by the body's signature)
        FunctionNode g = function("dyn g(dyn n) { for (int i = 0; i < 10; i++) n += i; return n; } return 0;", "g");
        assertFalse(anyInt(g));
    }
}
