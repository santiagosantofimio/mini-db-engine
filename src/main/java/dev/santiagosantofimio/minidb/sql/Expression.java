package dev.santiagosantofimio.minidb.sql;

import dev.santiagosantofimio.minidb.SourcePosition;
import dev.santiagosantofimio.minidb.record.Value;
import java.util.Optional;

public sealed interface Expression {

    SourcePosition position();

    record Literal(Value value, SourcePosition position) implements Expression {
    }

    record ColumnReference(Optional<String> qualifier, String name, SourcePosition position) implements Expression {
    }

    record Negate(Expression operand, SourcePosition position) implements Expression {
    }

    record Not(Expression operand, SourcePosition position) implements Expression {
    }

    record Binary(BinaryOperator operator, Expression left, Expression right, SourcePosition position)
            implements Expression {
    }

    record IsNull(Expression operand, boolean negated, SourcePosition position) implements Expression {
    }

    record Between(Expression operand, Expression low, Expression high, boolean negated, SourcePosition position)
            implements Expression {
    }
}
