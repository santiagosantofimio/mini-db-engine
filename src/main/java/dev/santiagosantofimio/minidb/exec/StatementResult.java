package dev.santiagosantofimio.minidb.exec;

import dev.santiagosantofimio.minidb.record.Tuple;
import java.util.List;

public sealed interface StatementResult {

    record Rows(List<String> columns, List<Tuple> rows) implements StatementResult {

        public Rows {
            columns = List.copyOf(columns);
            rows = List.copyOf(rows);
        }
    }

    record TableCreated(String table) implements StatementResult {
    }

    record TableDropped(String table) implements StatementResult {
    }

    record RowsInserted(String table, int count) implements StatementResult {
    }
}
