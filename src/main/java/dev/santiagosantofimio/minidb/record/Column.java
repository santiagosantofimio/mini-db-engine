package dev.santiagosantofimio.minidb.record;

import java.util.Objects;

public record Column(String name, DataType type, boolean nullable) {

    public Column {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        if (name.isBlank()) {
            throw new IllegalArgumentException("a column name cannot be blank");
        }
    }
}
