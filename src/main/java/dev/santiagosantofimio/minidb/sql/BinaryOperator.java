package dev.santiagosantofimio.minidb.sql;

public enum BinaryOperator {
    OR("OR", Kind.LOGICAL, 1),
    AND("AND", Kind.LOGICAL, 2),
    EQUAL("=", Kind.COMPARISON, 4),
    NOT_EQUAL("<>", Kind.COMPARISON, 4),
    LESS("<", Kind.COMPARISON, 4),
    LESS_OR_EQUAL("<=", Kind.COMPARISON, 4),
    GREATER(">", Kind.COMPARISON, 4),
    GREATER_OR_EQUAL(">=", Kind.COMPARISON, 4),
    ADD("+", Kind.ARITHMETIC, 5),
    SUBTRACT("-", Kind.ARITHMETIC, 5),
    MULTIPLY("*", Kind.ARITHMETIC, 6),
    DIVIDE("/", Kind.ARITHMETIC, 6);

    public enum Kind {
        LOGICAL,
        COMPARISON,
        ARITHMETIC
    }

    private final String symbol;
    private final Kind kind;
    private final int precedence;

    BinaryOperator(String symbol, Kind kind, int precedence) {
        this.symbol = symbol;
        this.kind = kind;
        this.precedence = precedence;
    }

    public String symbol() {
        return symbol;
    }

    public Kind kind() {
        return kind;
    }

    public int precedence() {
        return precedence;
    }
}
