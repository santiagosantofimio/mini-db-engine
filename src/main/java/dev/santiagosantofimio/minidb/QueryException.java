package dev.santiagosantofimio.minidb;

public final class QueryException extends MiniDbException {

    public QueryException(String message) {
        super(message);
    }

    public QueryException(String message, SourcePosition position) {
        super(message, position);
    }
}
