package dev.santiagosantofimio.minidb.catalog;

public sealed interface Definition permits Table, Index {

    String name();
}
