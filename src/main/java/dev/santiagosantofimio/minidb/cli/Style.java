package dev.santiagosantofimio.minidb.cli;

final class Style {

    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String DIM = "\u001B[2m";
    private static final String RED = "\u001B[1;31m";

    private final boolean enabled;

    Style(boolean enabled) {
        this.enabled = enabled;
    }

    String bold(String text) {
        return wrap(BOLD, text);
    }

    String dim(String text) {
        return wrap(DIM, text);
    }

    String error(String text) {
        return wrap(RED, text);
    }

    private String wrap(String code, String text) {
        return enabled && !text.isEmpty() ? code + text + RESET : text;
    }
}
