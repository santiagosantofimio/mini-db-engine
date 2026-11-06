package dev.santiagosantofimio.minidb.cli;

import dev.santiagosantofimio.minidb.plan.ValueType;
import dev.santiagosantofimio.minidb.record.Tuple;
import dev.santiagosantofimio.minidb.record.Value;
import dev.santiagosantofimio.minidb.record.Value.BooleanValue;
import dev.santiagosantofimio.minidb.record.Value.DoubleValue;
import dev.santiagosantofimio.minidb.record.Value.IntValue;
import dev.santiagosantofimio.minidb.record.Value.NullValue;
import dev.santiagosantofimio.minidb.record.Value.StringValue;
import java.util.ArrayList;
import java.util.List;

final class TableRenderer {

    private record Box(String top, String header, String bottom, String vertical, String horizontal) {
    }

    private static final Box UNICODE = new Box("┌┬┐", "├┼┤", "└┴┘", "│", "─");
    private static final Box ASCII = new Box("+++", "+++", "+++", "|", "-");

    private static final String NULL_TEXT = "NULL";

    private final Style style;
    private final Box box;

    TableRenderer(Style style, boolean unicode) {
        this.style = style;
        this.box = unicode ? UNICODE : ASCII;
    }

    List<String> render(List<String> columns, List<ValueType> types, List<Tuple> rows) {
        int count = columns.size();
        int[] widths = new int[count];
        boolean[] numeric = new boolean[count];
        for (int column = 0; column < count; column++) {
            widths[column] = width(columns.get(column));
            numeric[column] = types.get(column).isNumeric();
            for (Tuple row : rows) {
                widths[column] = Math.max(widths[column], width(text(row.get(column))));
            }
        }

        List<String> lines = new ArrayList<>(rows.size() + 4);
        lines.add(rule(box.top(), widths));
        List<String> header = new ArrayList<>(count);
        for (int column = 0; column < count; column++) {
            header.add(style.bold(pad(columns.get(column), widths[column], numeric[column])));
        }
        lines.add(line(header));
        lines.add(rule(box.header(), widths));
        for (Tuple row : rows) {
            List<String> cells = new ArrayList<>(count);
            for (int column = 0; column < count; column++) {
                Value value = row.get(column);
                String cell = pad(text(value), widths[column], numeric[column]);
                cells.add(value.isNull() ? style.dim(cell) : cell);
            }
            lines.add(line(cells));
        }
        lines.add(rule(box.bottom(), widths));
        return lines;
    }

    static String text(Value value) {
        return switch (value) {
            case NullValue nullValue -> NULL_TEXT;
            case IntValue integer -> Integer.toString(integer.value());
            case DoubleValue real -> Double.toString(real.value());
            case BooleanValue bool -> Boolean.toString(bool.value());
            case StringValue string -> visible(string.value());
        };
    }

    private static String visible(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(c -> {
            switch (c) {
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (Character.isISOControl(c)) {
                        out.append(String.format("\\u%04x", c));
                    } else {
                        out.appendCodePoint(c);
                    }
                }
            }
        });
        return out.toString();
    }

    private String rule(String corners, int[] widths) {
        StringBuilder rule = new StringBuilder().append(corners.charAt(0));
        for (int column = 0; column < widths.length; column++) {
            if (column > 0) {
                rule.append(corners.charAt(1));
            }
            rule.append(box.horizontal().repeat(widths[column] + 2));
        }
        return style.dim(rule.append(corners.charAt(2)).toString());
    }

    private String line(List<String> cells) {
        String separator = style.dim(box.vertical());
        StringBuilder line = new StringBuilder(separator);
        for (String cell : cells) {
            line.append(' ').append(cell).append(' ').append(separator);
        }
        return line.toString();
    }

    private static String pad(String text, int width, boolean rightAligned) {
        String padding = " ".repeat(width - width(text));
        return rightAligned ? padding + text : text + padding;
    }

    private static int width(String text) {
        return text.codePointCount(0, text.length());
    }
}
