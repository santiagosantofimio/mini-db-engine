package dev.santiagosantofimio.minidb.cli;

import java.io.Console;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

record Terminal(boolean interactive, boolean color, boolean unicode) {

    static final Terminal PLAIN = new Terminal(false, false, true);

    static Terminal detect() {
        boolean interactive = isTerminal();
        boolean colorAllowed = isBlank(System.getenv("NO_COLOR")) && !"dumb".equals(System.getenv("TERM"));
        String encoding = System.getProperty("stdout.encoding", Charset.defaultCharset().name());
        boolean unicode = Charset.forName(encoding, StandardCharsets.US_ASCII).equals(StandardCharsets.UTF_8);
        return new Terminal(interactive, interactive && colorAllowed, unicode);
    }

    private static boolean isTerminal() {
        Console console = System.console();
        if (console == null) {
            return false;
        }
        try {
            Method isTerminal = Console.class.getMethod("isTerminal");
            return (Boolean) isTerminal.invoke(console);
        } catch (ReflectiveOperationException e) {
            return true;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
