package dev.santiagosantofimio.minidb.sql;

import dev.santiagosantofimio.minidb.SourcePosition;

public record Token(TokenType type, String text, SourcePosition position) {

    public String describe() {
        if (type.isKeyword()) {
            return "the keyword " + type.name();
        }
        return switch (type) {
            case IDENTIFIER -> "'" + text + "'";
            case STRING -> "the string '" + text + "'";
            case INTEGER, DECIMAL -> text;
            default -> type.description();
        };
    }
}
