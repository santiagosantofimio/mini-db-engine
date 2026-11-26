package dev.santiagosantofimio.minidb.sql;

import dev.santiagosantofimio.minidb.SourcePosition;
import dev.santiagosantofimio.minidb.record.DataType;
import java.util.List;
import java.util.Optional;

public sealed interface Statement {

    SourcePosition position();

    record Identifier(String name, SourcePosition position) {
    }

    record ColumnDefinition(Identifier name, DataType type, boolean nullable, boolean primaryKey) {
    }

    record CreateTable(Identifier table, List<ColumnDefinition> columns, SourcePosition position)
            implements Statement {

        public CreateTable {
            columns = List.copyOf(columns);
        }
    }

    record DropTable(Identifier table, SourcePosition position) implements Statement {
    }

    record CreateIndex(Identifier name, Identifier table, Identifier column, SourcePosition position)
            implements Statement {
    }

    record Insert(Identifier table, Optional<List<Identifier>> columns, List<List<Expression>> rows,
            SourcePosition position) implements Statement {

        public Insert {
            columns = columns.map(List::copyOf);
            rows = rows.stream().map(List::copyOf).toList();
        }
    }

    record Select(List<SelectItem> items, TableReference from, Optional<Expression> where, SourcePosition position)
            implements Statement {

        public Select {
            items = List.copyOf(items);
        }
    }

    record TableReference(Identifier table, Optional<Identifier> alias) {

        public String effectiveName() {
            return alias.orElse(table).name();
        }
    }

    sealed interface SelectItem {
    }

    record AllColumns(SourcePosition position) implements SelectItem {
    }

    record SelectExpression(Expression expression, Optional<Identifier> alias) implements SelectItem {
    }
}
