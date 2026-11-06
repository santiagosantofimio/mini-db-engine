package dev.santiagosantofimio.minidb;

public abstract sealed class MiniDbException extends RuntimeException
        permits SyntaxException, CatalogException, ConstraintViolationException, StorageException {

    protected MiniDbException(String message) {
        super(message);
    }

    protected MiniDbException(String message, Throwable cause) {
        super(message, cause);
    }
}
