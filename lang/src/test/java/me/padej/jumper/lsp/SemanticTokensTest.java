package me.padej.jumper.lsp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** What each word of a text is colored as: `word:type[.modifier...]`. */
class SemanticTokensTest {
    static String render(String text) {
        SemanticTokens st = new SemanticTokens(text, n -> switch (n) {
            case "HashMap" -> java.util.HashMap.class;
            case "List" -> java.util.List.class;
            case "Math" -> Math.class;
            default -> null;
        }, Set.of("server"), Set.of("println", "len"));
        String[] lines = text.split("\n", -1);
        List<String> out = new ArrayList<>();
        for (SemanticTokens.Piece p : st.pieces()) {
            StringBuilder sb = new StringBuilder(lines[p.line()].substring(p.col(), p.col() + p.length()))
                    .append(':').append(SemanticTokens.TYPES.get(p.type()));
            for (int m = 0; m < SemanticTokens.MODIFIERS.size(); m++)
                if ((p.modifiers() & (1 << m)) != 0) sb.append('.').append(SemanticTokens.MODIFIERS.get(m));
            out.add(sb.toString());
        }
        return String.join(" ", out);
    }

    public static void main(String[] a) throws Exception {
        System.out.println(render(java.nio.file.Files.readString(java.nio.file.Path.of(a[0]))).replace(" ", "\n"));
    }

    @Test
    void keywordsTypesAndLiterals() {
        assertEquals("import:keyword java:namespace util:namespace HashMap:class dyn:keyword m:variable.declaration "
                        + "new:keyword HashMap:class int:keyword n:variable.declaration 5:number String:class.defaultLibrary "
                        + "s:variable.declaration \"hi\":string // note:comment",
                render("import java.util.HashMap;\ndyn m = new HashMap();\nint n = 5; String s = \"hi\"; // note"));
    }

    @Test
    void tableKeysAreProperties() {
        assertEquals("dyn:keyword t:variable.declaration name:property.declaration \"x\":string inner:property.declaration "
                        + "a:property.declaration 1:number \"q\":string 2:number t:variable inner:property a:property "
                        + "dyn:keyword r:variable.declaration c:variable a:variable b:variable",
                render("dyn t = { name: \"x\", inner: { a: 1 }, \"q\": 2 };\nt.inner.a;\ndyn r = c ? a : b;"));
    }

    @Test
    void functionsParametersAndCalls() {
        assertEquals("void:keyword greet:function.declaration dyn:keyword p:parameter.declaration int:keyword "
                        + "n:parameter.declaration println:function.defaultLibrary p:parameter n:parameter "
                        + "server:variable.readonly.defaultLibrary log:method p:parameter name:method "
                        + "dyn:keyword f:variable.declaration x:parameter.declaration x:parameter 1:number "
                        + "dyn:keyword g:variable.declaration a:parameter.declaration b:parameter.declaration return:keyword a:parameter b:parameter "
                        + "greet:function f:variable",
                render("void greet(dyn p, int n) {\n    println(p, n);\n    server.log(p.name());\n}\n"
                        + "dyn f = x -> x + 1;\ndyn g = (a, b) -> { return a * b; };\ngreet(f, 2);".replace("greet(f, 2)", "greet(f)")));
    }

    @Test
    void classesFieldsAndMethods() {
        assertEquals("class:keyword Point:class.declaration extends:keyword Base:class int:keyword x:property.declaration "
                        + "static:keyword List:interface all:property.declaration.static Point:class.declaration int:keyword "
                        + "x:parameter.declaration this:keyword x:property x:parameter int:keyword len:method.declaration "
                        + "return:keyword Math:class abs:method x:property Point:class p:variable.declaration new:keyword "
                        + "Point:class 1:number",
                render("class Point extends Base {\n    int x;\n    static List all;\n    Point(int x) { this.x = x; }\n"
                        + "    int len() { return Math.abs(x); }\n}\nPoint p = new Point(1);"));
    }

    @Test
    void blockCommentsSplitByLine() {
        assertEquals("/* a:comment b */:comment dyn:keyword x:variable.declaration", render("/* a\nb */ dyn x;"));
    }

    @Test
    void theEncodingIsRelative() {
        SemanticTokens st = new SemanticTokens("dyn x;\n  int y;", n -> null, Set.of(), Set.of());
        assertArrayEquals(new int[] {0, 0, 3, 10, 0, 0, 4, 1, 6, 1, 1, 2, 3, 10, 0, 0, 4, 1, 6, 1}, st.encode());
    }
}
