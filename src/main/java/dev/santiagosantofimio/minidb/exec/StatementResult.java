package dev.santiagosantofimio.minidb.exec;

import dev.santiagosantofimio.minidb.plan.ValueType;
import dev.santiagosantofimio.minidb.record.Tuple;
import java.util.List;

public sealed interface StatementResult {

    record Rows(List<String> columns, List<ValueType> types, List<Tuple> rows) implements StatementResult {

        public Rows {
            columns = List.copyOf(columns);
            types = List.copyOf(types);
            rows = List.copyOf(rows);
            if (columns.size() != types.size()) {
                throw new IllegalArgumentException("every column needs a type");
            }
        }
    }

    record TableCreated(String table) implements StatementResult {
    }

    record TableDropped(String table) implements StatementResult {
    }

    record IndexCreated(String index, String table, String column) implements StatementResult {
    }

    record RowsInserted(String table, int count) implements StatementResult {
    }
}
