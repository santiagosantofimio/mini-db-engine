package dev.santiagosantofimio.minidb.plan;

import dev.santiagosantofimio.minidb.CatalogException;
import dev.santiagosantofimio.minidb.QueryException;
import dev.santiagosantofimio.minidb.SourcePosition;
import dev.santiagosantofimio.minidb.plan.Expr.And;
import dev.santiagosantofimio.minidb.plan.Expr.Arithmetic;
import dev.santiagosantofimio.minidb.plan.Expr.ColumnRef;
import dev.santiagosantofimio.minidb.plan.Expr.Comparison;
import dev.santiagosantofimio.minidb.plan.Expr.Constant;
import dev.santiagosantofimio.minidb.plan.Expr.Or;
import dev.santiagosantofimio.minidb.sql.BinaryOperator;
import dev.santiagosantofimio.minidb.sql.Expression;
import dev.santiagosantofimio.minidb.sql.Expression.Between;
import dev.santiagosantofimio.minidb.sql.Expression.Binary;
import dev.santiagosantofimio.minidb.sql.Expression.ColumnReference;
import dev.santiagosantofimio.minidb.sql.Expression.IsNull;
import dev.santiagosantofimio.minidb.sql.Expression.Literal;
import dev.santiagosantofimio.minidb.sql.Expression.Negate;
import dev.santiagosantofimio.minidb.sql.Expression.Not;
import dev.santiagosantofimio.minidb.sql.SqlPrinter;
import java.util.List;

public final class ExpressionBinder {

    private final List<Attribute> scope;

    public ExpressionBinder(List<Attribute> scope) {
        this.scope = List.copyOf(scope);
    }

    public Expr bind(Expression expression) {
        return switch (expression) {
            case Literal literal -> new Constant(literal.value());
            case ColumnReference column -> resolve(column);
            case Negate negate -> negate(negate);
            case Not not -> new Expr.Not(requireBoolean(bind(not.operand()), "NOT", not.position()));
            case Binary binary -> binary(binary);
            case IsNull isNull -> new Expr.IsNull(bind(isNull.operand()), isNull.negated());
            case Between between -> between(between);
        };
    }

    private Expr resolve(ColumnReference column) {
        int found = -1;
        for (int i = 0; i < scope.size(); i++) {
            if (scope.get(i).matches(column.qualifier(), column.name())) {
                if (found >= 0) {
                    throw new CatalogException("column " + column.name() + " is ambiguous", column.position());
                }
                found = i;
            }
        }
        if (found < 0) {
            throw new CatalogException("unknown column " + SqlPrinter.print(column), column.position());
        }
        return new ColumnRef(found, scope.get(found));
    }

    private Expr negate(Negate negate) {
        Expr operand = bind(negate.operand());
        if (!operand.type().isNumericOrNull()) {
            throw new QueryException("cannot negate a " + operand.type() + " value", negate.position());
        }
        return new Expr.Negate(operand);
    }

    private Expr binary(Binary binary) {
        Expr left = bind(binary.left());
        Expr right = bind(binary.right());
        BinaryOperator operator = binary.operator();
        return switch (operator.kind()) {
            case LOGICAL -> {
                requireBoolean(left, operator.symbol(), binary.position());
                requireBoolean(right, operator.symbol(), binary.position());
                yield operator == BinaryOperator.AND ? new And(left, right) : new Or(left, right);
            }
            case COMPARISON -> comparison(operator, left, right, binary.position());
            case ARITHMETIC -> arithmetic(operator, left, right, binary.position());
        };
    }

    private static Expr comparison(BinaryOperator operator, Expr left, Expr right, SourcePosition position) {
        if (!left.type().isComparableWith(right.type())) {
            throw new QueryException(
                    "cannot compare " + left.type() + " with " + right.type() + " using " + operator.symbol(),
                    position);
        }
        return new Comparison(operator, left, right);
    }

    private static Expr arithmetic(BinaryOperator operator, Expr left, Expr right, SourcePosition position) {
        if (!left.type().isNumericOrNull() || !right.type().isNumericOrNull()) {
            throw new QueryException(
                    "operator " + operator.symbol() + " needs numbers, but got " + left.type() + " and "
                            + right.type(),
                    position);
        }
        return new Arithmetic(operator, left, right, arithmeticType(left.type(), right.type()));
    }

    private static ValueType arithmeticType(ValueType left, ValueType right) {
        if (left == ValueType.DOUBLE || right == ValueType.DOUBLE) {
            return ValueType.DOUBLE;
        }
        if (left == ValueType.INT || right == ValueType.INT) {
            return ValueType.INT;
        }
        return ValueType.NULL;
    }

    private Expr between(Between between) {
        Expr operand = bind(between.operand());
        Expr low = bind(between.low());
        Expr high = bind(between.high());
        Expr range = new And(
                comparison(BinaryOperator.GREATER_OR_EQUAL, operand, low, between.position()),
                comparison(BinaryOperator.LESS_OR_EQUAL, operand, high, between.position()));
        return between.negated() ? new Expr.Not(range) : range;
    }

    private static Expr requireBoolean(Expr operand, String operator, SourcePosition position) {
        if (!operand.type().isBooleanOrNull()) {
            throw new QueryException(operator + " needs a BOOLEAN operand, but got " + operand.type(), position);
        }
        return operand;
    }
}
