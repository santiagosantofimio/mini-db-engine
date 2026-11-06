package dev.santiagosantofimio.minidb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MiniDbExceptionTest {

    @Test
    void syntaxExceptionKeepsMessageAndPosition() {
        var error = new SyntaxException("unexpected token ','", 3, 17);

        assertEquals("unexpected token ','", error.getMessage());
        assertEquals(3, error.line());
        assertEquals(17, error.column());
    }

    @ParameterizedTest
    @CsvSource({"0, 1", "1, 0", "-4, 2"})
    void syntaxExceptionRejectsPositionsThatAreNotOneBased(int line, int column) {
        assertThrows(IllegalArgumentException.class, () -> new SyntaxException("bad", line, column));
    }

    @Test
    void storageExceptionKeepsItsCause() {
        var cause = new IOException("disk full");

        var error = new StorageException("cannot write page 7", cause);

        assertSame(cause, error.getCause());
    }

    @Test
    void everyEngineErrorIsCoveredByOneExhaustiveSwitch() {
        List<MiniDbException> errors = List.of(
                new SyntaxException("unexpected end of input", 1, 1),
                new CatalogException("no such table: students"),
                new ConstraintViolationException("duplicate primary key: 42"),
                new StorageException("not a minidb file"));

        assertEquals(
                List.of("syntax", "catalog", "constraint", "storage"),
                errors.stream().map(MiniDbExceptionTest::category).toList());
    }

    private static String category(MiniDbException error) {
        return switch (error) {
            case SyntaxException syntax -> "syntax";
            case CatalogException catalog -> "catalog";
            case ConstraintViolationException constraint -> "constraint";
            case StorageException storage -> "storage";
        };
    }
}
