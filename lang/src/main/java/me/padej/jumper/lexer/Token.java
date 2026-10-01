package me.padej.jumper.lexer;

/**
 * A token. {@code col} is 1-based; {@code endCol} is the column just after its last source character
 * (a token never spans lines) - the text of a string or number token is not its source spelling.
 */
public record Token(TokenType type, String text, Object value, int line, int col, int endCol) {
    /** A token the parser synthesizes: its end is taken from its text. */
    public Token(TokenType type, String text, Object value, int line, int col) {
        this(type, text, value, line, col, col + text.length());
    }

    @Override
    public String toString() {
        return type + "(" + text + ")@" + line + ":" + col;
    }
}
