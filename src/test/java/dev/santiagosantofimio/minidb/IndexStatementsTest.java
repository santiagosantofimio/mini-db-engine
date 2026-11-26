package dev.santiagosantofimio.minidb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.santiagosantofimio.minidb.catalog.Index;
import dev.santiagosantofimio.minidb.exec.StatementResult.IndexCreated;
import dev.santiagosantofimio.minidb.exec.StatementResult.Rows;
import dev.santiagosantofimio.minidb.index.BPlusTree;
import dev.santiagosantofimio.minidb.index.IndexEntry;
import dev.santiagosantofimio.minidb.sql.Statement;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexStatementsTest {

    @TempDir
    Path directory;

    private Database database;

    @BeforeEach
    void openDatabase() {
        database = Database.open(directory.resolve("index.db"), Database.MIN_BUFFER_PAGES, 3);
    }

    @AfterEach
    void closeDatabase() {
        database.close();
    }

    private MiniDbException failure(String sql) {
        return assertThrows(MiniDbException.class, () -> database.execute(sql));
    }

    private BPlusTree tree(String index) {
        return database.tree(database.index(index).orElseThrow());
    }

    private static List<Integer> keys(BPlusTree tree) {
        List<Integer> keys = new ArrayList<>();
        tree.range(Integer.MIN_VALUE, Integer.MAX_VALUE).forEachRemaining((IndexEntry entry) -> keys.add(entry.key()));
        return keys;
    }

    private int count(String table) {
        return ((Rows) database.execute("SELECT * FROM " + table).get(0)).rows().size();
    }

    @Test
    void primaryKeyCreatesAUniqueIndexAndImpliesNotNull() {
        database.execute("CREATE TABLE students (id INT PRIMARY KEY, name VARCHAR(20))");

        Index index = database.index("students_pkey").orElseThrow();
        assertTrue(index.primary());
        assertTrue(!database.table("students").orElseThrow().schema().column(0).nullable());
        assertInstanceOf(ConstraintViolationException.class, failure("INSERT INTO students VALUES (NULL, 'Ana')"));
    }

    @Test
    void insertsKeepEveryIndexUpToDate() {
        database.execute("CREATE TABLE students (id INT PRIMARY KEY, age INT, name VARCHAR(20))");
        database.execute("CREATE INDEX students_age ON students (age)");

        database.execute("INSERT INTO students VALUES (3, 21, 'Ana'), (1, 19, 'Bruno'), (2, 21, 'Camila'), (4, NULL, 'Diego')");

        assertEquals(List.of(1, 2, 3, 4), keys(tree("students_pkey")));
        assertEquals(List.of(19, 21, 21), keys(tree("students_age")));
        assertEquals(2, tree("students_age").lookup(21).size());
        tree("students_pkey").checkIntegrity();
    }

    @Test
    void duplicatePrimaryKeysAreRejectedBeforeAnythingIsWritten() {
        database.execute("CREATE TABLE students (id INT PRIMARY KEY, name VARCHAR(20))");
        database.execute("INSERT INTO students VALUES (1, 'Ana')");

        MiniDbException existing = failure("INSERT INTO students VALUES (2, 'Bruno'), (1, 'Copy')");
        MiniDbException sameStatement = failure("INSERT INTO students (name, id) VALUES ('X', 5), ('Y', 5)");

        assertInstanceOf(ConstraintViolationException.class, existing);
        assertEquals("duplicate key 1 in primary key students_pkey", existing.getMessage());
        assertEquals(new SourcePosition(1, 44), existing.position().orElseThrow());
        assertEquals(new SourcePosition(1, 56), sameStatement.position().orElseThrow());
        assertEquals(1, count("students"));
        assertEquals(List.of(1), keys(tree("students_pkey")));
    }

    @Test
    void createIndexIndexesExistingRows() {
        database.execute("CREATE TABLE numbers (n INT NOT NULL, label VARCHAR(10))");
        StringBuilder insert = new StringBuilder("INSERT INTO numbers VALUES ");
        for (int i = 0; i < 300; i++) {
            insert.append(i == 0 ? "" : ", ").append("(").append(299 - i).append(", 'n')");
        }
        database.execute(insert.toString());

        assertEquals(
                List.of(new IndexCreated("numbers_n", "numbers", "n")),
                database.execute("CREATE INDEX numbers_n ON numbers (n)"));

        assertEquals(300, tree("numbers_n").checkIntegrity().entries());
        assertTrue(tree("numbers_n").checkIntegrity().height() > 3);
        assertEquals(1, tree("numbers_n").lookup(150).size());
    }

    @Test
    void reportsInvalidIndexDefinitionsWithPositions() {
        database.execute("CREATE TABLE students (id INT PRIMARY KEY, name VARCHAR(20))");

        assertEquals(new SourcePosition(1, 35), failure("CREATE INDEX by_name ON students (name)").position().orElseThrow());
        assertEquals(new SourcePosition(1, 35), failure("CREATE INDEX by_nope ON students (nope)").position().orElseThrow());
        assertEquals(new SourcePosition(1, 23), failure("CREATE INDEX by_id ON ghosts (id)").position().orElseThrow());
        assertEquals(new SourcePosition(1, 14), failure("CREATE INDEX students_pkey ON students (id)").position().orElseThrow());
        assertEquals(new SourcePosition(1, 17), failure("CREATE TABLE t (name VARCHAR(5) PRIMARY KEY)").position().orElseThrow());
        assertEquals(new SourcePosition(1, 36), failure("CREATE TABLE t (a INT PRIMARY KEY, b INT PRIMARY KEY)").position().orElseThrow());
        assertTrue(database.table("t").isEmpty());
    }

    @Test
    void indexesSurviveReopeningAndDropWithTheirTable() {
        database.execute("CREATE TABLE students (id INT PRIMARY KEY, age INT)");
        database.execute("CREATE INDEX students_age ON students (age)");
        database.execute("INSERT INTO students VALUES (1, 20), (2, 30)");
        database.close();

        database = Database.open(directory.resolve("index.db"), Database.MIN_BUFFER_PAGES, 3);
        assertEquals(List.of("students_age", "students_pkey"), database.indexes().stream().map(Index::name).toList());
        assertEquals(List.of(20, 30), keys(tree("students_age")));

        database.execute("DROP TABLE students");
        assertTrue(database.indexes().isEmpty());
        database.execute("CREATE TABLE students (id INT PRIMARY KEY)");
        database.execute("INSERT INTO students VALUES (1)");
        assertEquals(List.of(1), keys(tree("students_pkey")));
    }

    @Test
    void noPageStaysPinnedAfterIndexStatements() {
        List<String> script = List.of(
                "CREATE TABLE t (id INT PRIMARY KEY, n INT)",
                "CREATE INDEX t_n ON t (n)",
                "INSERT INTO t VALUES (1, 1), (2, 1), (3, NULL)",
                "INSERT INTO t VALUES (3, 3)",
                "CREATE INDEX t_n ON t (n)",
                "CREATE INDEX t_id ON t (nope)",
                "DROP TABLE t");

        for (String sql : script) {
            try {
                for (Statement statement : database.parse(sql)) {
                    database.execute(statement);
                }
            } catch (MiniDbException expected) {
                assertTrue(expected.getMessage() != null);
            }
            assertEquals(0, database.pinnedPageCount(), () -> "pages left pinned after: " + sql);
        }
    }
}
