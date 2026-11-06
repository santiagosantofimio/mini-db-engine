package dev.santiagosantofimio.minidb.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.santiagosantofimio.minidb.CatalogException;
import dev.santiagosantofimio.minidb.record.Column;
import dev.santiagosantofimio.minidb.record.DataType;
import dev.santiagosantofimio.minidb.storage.PageHandle;
import dev.santiagosantofimio.minidb.storage.PageType;
import dev.santiagosantofimio.minidb.storage.Storage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogTest {

    private static final List<Column> STUDENT_COLUMNS = List.of(
            new Column("id", DataType.INT, false),
            new Column("name", DataType.varchar(40), true),
            new Column("gpa", DataType.DOUBLE, true),
            new Column("active", DataType.BOOLEAN, true));

    @TempDir
    Path directory;

    private Storage open() {
        return Storage.open(directory.resolve("catalog.db"), 16);
    }

    @Test
    void tablesSurviveReopening() {
        Table created;
        try (Storage storage = open()) {
            created = Catalog.open(storage).createTable("students", STUDENT_COLUMNS);
        }

        try (Storage storage = open()) {
            Catalog catalog = Catalog.open(storage);

            assertEquals(created, catalog.table("students").orElseThrow());
            assertEquals(STUDENT_COLUMNS, catalog.table("students").orElseThrow().schema().columns());
        }
    }

    @Test
    void catalogLivesInCatalogPagesReachableFromTheHeader() {
        try (Storage storage = open()) {
            Catalog.open(storage).createTable("students", STUDENT_COLUMNS);

            assertNotEquals(Storage.NO_PAGE, storage.catalogRoot());
            try (PageHandle root = storage.fetch(storage.catalogRoot())) {
                assertEquals(PageType.CATALOG, PageType.readFrom(root));
            }
        }
    }

    @Test
    void tablesAreListedByName() {
        try (Storage storage = open()) {
            Catalog catalog = Catalog.open(storage);
            catalog.createTable("students", STUDENT_COLUMNS);
            catalog.createTable("courses", STUDENT_COLUMNS);
            catalog.createTable("enrollments", STUDENT_COLUMNS);

            assertEquals(
                    List.of("courses", "enrollments", "students"),
                    catalog.tables().stream().map(Table::name).toList());
        }
    }

    @Test
    void rejectsDuplicateTablesAndColumns() {
        try (Storage storage = open()) {
            Catalog catalog = Catalog.open(storage);
            catalog.createTable("students", STUDENT_COLUMNS);

            assertThrows(CatalogException.class, () -> catalog.createTable("students", STUDENT_COLUMNS));
            assertThrows(
                    CatalogException.class,
                    () -> catalog.createTable("pairs", List.of(
                            new Column("x", DataType.INT, true), new Column("x", DataType.INT, true))));
            assertEquals(1, catalog.tables().size());
        }
    }

    @Test
    void droppingATableForgetsItAndFreesItsPages() {
        try (Storage storage = open()) {
            Catalog catalog = Catalog.open(storage);
            Table students = catalog.createTable("students", STUDENT_COLUMNS);

            catalog.dropTable("students");

            assertTrue(catalog.table("students").isEmpty());
            assertThrows(CatalogException.class, () -> catalog.dropTable("students"));
            try (PageHandle page = storage.fetch(students.firstPageId())) {
                assertEquals(PageType.FREE, PageType.readFrom(page));
            }
        }

        try (Storage storage = open()) {
            assertTrue(Catalog.open(storage).tables().isEmpty());
        }
    }

    @Test
    void rejectsDefinitionsThatDoNotFitInAPage() {
        List<Column> columns = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            columns.add(new Column("a_rather_long_column_name_" + i, DataType.INT, true));
        }

        try (Storage storage = open()) {
            Catalog catalog = Catalog.open(storage);
            int pages = storage.pageCount();

            assertThrows(CatalogException.class, () -> catalog.createTable("wide", columns));
            assertEquals(pages, storage.pageCount());
        }
    }

    @Test
    void manyTablesSpillOntoMoreCatalogPages() {
        try (Storage storage = open()) {
            Catalog catalog = Catalog.open(storage);
            for (int i = 0; i < 150; i++) {
                catalog.createTable("table_" + i, STUDENT_COLUMNS);
            }
        }

        try (Storage storage = open()) {
            assertEquals(150, Catalog.open(storage).tables().size());
            assertEquals(0, storage.pinnedPageCount());
        }
    }
}
