package me.padej.jumper.lsp;

import me.padej.jumper.lexer.Token;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The symbols of a text and their uses: scoping, members, the outline. */
class SymbolsTest {
    /** Each name of the text: `name` if it is not a symbol of the file, else `name>line:col` - where it is declared. */
    static String uses(String text) {
        Symbols s = new Symbols(text);
        List<String> out = new ArrayList<>();
        for (int k = 0; k < s.toks.size(); k++) {
            Token t = s.toks.get(k);
            if (t.type() != me.padej.jumper.lexer.TokenType.IDENT) continue;
            Symbols.Sym sym = s.symbolOf(k);
            if (sym == null) { out.add(t.text()); continue; }
            Token d = s.toks.get(sym.nameTok);
            out.add(t.text() + ">" + d.line() + ":" + d.col());
        }
        return String.join(" ", out);
    }

    static String outline(Symbols s, List<Symbols.Sym> syms) {
        List<String> out = new ArrayList<>();
        for (Symbols.Sym x : syms) {
            String kids = x.children.isEmpty() ? "" : "{" + outline(s, x.children) + "}";
            out.add(x.kind.name().toLowerCase() + " " + x.name + (x.detail == null ? "" : " " + x.detail) + kids);
        }
        return String.join(", ", out);
    }

    public static void main(String[] a) throws Exception {
        String t = java.nio.file.Files.readString(java.nio.file.Path.of(a[0]));
        System.out.println(uses(t).replace(" ", "\n"));
        Symbols s = new Symbols(t);
        System.out.println(outline(s, s.outline()));
    }

    @Test
    void blocksShadowingAndDeclarationOrder() {
        String text = """
                dyn x = 1;
                { dyn y = x; dyn x = x + y; x; }
                x;
                later();
                void later() { dyn x = 2; x; }
                """;
        assertEquals("x>1:5 y>2:7 x>1:5 x>2:18 x>1:5 y>2:7 x>2:18 x>1:5 later>5:6 later>5:6 x>5:20 x>5:20", uses(text));
    }

    @Test
    void parametersLambdasCatchAndFor() {
        String text = """
                int add(int a, int b) { return a + b; }
                dyn f = (a, c) -> a * c;
                dyn g = z -> { return z; };
                a;
                for (int i = 0; i < 3; i++) { i; }
                for (dyn v : [1]) { v; }
                i; v;
                try { } catch (e) { e; }
                e;
                """;
        assertEquals("add>1:5 a>1:13 b>1:20 a>1:13 b>1:20 f>2:5 a>2:10 c>2:13 a>2:10 c>2:13 g>3:5 z>3:9 z>3:9 a "
                + "i>5:10 i>5:10 i>5:10 i>5:10 v>6:10 v>6:10 i v e>8:16 e>8:16 e", uses(text));
    }

    @Test
    void classMembersThisAndStatics() {
        String text = """
                dyn count = 0;
                class Shape {
                    String name;
                    static int count = 0;
                    Shape(String name) { this.name = name; count++; }
                    String label() { return name + count; }
                }
                class Circle extends Shape {
                    double r;
                    Circle() { super("c"); r = 1; }
                    String label() { return name + r; }
                }
                Shape.count;
                Circle c = new Circle();
                c.label();
                count;
                """;
        assertEquals("count>1:5 Shape>2:7 name>3:12 count>4:16 Shape>5:5 name>5:18 name>3:12 name>5:18 count>4:16 "
                        + "label>6:12 name>3:12 count>4:16 Circle>8:7 Shape>2:7 r>9:12 Circle>10:5 r>9:12 label>11:12 name>3:12 r>9:12 "
                        + "Shape>2:7 count>4:16 Circle>8:7 c>14:8 Circle>8:7 c>14:8 label count>1:5",
                uses(text));
    }

    @Test
    void tableKeysImportsAndMembersOfOthersAreNotNames() {
        String text = """
                import java.util.HashMap;
                dyn name = 1;
                dyn t = { name: name, inner: { name: 2 } };
                t.name;
                dyn m = new HashMap();
                server.log(name);
                """;
        assertEquals("java util HashMap name>2:5 t>3:5 name name>2:5 inner name t>3:5 name m>5:5 HashMap server log name>2:5",
                uses(text));
    }

    @Test
    void theOutline() {
        String text = """
                import java.util.HashMap;
                dyn visits = new HashMap();
                int a = 1, b = 2;
                class Shape {
                    String name;
                    static int count;
                    Shape(String name) { }
                    double area() { return 0; }
                }
                void onJoin(dyn p) {
                    dyn local = 1;
                    int helper(int x) { return x; }
                }
                dyn f = x -> x;
                """;
        Symbols s = new Symbols(text);
        assertEquals("variable visits dyn, variable a int, variable b int, class Shape{field name String, field count int, "
                + "constructor Shape (String name), method area (): double}, function onJoin (dyn p){function helper (int x): int}, variable f dyn",
                outline(s, s.outline()));
    }
}
