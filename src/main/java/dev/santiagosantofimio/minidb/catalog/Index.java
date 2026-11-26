package dev.santiagosantofimio.minidb.catalog;

import java.util.Objects;

public record Index(String name, String table, int column, int rootPageId, boolean primary) implements Definition {

    public Index {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(table, "table");
        if (column < 0) {
            throw new IllegalArgumentException("column position must not be negative, got " + column);
        }
    }
}
