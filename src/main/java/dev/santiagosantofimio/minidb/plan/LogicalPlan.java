package dev.santiagosantofimio.minidb.plan;

import dev.santiagosantofimio.minidb.catalog.Table;
import dev.santiagosantofimio.minidb.plan.Expr.ColumnRef;
import dev.santiagosantofimio.minidb.record.Column;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public sealed interface LogicalPlan {

    List<Attribute> attributes();

    record Scan(Table table, String alias) implements LogicalPlan {

        @Override
        public List<Attribute> attributes() {
            return table.schema().columns().stream()
                    .map(column -> attribute(alias, column))
                    .toList();
        }

        private static Attribute attribute(String alias, Column column) {
            return new Attribute(Optional.of(alias), column.name(), ValueType.of(column.type()));
        }
    }

    record Selection(LogicalPlan input, Expr predicate) implements LogicalPlan {

        @Override
        public List<Attribute> attributes() {
            return input.attributes();
        }
    }

    record Projection(LogicalPlan input, List<Expr> expressions, List<String> names) implements LogicalPlan {

        public Projection {
            expressions = List.copyOf(expressions);
            names = List.copyOf(names);
            if (expressions.size() != names.size()) {
                throw new IllegalArgumentException("every projected expression needs a name");
            }
        }

        @Override
        public List<Attribute> attributes() {
            List<Attribute> attributes = new ArrayList<>(expressions.size());
            for (int i = 0; i < expressions.size(); i++) {
                Expr expression = expressions.get(i);
                Optional<String> qualifier = expression instanceof ColumnRef column
                        ? column.attribute().qualifier()
                        : Optional.empty();
                attributes.add(new Attribute(qualifier, names.get(i), expression.type()));
            }
            return attributes;
        }
    }
}
