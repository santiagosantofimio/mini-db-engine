package dev.santiagosantofimio.minidb.cli;

import java.io.PrintStream;

public final class Main {

    static final int EXIT_OK = 0;
    static final int EXIT_USAGE = 64;
    static final String USAGE = "Usage: java -jar minidb.jar [--help | --version]";

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        String option = args.length == 1 ? args[0] : "";
        return switch (option) {
            case "--version" -> {
                out.println("minidb " + version());
                yield EXIT_OK;
            }
            case "--help" -> {
                out.println(USAGE);
                yield EXIT_OK;
            }
            default -> {
                err.println(USAGE);
                yield EXIT_USAGE;
            }
        };
    }

    static String version() {
        String version = Main.class.getPackage().getImplementationVersion();
        return version == null ? "development build" : version;
    }
}
