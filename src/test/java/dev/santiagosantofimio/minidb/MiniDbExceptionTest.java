package dev.santiagosantofimio.minidb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MiniDbExceptionTest {

    @Test
    void syntaxExceptionKeepsMessageAndPosition() {
        var error = new SyntaxException("unexpected token ','", new SourcePosition(3, 17));

        assertEquals("unexpected token ','", error.getMessage());
        assertEquals(Optional.of(new SourcePosition(3, 17)), error.position());
    }

    @Test
    void syntaxExceptionRequiresAPosition() {
        assertThrows(NullPointerException.class, () -> new SyntaxException("bad", null));
    }

    @ParameterizedTest
    @CsvSource({"0, 1", "1, 0", "-4, 2"})
    void positionsAreOneBased(int line, int column) {
        assertThrows(IllegalArgumentException.class, () -> new SourcePosition(line, column));
    }

    @Test
    void otherErrorsMayCarryAPosition() {
        assertTrue(new CatalogException("no such table: t").position().isEmpty());
        assertEquals(
                Optional.of(new SourcePosition(1, 8)),
                new CatalogException("unknown column nme", new SourcePosition(1, 8)).position());
        assertEquals(
                Optional.of(new SourcePosition(2, 3)),
                new QueryException("cannot add VARCHAR and INT", new SourcePosition(2, 3)).position());
    }

    @Test
    void storageExceptionKeepsItsCause() {
        var cause = new IOException("disk full");

        var error = new StorageException("cannot write page 7", cause);

        assertSame(cause, error.getCause());
        assertTrue(error.position().isEmpty());
    }

    @Test
    void everyEngineErrorIsCoveredByOneExhaustiveSwitch() {
        List<MiniDbException> errors = List.of(
                new SyntaxException("unexpected end of input", new SourcePosition(1, 1)),
                new CatalogException("no such table: students"),
                new ConstraintViolationException("duplicate primary key: 42"),
                new QueryException("division by zero"),
                new StorageException("not a minidb file"));

        assertEquals(
                List.of("syntax", "catalog", "constraint", "query", "storage"),
                errors.stream().map(MiniDbExceptionTest::category).toList());
    }

    private static String category(MiniDbException error) {
        return switch (error) {
            case SyntaxException syntax -> "syntax";
            case CatalogException catalog -> "catalog";
            case ConstraintViolationException constraint -> "constraint";
            case QueryException query -> "query";
            case StorageException storage -> "storage";
        };
    }
}
