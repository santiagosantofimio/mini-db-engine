package dev.santiagosantofimio.minidb.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MainTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        return Main.run(args, print(out), print(err));
    }

    private static PrintStream print(ByteArrayOutputStream buffer) {
        return new PrintStream(buffer, true, StandardCharsets.UTF_8);
    }

    private static String text(ByteArrayOutputStream buffer) {
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @Test
    void versionOptionPrintsVersionAndSucceeds() {
        assertEquals(Main.EXIT_OK, run("--version"));
        assertTrue(text(out).startsWith("minidb "));
        assertEquals("", text(err));
    }

    @Test
    void helpOptionPrintsUsageToStandardOutput() {
        assertEquals(Main.EXIT_OK, run("--help"));
        assertEquals(Main.USAGE + System.lineSeparator(), text(out));
        assertEquals("", text(err));
    }

    @Test
    void missingArgumentsPrintUsageToStandardError() {
        assertEquals(Main.EXIT_USAGE, run());
        assertEquals("", text(out));
        assertEquals(Main.USAGE + System.lineSeparator(), text(err));
    }

    @Test
    void unknownOptionIsAUsageError() {
        assertEquals(Main.EXIT_USAGE, run("--frobnicate"));
        assertEquals(Main.USAGE + System.lineSeparator(), text(err));
    }
}
