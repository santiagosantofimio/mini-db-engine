package dev.santiagosantofimio.minidb;

import java.util.Objects;

public final class SyntaxException extends MiniDbException {

    public SyntaxException(String message, SourcePosition position) {
        super(message, Objects.requireNonNull(position, "a syntax error needs a position"));
    }
}
