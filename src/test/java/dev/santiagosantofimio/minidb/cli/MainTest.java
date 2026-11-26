package dev.santiagosantofimio.minidb.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {

    @TempDir
    Path directory;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String input, String... args) {
        return Main.run(
                args,
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                Terminal.PLAIN);
    }

    private static String text(ByteArrayOutputStream buffer) {
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private String database() {
        return directory.resolve("main.db").toString();
    }

    @Test
    void versionOptionPrintsVersionAndSucceeds() {
        assertEquals(Main.EXIT_OK, run("", "--version"));
        assertTrue(text(out).startsWith("minidb "));
        assertEquals("", text(err));
    }

    @Test
    void helpOptionPrintsUsageToStandardOutput() {
        assertEquals(Main.EXIT_OK, run("", "--help"));
        assertEquals(Main.USAGE + System.lineSeparator(), text(out));
    }

    @Test
    void missingDatabasePrintsUsage() {
        assertEquals(Main.EXIT_USAGE, run(""));
        assertEquals(Main.USAGE + System.lineSeparator(), text(err));
    }

    @Test
    void badOptionsAreUsageErrors() {
        assertEquals(Main.EXIT_USAGE, run("", database(), "--frobnicate"));
        assertEquals(Main.EXIT_USAGE, run("", database(), "--buffer-pages"));
        assertEquals(Main.EXIT_USAGE, run("", database(), "--buffer-pages", "lots"));
        assertEquals(Main.EXIT_USAGE, run("", database(), "--buffer-pages", "3"));
        assertEquals(Main.EXIT_USAGE, run("", database(), "other.db"));
        assertEquals(Main.EXIT_USAGE, run("", database(), "--btree-order", "2"));
        assertEquals(Main.EXIT_USAGE, run("", database(), "--btree-order", "409"));
        assertTrue(text(err).contains("--buffer-pages must be at least 16"));
        assertTrue(text(err).contains("--btree-order must be at most 408"));
    }

    @Test
    void runsSqlFromStandardInputAndCreatesTheFile() {
        int status = run(
                "CREATE TABLE t (x INT);\nINSERT INTO t VALUES (1), (2);\nSELECT * FROM t;\n",
                database(), "--buffer-pages", "32", "--btree-order", "4");

        assertEquals(Main.EXIT_OK, status);
        assertTrue(Files.exists(directory.resolve("main.db")));
        assertTrue(text(out).contains("2 rows in "));
        assertEquals("", text(err));
    }

    @Test
    void failsWhenAnyStatementFailsWithoutATerminal() {
        assertEquals(Main.EXIT_FAILURE, run("SELECT * FROM nowhere;\n", database()));
        assertTrue(text(err).contains("no such table: nowhere"));
    }

    @Test
    void reportsFilesThatAreNotDatabases() throws Exception {
        Path notADatabase = directory.resolve("notes.txt");
        Files.writeString(notADatabase, "just some notes");

        assertEquals(Main.EXIT_FAILURE, run("", notADatabase.toString()));
        assertTrue(text(err).startsWith("minidb: "));
    }
}
