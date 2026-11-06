package dev.santiagosantofimio.minidb.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.santiagosantofimio.minidb.Database;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReplTest {

    private static final String STUDENTS = """
            CREATE TABLE students (id INT NOT NULL, name VARCHAR(20), gpa DOUBLE);
            INSERT INTO students VALUES (1, 'Ana', 3.8), (2, 'Bruno', NULL), (10, 'Camila', 3.5);
            """;

    @TempDir
    Path directory;

    private Database database;
    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;

    @BeforeEach
    void openDatabase() {
        database = Database.open(directory.resolve("repl.db"), Database.MIN_BUFFER_PAGES);
    }

    @AfterEach
    void closeDatabase() {
        database.close();
    }

    private int run(String input) {
        return run(input, Terminal.PLAIN);
    }

    private int run(String input, Terminal terminal) {
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        Repl repl = new Repl(
                database,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                terminal);
        return repl.run(new BufferedReader(new StringReader(input)), "repl.db", "test");
    }

    private List<String> output() {
        return out.toString(StandardCharsets.UTF_8).lines().toList();
    }

    private List<String> errors() {
        return err.toString(StandardCharsets.UTF_8).lines().toList();
    }

    private static List<String> withoutTiming(List<String> lines) {
        return lines.stream().map(line -> line.replaceAll(" in [0-9.]+ m?s$", " in <time>")).toList();
    }

    @Test
    void rendersResultsAsABorderedTable() {
        run(STUDENTS + "SELECT id, name, gpa FROM students;\n");

        assertEquals(List.of(
                "Created table students in <time>",
                "Inserted 3 rows into students in <time>",
                "┌────┬────────┬──────┐",
                "│ id │ name   │  gpa │",
                "├────┼────────┼──────┤",
                "│  1 │ Ana    │  3.8 │",
                "│  2 │ Bruno  │ NULL │",
                "│ 10 │ Camila │  3.5 │",
                "└────┴────────┴──────┘",
                "3 rows in <time>"), withoutTiming(output()));
    }

    @Test
    void fallsBackToAsciiBordersWithoutUnicode() {
        run(STUDENTS + "SELECT id FROM students WHERE id = 1;\n", new Terminal(false, false, false));

        assertEquals(List.of("+----+", "| id |", "+----+", "|  1 |", "+----+", "1 row in <time>"),
                withoutTiming(output()).subList(2, 8));
    }

    @Test
    void emptyResultsStillShowTheHeader() {
        run(STUDENTS + "SELECT name FROM students WHERE gpa > 4;\n");

        assertEquals(List.of("┌──────┐", "│ name │", "├──────┤", "└──────┘", "0 rows in <time>"),
                withoutTiming(output()).subList(2, 7));
    }

    @Test
    void numericColumnsAlignRightEvenWhenEveryValueIsNull() {
        run(STUDENTS + "SELECT gpa, name FROM students WHERE gpa IS NULL;\n");

        assertTrue(output().contains("│ NULL │ Bruno │"));
        assertTrue(output().contains("│  gpa │ name  │"));
    }

    @Test
    void statementsCanSpanSeveralLines() {
        run(STUDENTS + "SELECT name\nFROM students\nWHERE id = 10;\n");

        assertTrue(output().contains("│ Camila │"));
    }

    @Test
    void errorsShowLineColumnAndACaret() {
        int status = run(STUDENTS + "SELECT name\nFROM students\nWHERE nme = 'Ana';\n");

        assertEquals(1, status);
        assertEquals(List.of(
                "Error at line 3, column 7: unknown column nme",
                "  WHERE nme = 'Ana';",
                "        ^"), errors());
    }

    @Test
    void syntaxErrorsAreLabelledAndKeepTabsAligned() {
        run("SELECT *\tFORM students;\n");

        assertEquals(List.of(
                "Syntax error at line 1, column 10: expected FROM but found 'form'",
                "  SELECT *\tFORM students;",
                "  " + " ".repeat(8) + "\t^"), errors());
    }

    @Test
    void missingTokensAtTheEndPointPastTheLastToken() {
        run("SELECT * FROM\n");

        assertEquals("Syntax error at line 1, column 14: expected a table name but found the end of the input",
                errors().get(0));
        assertEquals("  SELECT * FROM", errors().get(1));
        assertEquals("  " + " ".repeat(13) + "^", errors().get(2));
    }

    @Test
    void errorsWithoutAPositionAreOneLine() {
        run(STUDENTS + "SELECT id / 0 FROM students;\n");

        assertEquals(List.of("Error: division by zero"), errors());
    }

    @Test
    void laterStatementsOnTheSameLineRunAfterEarlierOnes() {
        run(STUDENTS + "SELECT id FROM students WHERE id = 1; SELECT nope FROM students; SELECT 1 FROM x;\n");

        assertTrue(output().contains("│  1 │"));
        assertEquals("Error at line 1, column 46: unknown column nope", errors().get(0));
        assertEquals(3, errors().size());
    }

    @Test
    void metaCommandsDescribeTheDatabase() {
        run(STUDENTS + ".tables\n.schema students\n.stats\n.help\n");

        List<String> lines = output();
        assertTrue(lines.contains("students"));
        assertTrue(lines.containsAll(List.of(
                "CREATE TABLE students (",
                "  id INT NOT NULL,",
                "  name VARCHAR(20),",
                "  gpa DOUBLE",
                ");")));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("Buffer pool 16 pages")));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith(".read <file>")));
    }

    @Test
    void unknownMetaCommandsAndTablesAreReported() {
        run(".frobnicate\n.schema ghosts\n");

        assertEquals(List.of(
                "Error: unknown command .frobnicate; type .help to see the commands",
                "Error: no such table: ghosts"), errors());
    }

    @Test
    void readRunsAFileAndStopsAtTheFirstError() throws Exception {
        Path script = directory.resolve("load.sql");
        Files.writeString(script, STUDENTS + "\nINSERT INTO students VALUES (4, 'Diego', 'high');\n"
                + "INSERT INTO students VALUES (5, 'Elena', 3.1);\n");

        run(".read " + script + "\nSELECT name FROM students WHERE id > 3 AND id < 10;\n");

        assertEquals("Error in load.sql at line 4, column 42: column gpa is DOUBLE, but this value is VARCHAR",
                errors().get(0));
        assertTrue(errors().get(3).startsWith("Stopped reading "));
        assertEquals(List.of("┌──────┐", "│ name │", "├──────┤", "└──────┘", "0 rows in <time>"),
                withoutTiming(output()).subList(2, 7));
    }

    @Test
    void readReportsMissingFiles() {
        run(".read nowhere.sql\n");

        assertEquals(List.of("Error: cannot read nowhere.sql: no such file"), errors());
    }

    @Test
    void exitStopsReadingInput() {
        run(".exit\nCREATE TABLE never (x INT);\n");

        assertTrue(database.tables().isEmpty());
    }

    @Test
    void interactiveSessionsShowPromptsAndAGreeting() {
        run("SELECT 1\n", new Terminal(true, false, true));
        String text = out.toString(StandardCharsets.UTF_8);

        assertTrue(text.startsWith("minidb test · repl.db"));
        assertTrue(text.contains(Repl.PROMPT));
        assertTrue(text.contains(Repl.CONTINUATION));
    }

    @Test
    void colorsAreOffWithoutATerminalAndOnWithOne() {
        run(STUDENTS + "SELECT nme FROM students;\n");
        assertFalse(err.toString(StandardCharsets.UTF_8).contains("\u001B["));

        run("SELECT nme FROM students;\n", new Terminal(true, true, true));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("\u001B[1;31m"));
    }

    @Test
    void controlCharactersInValuesCannotBreakTheTable() {
        run("CREATE TABLE notes (body VARCHAR(20));\n"
                + "INSERT INTO notes VALUES ('two\nlines');\nSELECT body FROM notes;\n");

        assertTrue(output().contains("│ two\\nlines │"));
    }
}
