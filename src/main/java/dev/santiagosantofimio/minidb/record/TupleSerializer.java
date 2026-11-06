package dev.santiagosantofimio.minidb.record;

import dev.santiagosantofimio.minidb.StorageException;
import dev.santiagosantofimio.minidb.record.DataType.BooleanType;
import dev.santiagosantofimio.minidb.record.DataType.DoubleType;
import dev.santiagosantofimio.minidb.record.DataType.IntType;
import dev.santiagosantofimio.minidb.record.DataType.VarcharType;
import dev.santiagosantofimio.minidb.record.Value.BooleanValue;
import dev.santiagosantofimio.minidb.record.Value.DoubleValue;
import dev.santiagosantofimio.minidb.record.Value.IntValue;
import dev.santiagosantofimio.minidb.record.Value.NullValue;
import dev.santiagosantofimio.minidb.record.Value.StringValue;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class TupleSerializer {

    private static final int LENGTH_PREFIX_SIZE = 2;
    private static final int MAX_STRING_BYTES = 0xFFFF;

    private TupleSerializer() {
    }

    public static byte[] serialize(Schema schema, Tuple tuple) {
        if (tuple.size() != schema.size()) {
            throw new IllegalArgumentException(
                    "the tuple has " + tuple.size() + " values but the schema has " + schema.size() + " columns");
        }
        byte[][] strings = new byte[schema.size()][];
        int size = bitmapSize(schema);
        for (int i = 0; i < schema.size(); i++) {
            Column column = schema.column(i);
            Value value = tuple.get(i);
            if (!column.type().accepts(value)) {
                throw new IllegalArgumentException(
                        "column " + column.name() + " has type " + column.type() + " but got " + value);
            }
            if (value instanceof StringValue string) {
                strings[i] = encode(column, string);
            }
            size += encodedSize(value, strings[i]);
        }

        ByteBuffer out = ByteBuffer.allocate(size);
        out.position(bitmapSize(schema));
        for (int i = 0; i < schema.size(); i++) {
            switch (tuple.get(i)) {
                case NullValue nullValue -> markNull(out, i);
                case IntValue integer -> out.putInt(integer.value());
                case DoubleValue real -> out.putDouble(real.value());
                case BooleanValue bool -> out.put((byte) (bool.value() ? 1 : 0));
                case StringValue string -> out.putShort((short) strings[i].length).put(strings[i]);
            }
        }
        return out.array();
    }

    public static Tuple deserialize(Schema schema, byte[] bytes) {
        ByteBuffer in = ByteBuffer.wrap(bytes);
        try {
            byte[] bitmap = new byte[bitmapSize(schema)];
            in.get(bitmap);
            List<Value> values = new ArrayList<>(schema.size());
            for (int i = 0; i < schema.size(); i++) {
                values.add(isNull(bitmap, i) ? Value.NULL : read(schema.column(i).type(), in));
            }
            if (in.hasRemaining()) {
                throw new StorageException("damaged tuple: " + in.remaining() + " bytes left after the last column");
            }
            return new Tuple(values);
        } catch (BufferUnderflowException e) {
            throw new StorageException("damaged tuple: the data ends before the last column", e);
        }
    }

    private static int bitmapSize(Schema schema) {
        return (schema.size() + Byte.SIZE - 1) / Byte.SIZE;
    }

    private static int encodedSize(Value value, byte[] encodedString) {
        return switch (value) {
            case NullValue nullValue -> 0;
            case IntValue integer -> Integer.BYTES;
            case DoubleValue real -> Double.BYTES;
            case BooleanValue bool -> 1;
            case StringValue string -> LENGTH_PREFIX_SIZE + encodedString.length;
        };
    }

    private static byte[] encode(Column column, StringValue string) {
        byte[] encoded = string.value().getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_STRING_BYTES) {
            throw new IllegalArgumentException("column " + column.name() + " got a string of " + encoded.length
                    + " bytes; the format stores at most " + MAX_STRING_BYTES);
        }
        return encoded;
    }

    private static void markNull(ByteBuffer out, int column) {
        int index = column / Byte.SIZE;
        out.put(index, (byte) (out.get(index) | 1 << (column % Byte.SIZE)));
    }

    private static boolean isNull(byte[] bitmap, int column) {
        return (bitmap[column / Byte.SIZE] & 1 << (column % Byte.SIZE)) != 0;
    }

    private static Value read(DataType type, ByteBuffer in) {
        return switch (type) {
            case IntType integer -> Value.of(in.getInt());
            case DoubleType real -> Value.of(in.getDouble());
            case BooleanType bool -> readBoolean(in.get());
            case VarcharType varchar -> readString(in);
        };
    }

    private static Value readBoolean(byte stored) {
        return switch (stored) {
            case 0 -> Value.FALSE;
            case 1 -> Value.TRUE;
            default -> throw new StorageException("damaged tuple: " + stored + " is not a boolean");
        };
    }

    private static Value readString(ByteBuffer in) {
        byte[] encoded = new byte[Short.toUnsignedInt(in.getShort())];
        in.get(encoded);
        return Value.of(new String(encoded, StandardCharsets.UTF_8));
    }
}
