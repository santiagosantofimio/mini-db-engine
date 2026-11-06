package dev.santiagosantofimio.minidb.exec;

import dev.santiagosantofimio.minidb.QueryException;
import dev.santiagosantofimio.minidb.plan.Expr;
import dev.santiagosantofimio.minidb.plan.Expr.And;
import dev.santiagosantofimio.minidb.plan.Expr.Arithmetic;
import dev.santiagosantofimio.minidb.plan.Expr.ColumnRef;
import dev.santiagosantofimio.minidb.plan.Expr.Comparison;
import dev.santiagosantofimio.minidb.plan.Expr.Constant;
import dev.santiagosantofimio.minidb.plan.Expr.IsNull;
import dev.santiagosantofimio.minidb.plan.Expr.Negate;
import dev.santiagosantofimio.minidb.plan.Expr.Not;
import dev.santiagosantofimio.minidb.plan.Expr.Or;
import dev.santiagosantofimio.minidb.record.Tuple;
import dev.santiagosantofimio.minidb.record.Value;
import dev.santiagosantofimio.minidb.record.Value.BooleanValue;
import dev.santiagosantofimio.minidb.record.Value.DoubleValue;
import dev.santiagosantofimio.minidb.record.Value.IntValue;
import dev.santiagosantofimio.minidb.record.Value.StringValue;
import dev.santiagosantofimio.minidb.sql.BinaryOperator;

public final class Evaluator {

    private static final Tuple NO_ROW = Tuple.of();

    private Evaluator() {
    }

    public static Value evaluate(Expr expression) {
        return evaluate(expression, NO_ROW);
    }

    public static Value evaluate(Expr expression, Tuple row) {
        return switch (expression) {
            case Constant constant -> constant.value();
            case ColumnRef column -> row.get(column.index());
            case Negate negate -> negate(evaluate(negate.operand(), row));
            case Arithmetic arithmetic -> arithmetic(
                    arithmetic.operator(), evaluate(arithmetic.left(), row), evaluate(arithmetic.right(), row));
            case Comparison comparison -> compare(
                    comparison.operator(), evaluate(comparison.left(), row), evaluate(comparison.right(), row));
            case And and -> and(and, row);
            case Or or -> or(or, row);
            case Not not -> not(evaluate(not.operand(), row));
            case IsNull isNull -> Value.of(evaluate(isNull.operand(), row).isNull() != isNull.negated());
        };
    }

    public static boolean isTrue(Value value) {
        return value instanceof BooleanValue bool && bool.value();
    }

    private static boolean isFalse(Value value) {
        return value instanceof BooleanValue bool && !bool.value();
    }

    private static Value and(And and, Tuple row) {
        Value left = evaluate(and.left(), row);
        if (isFalse(left)) {
            return Value.FALSE;
        }
        Value right = evaluate(and.right(), row);
        if (isFalse(right)) {
            return Value.FALSE;
        }
        return left.isNull() || right.isNull() ? Value.NULL : Value.TRUE;
    }

    private static Value or(Or or, Tuple row) {
        Value left = evaluate(or.left(), row);
        if (isTrue(left)) {
            return Value.TRUE;
        }
        Value right = evaluate(or.right(), row);
        if (isTrue(right)) {
            return Value.TRUE;
        }
        return left.isNull() || right.isNull() ? Value.NULL : Value.FALSE;
    }

    private static Value not(Value value) {
        return value instanceof BooleanValue bool ? Value.of(!bool.value()) : Value.NULL;
    }

    private static Value negate(Value value) {
        return switch (value) {
            case IntValue integer -> {
                if (integer.value() == Integer.MIN_VALUE) {
                    throw new QueryException("-(" + integer.value() + ") is out of range for INT");
                }
                yield Value.of(-integer.value());
            }
            case DoubleValue real -> Value.of(-real.value());
            default -> Value.NULL;
        };
    }

    private static Value arithmetic(BinaryOperator operator, Value left, Value right) {
        if (left.isNull() || right.isNull()) {
            return Value.NULL;
        }
        if (left instanceof IntValue a && right instanceof IntValue b) {
            return Value.of(integerArithmetic(operator, a.value(), b.value()));
        }
        return Value.of(realArithmetic(operator, asDouble(left), asDouble(right)));
    }

    private static int integerArithmetic(BinaryOperator operator, int a, int b) {
        try {
            return switch (operator) {
                case ADD -> Math.addExact(a, b);
                case SUBTRACT -> Math.subtractExact(a, b);
                case MULTIPLY -> Math.multiplyExact(a, b);
                case DIVIDE -> {
                    if (b == 0) {
                        throw new QueryException("division by zero");
                    }
                    yield Math.divideExact(a, b);
                }
                default -> throw new IllegalArgumentException(operator + " is not arithmetic");
            };
        } catch (ArithmeticException e) {
            throw new QueryException(a + " " + operator.symbol() + " " + b + " is out of range for INT");
        }
    }

    private static double realArithmetic(BinaryOperator operator, double a, double b) {
        double result = switch (operator) {
            case ADD -> a + b;
            case SUBTRACT -> a - b;
            case MULTIPLY -> a * b;
            case DIVIDE -> {
                if (b == 0.0) {
                    throw new QueryException("division by zero");
                }
                yield a / b;
            }
            default -> throw new IllegalArgumentException(operator + " is not arithmetic");
        };
        if (Double.isInfinite(result)) {
            throw new QueryException(a + " " + operator.symbol() + " " + b + " is out of range for DOUBLE");
        }
        return result;
    }

    private static double asDouble(Value value) {
        return switch (value) {
            case IntValue integer -> integer.value();
            case DoubleValue real -> real.value();
            default -> throw new IllegalArgumentException(value + " is not a number");
        };
    }

    private static Value compare(BinaryOperator operator, Value left, Value right) {
        if (left.isNull() || right.isNull()) {
            return Value.NULL;
        }
        int order = order(left, right);
        return Value.of(switch (operator) {
            case EQUAL -> order == 0;
            case NOT_EQUAL -> order != 0;
            case LESS -> order < 0;
            case LESS_OR_EQUAL -> order <= 0;
            case GREATER -> order > 0;
            case GREATER_OR_EQUAL -> order >= 0;
            default -> throw new IllegalArgumentException(operator + " is not a comparison");
        });
    }

    static int order(Value left, Value right) {
        if (left instanceof IntValue a && right instanceof IntValue b) {
            return Integer.compare(a.value(), b.value());
        }
        if (left instanceof StringValue a && right instanceof StringValue b) {
            return a.value().compareTo(b.value());
        }
        if (left instanceof BooleanValue a && right instanceof BooleanValue b) {
            return Boolean.compare(a.value(), b.value());
        }
        return Double.compare(asDouble(left), asDouble(right));
    }
}
