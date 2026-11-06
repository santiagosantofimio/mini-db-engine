package dev.santiagosantofimio.minidb.cli;

import dev.santiagosantofimio.minidb.Database;
import dev.santiagosantofimio.minidb.MiniDbException;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.file.Path;

public final class Main {

    static final int EXIT_OK = 0;
    static final int EXIT_FAILURE = 1;
    static final int EXIT_USAGE = 64;
    static final String USAGE = """
            Usage: java -jar minidb.jar <database-file> [--buffer-pages N]
                   java -jar minidb.jar --help | --version""";

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.in, System.out, System.err, Terminal.detect()));
    }

    static int run(String[] args, InputStream in, PrintStream out, PrintStream err, Terminal terminal) {
        if (args.length == 1 && args[0].equals("--version")) {
            out.println("minidb " + version());
            return EXIT_OK;
        }
        if (args.length == 1 && args[0].equals("--help")) {
            out.println(USAGE);
            return EXIT_OK;
        }
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage().isEmpty() ? USAGE : "minidb: " + e.getMessage() + "\n" + USAGE);
            return EXIT_USAGE;
        }
        try (Database database = Database.open(options.database(), options.bufferPages())) {
            BufferedReader input = new BufferedReader(new InputStreamReader(in, Charset.defaultCharset()));
            Repl repl = new Repl(database, out, err, terminal);
            return repl.run(input, options.database().getFileName().toString(), version());
        } catch (MiniDbException e) {
            err.println("minidb: " + e.getMessage());
            return EXIT_FAILURE;
        }
    }

    static String version() {
        String version = Main.class.getPackage().getImplementationVersion();
        return version == null ? "development build" : version;
    }

    private record Options(Path database, int bufferPages) {

        static Options parse(String[] args) {
            Path database = null;
            int bufferPages = Database.DEFAULT_BUFFER_PAGES;
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (arg.equals("--buffer-pages")) {
                    if (i + 1 == args.length) {
                        throw new IllegalArgumentException("--buffer-pages needs a number");
                    }
                    bufferPages = bufferPages(args[++i]);
                } else if (arg.startsWith("-")) {
                    throw new IllegalArgumentException("unknown option " + arg);
                } else if (database == null) {
                    database = Path.of(arg);
                } else {
                    throw new IllegalArgumentException("expected one database file, got " + database + " and " + arg);
                }
            }
            if (database == null) {
                throw new IllegalArgumentException("");
            }
            return new Options(database, bufferPages);
        }

        private static int bufferPages(String text) {
            int pages;
            try {
                pages = Integer.parseInt(text);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("--buffer-pages needs a number, got " + text);
            }
            if (pages < Database.MIN_BUFFER_PAGES) {
                throw new IllegalArgumentException("--buffer-pages must be at least " + Database.MIN_BUFFER_PAGES);
            }
            return pages;
        }
    }
}
