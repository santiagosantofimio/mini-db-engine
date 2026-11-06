package dev.santiagosantofimio.minidb;

public final class SyntaxException extends MiniDbException {

    private final int line;
    private final int column;

    public SyntaxException(String message, int line, int column) {
        super(message);
        if (line < 1 || column < 1) {
            throw new IllegalArgumentException(
                    "line and column are 1-based, got line " + line + ", column " + column);
        }
        this.line = line;
        this.column = column;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }
}
