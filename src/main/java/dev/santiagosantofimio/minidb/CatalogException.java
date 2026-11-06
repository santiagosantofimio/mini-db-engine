package dev.santiagosantofimio.minidb;

public final class CatalogException extends MiniDbException {

    public CatalogException(String message) {
        super(message);
    }

    public CatalogException(String message, SourcePosition position) {
        super(message, position);
    }
}
