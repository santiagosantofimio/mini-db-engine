package dev.santiagosantofimio.minidb.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.santiagosantofimio.minidb.CatalogException;
import dev.santiagosantofimio.minidb.ConstraintViolationException;
import dev.santiagosantofimio.minidb.record.Column;
import dev.santiagosantofimio.minidb.record.DataType;
import dev.santiagosantofimio.minidb.record.Tuple;
import dev.santiagosantofimio.minidb.record.TupleSerializer;
import dev.santiagosantofimio.minidb.record.Value;
import dev.santiagosantofimio.minidb.storage.PageHandle;
import dev.santiagosantofimio.minidb.storage.PageType;
import dev.santiagosantofimio.minidb.storage.Storage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogTest {

    private static final int ORDER = 4;

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
            created = Catalog.open(storage, ORDER).createTable("students", STUDENT_COLUMNS);
        }

        try (Storage storage = open()) {
            Catalog catalog = Catalog.open(storage, ORDER);

            assertEquals(created, catalog.table("students").orElseThrow());
            assertEquals(STUDENT_COLUMNS, catalog.table("students").orElseThrow().schema().columns());
        }
    }

    @Test
    void catalogLivesInCatalogPagesReachableFromTheHeader() {
        try (Storage storage = open()) {
            Catalog.open(storage, ORDER).createTable("students", STUDENT_COLUMNS);

            assertNotEquals(Storage.NO_PAGE, storage.catalogRoot());
            try (PageHandle root = storage.fetch(storage.catalogRoot())) {
                assertEquals(PageType.CATALOG, PageType.readFrom(root));
            }
        }
    }

    @Test
    void tablesAreListedByName() {
        try (Storage storage = open()) {
            Catalog catalog = Catalog.open(storage, ORDER);
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
            Catalog catalog = Catalog.open(storage, ORDER);
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
            Catalog catalog = Catalog.open(storage, ORDER);
            Table students = catalog.createTable("students", STUDENT_COLUMNS);

            catalog.dropTable("students");

            assertTrue(catalog.table("students").isEmpty());
            assertThrows(CatalogException.class, () -> catalog.dropTable("students"));
            try (PageHandle page = storage.fetch(students.firstPageId())) {
                assertEquals(PageType.FREE, PageType.readFrom(page));
            }
        }

        try (Storage storage = open()) {
            assertTrue(Catalog.open(storage, ORDER).tables().isEmpty());
        }
    }

    @Test
    void rejectsDefinitionsThatDoNotFitInAPage() {
        List<Column> columns = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            columns.add(new Column("a_rather_long_column_name_" + i, DataType.INT, true));
        }

        try (Storage storage = open()) {
            Catalog catalog = Catalog.open(storage, ORDER);
            int pages = storage.pageCount();

            assertThrows(CatalogException.class, () -> catalog.createTable("wide", columns));
            assertEquals(pages, storage.pageCount());
        }
    }

    @Test
    void manyTablesSpillOntoMoreCatalogPages() {
        try (Storage storage = open()) {
            Catalog catalog = Catalog.open(storage, ORDER);
            for (int i = 0; i < 150; i++) {
                catalog.createTable("table_" + i, STUDENT_COLUMNS);
            }
        }

        try (Storage storage = open()) {
            assertEquals(150, Catalog.open(storage, ORDER).tables().size());
            assertEquals(0, storage.pinnedPageCount());
        }
    }

    @Test
    void indexesAreBuiltFromExistingRowsAndSurviveReopening() {
        try (Storage storage = open()) {
            Catalog catalog = Catalog.open(storage, ORDER);
            Table students = catalog.createTable("students", STUDENT_COLUMNS);
            insertIds(catalog, students, 50);

            Index index = catalog.createIndex("students_by_id", "students", "id", false);

            assertEquals(1, catalog.treeOf(index).lookup(7).size());
        }

        try (Storage storage = open()) {
            Catalog catalog = Catalog.open(storage, ORDER);
            Index index = catalog.index("students_by_id").orElseThrow();

            assertEquals(List.of(index), catalog.indexesOf("students"));
            assertEquals(0, index.column());
            assertEquals(50, catalog.treeOf(index).checkIntegrity().entries());
        }
    }

    @Test
    void onlyIntColumnsCanBeIndexed() {
        try (Storage storage = open()) {
            Catalog catalog = Catalog.open(storage, ORDER);
            catalog.createTable("students", STUDENT_COLUMNS);

            assertThrows(CatalogException.class, () -> catalog.createIndex("by_name", "students", "name", false));
            assertThrows(CatalogException.class, () -> catalog.createIndex("by_nope", "students", "nope", false));
            assertThrows(CatalogException.class, () -> catalog.createIndex("by_id", "ghosts", "id", false));
        }
    }

    @Test
    void uniqueIndexOverDuplicatesFailsWithoutLeakingPages() {
        try (Storage storage = open()) {
            Catalog catalog = Catalog.open(storage, ORDER);
            Table students = catalog.createTable("students", STUDENT_COLUMNS);
            insertIds(catalog, students, 20);
            insertIds(catalog, students, 1);
            int pages = storage.pageCount();

            assertThrows(
                    ConstraintViolationException.class,
                    () -> catalog.createIndex("students_pkey", "students", "id", true));

            assertTrue(catalog.indexes().isEmpty());
            Index retry = catalog.createIndex("students_by_id", "students", "id", false);
            assertEquals(21, catalog.treeOf(retry).checkIntegrity().entries());
            assertTrue(storage.pageCount() <= pages + catalog.treeOf(retry).checkIntegrity().pages());
        }
    }

    @Test
    void aTableHasAtMostOnePrimaryKeyAndIndexNamesAreUnique() {
        try (Storage storage = open()) {
            Catalog catalog = Catalog.open(storage, ORDER);
            catalog.createTable("students", STUDENT_COLUMNS);
            catalog.createIndex("students_pkey", "students", "id", true);

            assertThrows(CatalogException.class, () -> catalog.createIndex("other_pkey", "students", "id", true));
            assertThrows(CatalogException.class, () -> catalog.createIndex("students_pkey", "students", "id", false));
        }
    }

    @Test
    void droppingATableDropsItsIndexes() {
        try (Storage storage = open()) {
            Catalog catalog = Catalog.open(storage, ORDER);
            Table students = catalog.createTable("students", STUDENT_COLUMNS);
            insertIds(catalog, students, 30);
            Index index = catalog.createIndex("students_pkey", "students", "id", true);

            catalog.dropTable("students");

            assertTrue(catalog.indexes().isEmpty());
            try (PageHandle root = storage.fetch(index.rootPageId())) {
                assertEquals(PageType.FREE, PageType.readFrom(root));
            }
        }

        try (Storage storage = open()) {
            assertTrue(Catalog.open(storage, ORDER).indexes().isEmpty());
        }
    }

    private static void insertIds(Catalog catalog, Table table, int count) {
        for (int id = 0; id < count; id++) {
            Tuple row = Tuple.of(Value.of(id), Value.of("student " + id), Value.NULL, Value.TRUE);
            catalog.heapOf(table).insert(TupleSerializer.serialize(table.schema(), row));
        }
    }
}
