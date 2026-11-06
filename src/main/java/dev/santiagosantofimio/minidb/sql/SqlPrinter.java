package dev.santiagosantofimio.minidb.sql;

import dev.santiagosantofimio.minidb.record.Value;
import dev.santiagosantofimio.minidb.record.Value.BooleanValue;
import dev.santiagosantofimio.minidb.record.Value.DoubleValue;
import dev.santiagosantofimio.minidb.record.Value.IntValue;
import dev.santiagosantofimio.minidb.record.Value.NullValue;
import dev.santiagosantofimio.minidb.record.Value.StringValue;
import dev.santiagosantofimio.minidb.sql.Expression.Between;
import dev.santiagosantofimio.minidb.sql.Expression.Binary;
import dev.santiagosantofimio.minidb.sql.Expression.ColumnReference;
import dev.santiagosantofimio.minidb.sql.Expression.IsNull;
import dev.santiagosantofimio.minidb.sql.Expression.Literal;
import dev.santiagosantofimio.minidb.sql.Expression.Negate;
import dev.santiagosantofimio.minidb.sql.Expression.Not;

public final class SqlPrinter {

    private static final int NOT_PRECEDENCE = 3;
    private static final int PREDICATE_PRECEDENCE = 4;
    private static final int ADDITIVE_PRECEDENCE = 5;
    private static final int UNARY_PRECEDENCE = 7;
    private static final int PRIMARY_PRECEDENCE = 8;

    private SqlPrinter() {
    }

    public static String print(Expression expression) {
        return switch (expression) {
            case Literal literal -> literal(literal.value());
            case ColumnReference column -> column.qualifier().map(q -> q + ".").orElse("") + column.name();
            case Negate negate -> "-" + operand(negate.operand(), UNARY_PRECEDENCE);
            case Not not -> "NOT " + operand(not.operand(), NOT_PRECEDENCE);
            case Binary binary -> binary(binary);
            case IsNull isNull -> operand(isNull.operand(), ADDITIVE_PRECEDENCE)
                    + (isNull.negated() ? " IS NOT NULL" : " IS NULL");
            case Between between -> operand(between.operand(), ADDITIVE_PRECEDENCE)
                    + (between.negated() ? " NOT BETWEEN " : " BETWEEN ")
                    + operand(between.low(), ADDITIVE_PRECEDENCE)
                    + " AND " + operand(between.high(), ADDITIVE_PRECEDENCE);
        };
    }

    public static String literal(Value value) {
        return switch (value) {
            case NullValue nullValue -> "NULL";
            case IntValue integer -> Integer.toString(integer.value());
            case DoubleValue real -> Double.toString(real.value());
            case BooleanValue bool -> bool.value() ? "TRUE" : "FALSE";
            case StringValue string -> "'" + string.value().replace("'", "''") + "'";
        };
    }

    private static String binary(Binary binary) {
        BinaryOperator operator = binary.operator();
        boolean comparison = operator.kind() == BinaryOperator.Kind.COMPARISON;
        int left = comparison ? ADDITIVE_PRECEDENCE : operator.precedence();
        int right = comparison ? ADDITIVE_PRECEDENCE : operator.precedence() + 1;
        return operand(binary.left(), left) + " " + operator.symbol() + " " + operand(binary.right(), right);
    }

    private static String operand(Expression expression, int minimumPrecedence) {
        String text = print(expression);
        return precedence(expression) < minimumPrecedence ? "(" + text + ")" : text;
    }

    private static int precedence(Expression expression) {
        return switch (expression) {
            case Binary binary -> binary.operator().precedence();
            case Not not -> NOT_PRECEDENCE;
            case IsNull isNull -> PREDICATE_PRECEDENCE;
            case Between between -> PREDICATE_PRECEDENCE;
            case Negate negate -> UNARY_PRECEDENCE;
            case Literal literal -> isNegativeNumber(literal.value()) ? UNARY_PRECEDENCE : PRIMARY_PRECEDENCE;
            case ColumnReference column -> PRIMARY_PRECEDENCE;
        };
    }

    private static boolean isNegativeNumber(Value value) {
        return switch (value) {
            case IntValue integer -> integer.value() < 0;
            case DoubleValue real -> real.value() < 0 || Double.compare(real.value(), -0.0) == 0;
            default -> false;
        };
    }
}
