package dev.santiagosantofimio.minidb;

import java.util.Optional;

public abstract sealed class MiniDbException extends RuntimeException
        permits SyntaxException, CatalogException, ConstraintViolationException, QueryException, StorageException {

    private final SourcePosition position;

    protected MiniDbException(String message) {
        super(message);
        this.position = null;
    }

    protected MiniDbException(String message, Throwable cause) {
        super(message, cause);
        this.position = null;
    }

    protected MiniDbException(String message, SourcePosition position) {
        super(message);
        this.position = position;
    }

    public Optional<SourcePosition> position() {
        return Optional.ofNullable(position);
    }
}
