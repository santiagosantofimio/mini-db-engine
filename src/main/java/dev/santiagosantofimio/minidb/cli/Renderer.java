package dev.santiagosantofimio.minidb.cli;

import dev.santiagosantofimio.minidb.MiniDbException;
import dev.santiagosantofimio.minidb.SourcePosition;
import dev.santiagosantofimio.minidb.SyntaxException;
import dev.santiagosantofimio.minidb.catalog.Index;
import dev.santiagosantofimio.minidb.catalog.Table;
import dev.santiagosantofimio.minidb.exec.StatementResult;
import dev.santiagosantofimio.minidb.exec.StatementResult.IndexCreated;
import dev.santiagosantofimio.minidb.exec.StatementResult.Rows;
import dev.santiagosantofimio.minidb.exec.StatementResult.RowsInserted;
import dev.santiagosantofimio.minidb.exec.StatementResult.TableCreated;
import dev.santiagosantofimio.minidb.exec.StatementResult.TableDropped;
import dev.santiagosantofimio.minidb.index.NodeView;
import dev.santiagosantofimio.minidb.index.TreeShape;
import dev.santiagosantofimio.minidb.record.Column;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

final class Renderer {

    private static final int MAX_NODES_PER_LEVEL = 24;
    private static final int MAX_KEYS_PER_NODE = 8;

    private final Style style;
    private final TableRenderer tables;
    private final boolean unicode;

    Renderer(Terminal terminal) {
        this.style = new Style(terminal.color());
        this.tables = new TableRenderer(style, terminal.unicode());
        this.unicode = terminal.unicode();
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
            case IndexCreated created -> done(
                    "Created index " + created.index() + " on " + created.table() + " (" + created.column() + ")", nanos);
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

    List<String> schema(Table table, List<Index> indexes) {
        int primaryColumn = indexes.stream().filter(Index::primary).mapToInt(Index::column).findFirst().orElse(-1);
        List<String> lines = new ArrayList<>();
        lines.add("CREATE TABLE " + table.name() + " (");
        List<Column> columns = table.schema().columns();
        for (int i = 0; i < columns.size(); i++) {
            Column column = columns.get(i);
            String constraint = i == primaryColumn ? " PRIMARY KEY" : column.nullable() ? "" : " NOT NULL";
            lines.add("  " + column.name() + " " + column.type() + constraint + (i < columns.size() - 1 ? "," : ""));
        }
        lines.add(");");
        for (Index index : indexes) {
            if (!index.primary()) {
                lines.add("CREATE INDEX " + index.name() + " ON " + table.name() + " ("
                        + table.schema().column(index.column()).name() + ");");
            }
        }
        return lines;
    }

    List<String> indexes(List<Index> indexes, List<String> columnNames) {
        if (indexes.isEmpty()) {
            return List.of(style.dim("No indexes yet"));
        }
        List<String> targets = new ArrayList<>();
        for (int i = 0; i < indexes.size(); i++) {
            targets.add(indexes.get(i).table() + " (" + columnNames.get(i) + ")");
        }
        int nameWidth = indexes.stream().mapToInt(index -> index.name().length()).max().orElse(0);
        int targetWidth = targets.stream().mapToInt(String::length).max().orElse(0);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < indexes.size(); i++) {
            Index index = indexes.get(i);
            String line = pad(index.name(), nameWidth) + "  " + pad(targets.get(i), targetWidth);
            lines.add((index.primary() ? line + "  " + style.dim("primary key") : line).stripTrailing());
        }
        return lines;
    }

    List<String> btree(Index index, String column, TreeShape shape, int order, List<List<NodeView>> levels) {
        String separator = unicode ? " · " : ", ";
        List<String> lines = new ArrayList<>();
        lines.add(style.bold(index.name()) + " on " + index.table() + " (" + column + ")");
        lines.add(style.dim(count(shape.height(), "level") + separator + count(shape.pages(), "page") + separator
                + count(shape.entries(), "key") + separator + "order " + order));
        int labelWidth = Integer.toString(levels.size()).length();
        for (int level = 0; level < levels.size(); level++) {
            List<NodeView> nodes = levels.get(level);
            boolean leaves = nodes.get(0).leaf();
            List<String> shown = new ArrayList<>();
            for (NodeView node : nodes.subList(0, Math.min(nodes.size(), MAX_NODES_PER_LEVEL))) {
                shown.add(node(node));
            }
            String line = String.join(leaves ? (unicode ? " → " : " -> ") : "  ", shown);
            if (nodes.size() > MAX_NODES_PER_LEVEL) {
                line += style.dim("  " + ellipsis() + " " + (nodes.size() - MAX_NODES_PER_LEVEL) + " more");
            }
            lines.add("  " + style.dim(pad(Integer.toString(level + 1), labelWidth)) + "  " + line);
        }
        return lines;
    }

    private String node(NodeView node) {
        List<Integer> keys = node.keys();
        List<String> parts = new ArrayList<>();
        if (keys.size() <= MAX_KEYS_PER_NODE) {
            keys.forEach(key -> parts.add(Integer.toString(key)));
        } else {
            keys.subList(0, 3).forEach(key -> parts.add(Integer.toString(key)));
            parts.add(ellipsis());
            keys.subList(keys.size() - 3, keys.size()).forEach(key -> parts.add(Integer.toString(key)));
        }
        String body = String.join(" ", parts);
        return node.leaf() ? "(" + body + ")" : "[" + body + "]";
    }

    private String ellipsis() {
        return unicode ? "…" : "...";
    }

    private static String pad(String text, int width) {
        return text + " ".repeat(Math.max(0, width - text.length()));
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
