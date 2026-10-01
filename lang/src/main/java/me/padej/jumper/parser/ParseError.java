package me.padej.jumper.parser;

public class ParseError extends RuntimeException {
    public final int line, col;
    /** The message without the position suffix - for tools that show the position their own way. */
    public final String reason;
    /** Error at end of input - the input is probably incomplete (for the REPL). */
    public boolean atEof;

    public ParseError(String msg, int line, int col) {
        super(msg + " (line " + line + ", col " + col + ")");
        this.reason = msg;
        this.line = line;
        this.col = col;
    }
}
