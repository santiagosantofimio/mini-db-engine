package dev.santiagosantofimio.minidb.record;

import java.util.List;

public record Schema(List<Column> columns) {

    public Schema {
        columns = List.copyOf(columns);
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("a schema needs at least one column");
        }
    }

    public static Schema of(Column... columns) {
        return new Schema(List.of(columns));
    }

    public int size() {
        return columns.size();
    }

    public Column column(int index) {
        return columns.get(index);
    }
}
