package dev.santiagosantofimio.minidb.cli;

import dev.santiagosantofimio.minidb.MiniDbException;
import dev.santiagosantofimio.minidb.SourcePosition;
import dev.santiagosantofimio.minidb.SyntaxException;
import dev.santiagosantofimio.minidb.catalog.Table;
import dev.santiagosantofimio.minidb.exec.StatementResult;
import dev.santiagosantofimio.minidb.exec.StatementResult.Rows;
import dev.santiagosantofimio.minidb.exec.StatementResult.RowsInserted;
import dev.santiagosantofimio.minidb.exec.StatementResult.TableCreated;
import dev.santiagosantofimio.minidb.exec.StatementResult.TableDropped;
import dev.santiagosantofimio.minidb.record.Column;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

final class Renderer {

    private final Style style;
    private final TableRenderer tables;

    Renderer(Terminal terminal) {
        this.style = new Style(terminal.color());
        this.tables = new TableRenderer(style, terminal.unicode());
    }

    Style style() {
        return style;
    }

    List<String> result(StatementResult result, long nanos) {
        return switch (result) {
            case Rows rows -> {
                List<String> lines = new ArrayList<>(tables.render(rows.columns(), rows.types(), rows.rows()));
                lines.add(style.dim(count(rows.rows().size(), "row") + " in " + duration(nanos)));
                yield lines;
            }
            case TableCreated created -> done("Created table " + created.table(), nanos);
            case TableDropped dropped -> done("Dropped table " + dropped.table(), nanos);
            case RowsInserted inserted -> done(
                    "Inserted " + count(inserted.count(), "row") + " into " + inserted.table(), nanos);
        };
    }

    private List<String> done(String message, long nanos) {
        return List.of(message + style.dim(" in " + duration(nanos)));
    }

    List<String> error(MiniDbException error, String source, String sourceName, int firstLine) {
        String label = error instanceof SyntaxException ? "Syntax error" : "Error";
        Optional<SourcePosition> position = error.position();
        if (position.isEmpty()) {
            return List.of(style.error(label + ":") + " " + error.getMessage());
        }
        int line = position.get().line();
        int column = position.get().column();
        String where = (sourceName == null ? "" : " in " + sourceName) + " at line " + (firstLine + line - 1)
                + ", column " + column;
        String sourceLine = source.lines().skip(line - 1).findFirst().orElse("");
        return List.of(
                style.error(label) + style.dim(where + ":") + " " + error.getMessage(),
                "  " + sourceLine,
                "  " + caretIndent(sourceLine, column) + style.error("^"));
    }

    List<String> message(String text) {
        return List.of(style.error("Error:") + " " + text);
    }

    List<String> schema(Table table) {
        List<String> lines = new ArrayList<>();
        lines.add("CREATE TABLE " + table.name() + " (");
        List<Column> columns = table.schema().columns();
        for (int i = 0; i < columns.size(); i++) {
            Column column = columns.get(i);
            lines.add("  " + column.name() + " " + column.type() + (column.nullable() ? "" : " NOT NULL")
                    + (i < columns.size() - 1 ? "," : ""));
        }
        lines.add(");");
        return lines;
    }

    static String count(long count, String noun) {
        return String.format(Locale.ROOT, "%,d %s%s", count, noun, count == 1 ? "" : "s");
    }

    static String duration(long nanos) {
        double millis = nanos / 1_000_000.0;
        if (millis < 1) {
            return String.format(Locale.ROOT, "%.2f ms", millis);
        }
        if (millis < 100) {
            return String.format(Locale.ROOT, "%.1f ms", millis);
        }
        if (millis < 1000) {
            return String.format(Locale.ROOT, "%.0f ms", millis);
        }
        return String.format(Locale.ROOT, "%.2f s", millis / 1000);
    }

    private static String caretIndent(String sourceLine, int column) {
        StringBuilder indent = new StringBuilder();
        int[] codePoints = sourceLine.codePoints().toArray();
        for (int i = 0; i < column - 1 && i < codePoints.length; i++) {
            indent.append(codePoints[i] == '\t' ? '\t' : ' ');
        }
        return indent.toString();
    }
}
