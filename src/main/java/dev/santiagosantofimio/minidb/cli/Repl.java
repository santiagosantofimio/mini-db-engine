package dev.santiagosantofimio.minidb.cli;

import dev.santiagosantofimio.minidb.Database;
import dev.santiagosantofimio.minidb.MiniDbException;
import dev.santiagosantofimio.minidb.catalog.Table;
import dev.santiagosantofimio.minidb.exec.StatementResult;
import dev.santiagosantofimio.minidb.sql.Lexer;
import dev.santiagosantofimio.minidb.sql.Statement;
import dev.santiagosantofimio.minidb.storage.BufferPoolStats;
import dev.santiagosantofimio.minidb.storage.Pager;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

final class Repl {

    static final String PROMPT = "minidb> ";
    static final String CONTINUATION = "   ...> ";

    private static final int MAX_READ_DEPTH = 16;

    private static final List<String[]> COMMANDS = List.of(
            new String[] {".help", "Show this list"},
            new String[] {".tables", "List the tables"},
            new String[] {".schema [table]", "Show CREATE TABLE statements"},
            new String[] {".stats", "Show buffer pool statistics"},
            new String[] {".read <file>", "Run the SQL in a file"},
            new String[] {".exit", "Leave minidb"});

    private enum Outcome {
        FINISHED,
        FAILED,
        EXIT
    }

    private final Database database;
    private final PrintStream out;
    private final PrintStream err;
    private final Terminal terminal;
    private final Renderer renderer;
    private int readDepth;
    private boolean anyError;

    Repl(Database database, PrintStream out, PrintStream err, Terminal terminal) {
        this.database = database;
        this.out = out;
        this.err = err;
        this.terminal = terminal;
        this.renderer = new Renderer(terminal);
    }

    int run(BufferedReader input, String databaseName, String version) {
        if (terminal.interactive()) {
            out.println(renderer.style().bold("minidb " + version) + renderer.style().dim(" · " + databaseName));
            out.println(renderer.style().dim("Enter SQL ending with ; or type .help for commands."));
        }
        consume(input, null, terminal.interactive(), false);
        if (terminal.interactive()) {
            out.println();
        }
        return anyError && !terminal.interactive() ? 1 : 0;
    }

    private Outcome consume(BufferedReader reader, String sourceName, boolean prompts, boolean stopOnError) {
        StringBuilder buffer = new StringBuilder();
        int lineNumber = 0;
        int firstLine = 1;
        while (true) {
            if (prompts) {
                out.print(buffer.isEmpty() ? PROMPT : CONTINUATION);
                out.flush();
            }
            String line = readLine(reader);
            if (line == null) {
                if (!buffer.toString().isBlank() && !runSql(buffer.toString(), sourceName, firstLine) && stopOnError) {
                    return Outcome.FAILED;
                }
                return Outcome.FINISHED;
            }
            lineNumber++;
            if (buffer.isEmpty()) {
                if (line.isBlank()) {
                    continue;
                }
                if (line.strip().startsWith(".")) {
                    Outcome outcome = metaCommand(line.strip());
                    if (outcome == Outcome.EXIT || (outcome == Outcome.FAILED && stopOnError)) {
                        return outcome;
                    }
                    continue;
                }
                firstLine = lineNumber;
            }
            buffer.append(line).append('\n');
            if (Lexer.isComplete(buffer.toString())) {
                boolean succeeded = runSql(buffer.toString(), sourceName, firstLine);
                buffer.setLength(0);
                if (!succeeded && stopOnError) {
                    return Outcome.FAILED;
                }
            }
        }
    }

    private boolean runSql(String sql, String sourceName, int lineInSource) {
        int firstLine = sourceName == null ? 1 : lineInSource;
        List<Statement> statements;
        try {
            statements = database.parse(sql);
        } catch (MiniDbException e) {
            return report(e, sql, sourceName, firstLine);
        }
        for (Statement statement : statements) {
            long start = System.nanoTime();
            try {
                StatementResult result = database.execute(statement);
                print(renderer.result(result, System.nanoTime() - start));
            } catch (MiniDbException e) {
                return report(e, sql, sourceName, firstLine);
            } catch (RuntimeException e) {
                anyError = true;
                printError(renderer.message("internal error: " + e));
                return false;
            }
        }
        return true;
    }

