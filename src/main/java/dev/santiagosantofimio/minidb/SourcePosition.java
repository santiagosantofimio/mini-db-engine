package dev.santiagosantofimio.minidb;

public record SourcePosition(int line, int column) {

    public SourcePosition {
        if (line < 1 || column < 1) {
            throw new IllegalArgumentException(
                    "line and column are 1-based, got line " + line + ", column " + column);
        }
    }

    @Override
    public String toString() {
        return "line " + line + ", column " + column;
    }
}
