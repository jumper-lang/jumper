package me.padej.jumper.parser;

/**
 * Where a node of the tree comes from in the source: 1-based line and column of its first character,
 * and the position just after its last one (exclusive, as an LSP range). Kept by the parser in a side
 * table ({@link Parser#ranges()}), not in the nodes: execution never needs it, and every byte of a node
 * counts in the interpreter.
 */
public record Range(int startLine, int startCol, int endLine, int endCol) {
    public boolean contains(Range r) {
        return (r.startLine > startLine || r.startLine == startLine && r.startCol >= startCol)
                && (r.endLine < endLine || r.endLine == endLine && r.endCol <= endCol);
    }

    @Override
    public String toString() {
        return startLine + ":" + startCol + "-" + endLine + ":" + endCol;
    }
}