    private boolean report(MiniDbException error, String sql, String sourceName, int firstLine) {
        anyError = true;
        printError(renderer.error(error, sql, sourceName, firstLine));
        return false;
    }

    private Outcome metaCommand(String line) {
        String[] parts = line.split("\\s+", 2);
        String argument = parts.length > 1 ? parts[1].strip() : "";
        switch (parts[0].toLowerCase(Locale.ROOT)) {
            case ".exit", ".quit" -> {
                return Outcome.EXIT;
            }
            case ".help" -> help();
            case ".tables" -> tables();
            case ".schema" -> {
                return schema(argument);
            }
            case ".stats" -> stats();
            case ".read" -> {
                return read(argument);
            }
            default -> {
                return fail("unknown command " + parts[0] + "; type .help to see the commands");
            }
        }
        return Outcome.FINISHED;
    }

    private void help() {
        int width = COMMANDS.stream().mapToInt(command -> command[0].length()).max().orElse(0);
        for (String[] command : COMMANDS) {
            out.println(command[0] + " ".repeat(width - command[0].length() + 2) + renderer.style().dim(command[1]));
        }
    }

    private void tables() {
        List<Table> tables = database.tables();
        if (tables.isEmpty()) {
            out.println(renderer.style().dim("No tables yet"));
        }
        tables.forEach(table -> out.println(table.name()));
    }

    private Outcome schema(String name) {
        if (name.isEmpty()) {
            database.tables().forEach(table -> print(renderer.schema(table)));
            return Outcome.FINISHED;
        }
        Optional<Table> table = database.table(name.toLowerCase(Locale.ROOT));
        if (table.isEmpty()) {
            return fail("no such table: " + name);
        }
        print(renderer.schema(table.get()));
        return Outcome.FINISHED;
    }

    private void stats() {
        BufferPoolStats stats = database.stats();
        long pages = database.pageCount();
        print(List.of(
                row("Buffer pool", Renderer.count(database.bufferPages(), "page")),
                row("Hits", String.format(Locale.ROOT, "%,d (%.1f%%)", stats.hits(), stats.hitRatio() * 100)),
                row("Misses", String.format(Locale.ROOT, "%,d", stats.misses())),
                row("Evictions", String.format(Locale.ROOT, "%,d", stats.evictions())),
                row("File", Renderer.count(pages, "page") + String.format(Locale.ROOT, " (%,d KB)",
                        pages * Pager.PAGE_SIZE / 1024))));
    }

    private String row(String label, String value) {
        return renderer.style().dim(String.format(Locale.ROOT, "%-12s", label)) + value;
    }

    private Outcome read(String argument) {
        if (argument.isEmpty()) {
            return fail(".read needs a file name");
        }
        if (readDepth >= MAX_READ_DEPTH) {
            return fail(".read is nested more than " + MAX_READ_DEPTH + " levels deep");
        }
        Path path = Path.of(argument);
        BufferedReader reader;
        try {
            reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return fail("cannot read " + argument + ": " + describe(e));
        }
        readDepth++;
        try (reader) {
            Outcome outcome = consume(reader, path.getFileName().toString(), false, true);
            if (outcome == Outcome.FAILED) {
                printError(List.of(renderer.style().dim("Stopped reading " + argument)));
            }
            return outcome;
        } catch (IOException e) {
            return fail("cannot close " + argument + ": " + describe(e));
        } finally {
            readDepth--;
        }
    }

    private Outcome fail(String message) {
        anyError = true;
        printError(renderer.message(message));
        return Outcome.FAILED;
    }

    private static String describe(IOException e) {
        return e instanceof NoSuchFileException ? "no such file" : String.valueOf(e.getMessage());
    }

    private static String readLine(BufferedReader reader) {
        try {
            return reader.readLine();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void print(List<String> lines) {
        lines.forEach(out::println);
        out.flush();
    }

    private void printError(List<String> lines) {
        out.flush();
        lines.forEach(err::println);
        err.flush();
    }
}
