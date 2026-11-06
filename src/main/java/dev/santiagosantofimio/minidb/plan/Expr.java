package dev.santiagosantofimio.minidb.plan;

import dev.santiagosantofimio.minidb.record.Value;
import dev.santiagosantofimio.minidb.sql.BinaryOperator;

public sealed interface Expr {

    ValueType type();

    record Constant(Value value) implements Expr {

        @Override
        public ValueType type() {
            return ValueType.of(value);
        }
    }

    record ColumnRef(int index, Attribute attribute) implements Expr {

        @Override
        public ValueType type() {
            return attribute.type();
        }
    }

    record Negate(Expr operand) implements Expr {

        @Override
        public ValueType type() {
            return operand.type();
        }
    }

    record Arithmetic(BinaryOperator operator, Expr left, Expr right, ValueType type) implements Expr {
    }

    record Comparison(BinaryOperator operator, Expr left, Expr right) implements Expr {

        @Override
        public ValueType type() {
            return ValueType.BOOLEAN;
        }
    }

    record And(Expr left, Expr right) implements Expr {

        @Override
        public ValueType type() {
            return ValueType.BOOLEAN;
        }
    }

    record Or(Expr left, Expr right) implements Expr {

        @Override
        public ValueType type() {
            return ValueType.BOOLEAN;
        }
    }

    record Not(Expr operand) implements Expr {

        @Override
        public ValueType type() {
            return ValueType.BOOLEAN;
        }
    }

    record IsNull(Expr operand, boolean negated) implements Expr {

        @Override
        public ValueType type() {
            return ValueType.BOOLEAN;
        }
    }
}
