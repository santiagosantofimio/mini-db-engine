package dev.santiagosantofimio.minidb.catalog;

import dev.santiagosantofimio.minidb.record.Schema;
import java.util.Objects;

public record Table(String name, Schema schema, int firstPageId) implements Definition {

    public Table {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(schema, "schema");
    }
}
