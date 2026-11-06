package dev.santiagosantofimio.minidb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.santiagosantofimio.minidb.exec.StatementResult;
import dev.santiagosantofimio.minidb.exec.StatementResult.Rows;
import dev.santiagosantofimio.minidb.exec.StatementResult.RowsInserted;
import dev.santiagosantofimio.minidb.exec.StatementResult.TableCreated;
import dev.santiagosantofimio.minidb.exec.StatementResult.TableDropped;
import dev.santiagosantofimio.minidb.record.Tuple;
import dev.santiagosantofimio.minidb.record.Value;
import dev.santiagosantofimio.minidb.sql.Statement;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseTest {

    private static final String STUDENTS = """
            CREATE TABLE students (id INT NOT NULL, name VARCHAR(20) NOT NULL, age INT, gpa DOUBLE, active BOOLEAN);
            INSERT INTO students VALUES
              (1, 'Ana', 21, 3.8, TRUE),
              (2, 'Bruno', 19, 2.9, FALSE),
              (3, 'Camila', 23, 3.5, TRUE),
              (4, 'Diego', NULL, NULL, NULL);
            """;

    @TempDir
    Path directory;

    private Database database;

    @BeforeEach
    void openDatabase() {
        database = Database.open(directory.resolve("test.db"), Database.MIN_BUFFER_PAGES);
    }

    @AfterEach
    void closeDatabase() {
        database.close();
    }

    private Rows query(String sql) {
        List<StatementResult> results = database.execute(sql);
        assertEquals(1, results.size());
        return (Rows) results.get(0);
    }

    private static List<List<Value>> values(Rows rows) {
        return rows.rows().stream().map(Tuple::values).toList();
    }

    private MiniDbException failure(String sql) {
        return assertThrows(MiniDbException.class, () -> database.execute(sql));
    }

    @Test
    void createsInsertsAndSelects() {
        List<StatementResult> results = database.execute(STUDENTS);

        assertEquals(new TableCreated("students"), results.get(0));
        assertEquals(new RowsInserted("students", 4), results.get(1));
        Rows rows = query("SELECT name, age FROM students WHERE age > 20");
        assertEquals(List.of("name", "age"), rows.columns());
        assertEquals(
                List.of(List.of(Value.of("Ana"), Value.of(21)), List.of(Value.of("Camila"), Value.of(23))),
                values(rows));
    }

    @Test
    void selectStarReturnsEveryColumnInTableOrder() {
        database.execute(STUDENTS);

        Rows rows = query("SELECT * FROM students WHERE id = 4");

        assertEquals(List.of("id", "name", "age", "gpa", "active"), rows.columns());
        assertEquals(List.of(List.of(Value.of(4), Value.of("Diego"), Value.NULL, Value.NULL, Value.NULL)), values(rows));
    }

    @Test
    void whereKeepsOnlyRowsThatAreTrue() {
        database.execute(STUDENTS);

        assertEquals(4, query("SELECT id FROM students WHERE age > 0 OR age IS NULL").rows().size());
        assertEquals(3, query("SELECT id FROM students WHERE NOT age < 20 OR age IS NULL").rows().size());
        assertEquals(2, query("SELECT id FROM students WHERE NOT age < 20").rows().size());
        assertEquals(0, query("SELECT id FROM students WHERE age = NULL").rows().size());
        assertEquals(List.of(List.of(Value.of(4))), values(query("SELECT id FROM students WHERE gpa IS NULL")));
    }

    @Test
    void projectsExpressionsWithTheirTextOrAlias() {
        database.execute(STUDENTS);

        Rows rows = query("SELECT s.name, age + 1, gpa * 10 AS score FROM students s WHERE s.id = 1");

        assertEquals(List.of("name", "age + 1", "score"), rows.columns());
        assertEquals(List.of(List.of(Value.of("Ana"), Value.of(22), Value.of(38.0))), values(rows));
    }

    @Test
    void insertWithColumnListFillsTheRestWithNull() {
        database.execute("CREATE TABLE t (a INT, b VARCHAR(5), c DOUBLE NOT NULL)");

        database.execute("INSERT INTO t (c, a) VALUES (2, 1)");

        assertEquals(List.of(List.of(Value.of(1), Value.NULL, Value.of(2.0))), values(query("SELECT * FROM t")));
    }

    @Test
    void enforcesNotNullLengthAndTypes() {
        database.execute("CREATE TABLE t (id INT NOT NULL, name VARCHAR(3))");

        assertInstanceOf(ConstraintViolationException.class, failure("INSERT INTO t VALUES (NULL, 'a')"));
        assertInstanceOf(ConstraintViolationException.class, failure("INSERT INTO t (name) VALUES ('a')"));
        assertInstanceOf(ConstraintViolationException.class, failure("INSERT INTO t VALUES (1, 'abcd')"));
        assertInstanceOf(QueryException.class, failure("INSERT INTO t VALUES ('1', 'a')"));
        assertInstanceOf(QueryException.class, failure("INSERT INTO t VALUES (1.5, 'a')"));
        assertInstanceOf(QueryException.class, failure("INSERT INTO t VALUES (1)"));
        assertInstanceOf(QueryException.class, failure("INSERT INTO t (id, id) VALUES (1, 2)"));
        assertInstanceOf(CatalogException.class, failure("INSERT INTO t (nope) VALUES (1)"));
        database.execute("INSERT INTO t VALUES (1, 'ñú🦤')");
        assertEquals(1, query("SELECT id FROM t").rows().size());
    }

    @Test
    void aFailingRowInsertsNothing() {
        database.execute("CREATE TABLE t (id INT NOT NULL)");

        failure("INSERT INTO t VALUES (1), (2), (NULL)");

        assertEquals(0, query("SELECT * FROM t").rows().size());
    }

    @Test
    void errorsPointAtTheOffendingToken() {
        database.execute(STUDENTS);

        assertEquals(new SourcePosition(1, 8), failure("SELECT nme FROM students").position().orElseThrow());
        assertEquals(new SourcePosition(1, 15), failure("SELECT * FROM studnts").position().orElseThrow());
        assertEquals(new SourcePosition(2, 21), failure("SELECT *\nFROM students WHERE age").position().orElseThrow());
        assertEquals(new SourcePosition(1, 14), failure("CREATE TABLE students (x INT)").position().orElseThrow());
        assertEquals(new SourcePosition(1, 38), failure("INSERT INTO students (id) VALUES (5, 'x')").position().orElseThrow());
    }

    @Test
    void whereMustBeBoolean() {
        database.execute(STUDENTS);

        QueryException error = (QueryException) failure("SELECT * FROM students WHERE age + 1");

        assertTrue(error.getMessage().startsWith("WHERE needs a BOOLEAN condition"));
    }

    @Test
    void dataAndSchemaSurviveReopening() {
        database.execute(STUDENTS);
        database.close();

        database = Database.open(directory.resolve("test.db"), Database.MIN_BUFFER_PAGES);

        assertEquals(4, query("SELECT * FROM students").rows().size());
        assertEquals(List.of("students"), database.tables().stream().map(t -> t.name()).toList());
    }

    @Test
    void dropTableRemovesItsData() {
        database.execute(STUDENTS);

        assertEquals(List.of(new TableDropped("students")), database.execute("DROP TABLE students"));

        assertInstanceOf(CatalogException.class, failure("SELECT * FROM students"));
        assertInstanceOf(CatalogException.class, failure("DROP TABLE students"));
        database.execute(STUDENTS);
        assertEquals(4, query("SELECT * FROM students").rows().size());
    }

    @Test
    void manyRowsWithASmallBufferPool() {
        database.execute("CREATE TABLE numbers (n INT NOT NULL, label VARCHAR(40))");
        StringBuilder insert = new StringBuilder("INSERT INTO numbers VALUES ");
        for (int i = 0; i < 3_000; i++) {
            insert.append(i == 0 ? "" : ", ").append("(").append(i).append(", 'number ").append(i).append("')");
        }
        database.execute(insert.toString());

        assertEquals(1_000, query("SELECT n FROM numbers WHERE n BETWEEN 1000 AND 1999").rows().size());
        assertTrue(database.stats().evictions() > 0);
    }

    @Test
    void noPageStaysPinnedAfterAnyStatement() {
        List<String> script = List.of(
                "CREATE TABLE t (id INT NOT NULL, name VARCHAR(10))",
                "INSERT INTO t VALUES (1, 'a'), (2, 'b')",
                "INSERT INTO t VALUES (NULL, 'c')",
                "SELECT * FROM t WHERE id = 1",
                "SELECT id / 0 FROM t",
                "SELECT nope FROM t",
                "CREATE TABLE t (x INT)",
                "INSERT INTO t VALUES (3, 'this is far too long')",
                "DROP TABLE t",
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

    @Test
    void rejectsATinyBufferPool() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Database.open(directory.resolve("other.db"), Database.MIN_BUFFER_PAGES - 1));
    }
}
