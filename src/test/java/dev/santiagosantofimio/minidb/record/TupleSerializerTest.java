package dev.santiagosantofimio.minidb.record;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.santiagosantofimio.minidb.StorageException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class TupleSerializerTest {

    private static final Schema STUDENTS = Schema.of(
            new Column("id", DataType.INT, false),
            new Column("name", DataType.varchar(40), true),
            new Column("gpa", DataType.DOUBLE, true),
            new Column("active", DataType.BOOLEAN, true));

    private static Tuple roundTrip(Schema schema, Tuple tuple) {
        return TupleSerializer.deserialize(schema, TupleSerializer.serialize(schema, tuple));
    }

    @Test
    void everyTypeRoundTrips() {
        Tuple tuple = Tuple.of(Value.of(7), Value.of("Ana"), Value.of(4.25), Value.TRUE);

        assertEquals(tuple, roundTrip(STUDENTS, tuple));
    }

    @Test
    void nullsRoundTripAndTakeNoSpace() {
        Tuple tuple = Tuple.of(Value.of(7), Value.NULL, Value.NULL, Value.NULL);

        byte[] bytes = TupleSerializer.serialize(STUDENTS, tuple);

        assertEquals(1 + Integer.BYTES, bytes.length);
        assertEquals(tuple, TupleSerializer.deserialize(STUDENTS, bytes));
    }

    @Test
    void layoutIsANullBitmapFollowedByTheFields() {
        Tuple tuple = Tuple.of(Value.of(7), Value.of("Ana"), Value.NULL, Value.TRUE);

        byte[] expected = {0b0000_0100, 0, 0, 0, 7, 0, 3, 'A', 'n', 'a', 1};

        assertArrayEquals(expected, TupleSerializer.serialize(STUDENTS, tuple));
    }

    @Test
    void nullBitmapGrowsByOneBytePerEightColumns() {
        List<Column> columns = new ArrayList<>();
        List<Value> values = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            columns.add(new Column("c" + i, DataType.INT, true));
            values.add(i == 0 || i == 9 ? Value.NULL : Value.of(i));
        }
        Schema schema = new Schema(columns);
        Tuple tuple = new Tuple(values);

        byte[] bytes = TupleSerializer.serialize(schema, tuple);

        assertEquals(2 + 8 * Integer.BYTES, bytes.length);
        assertArrayEquals(new byte[] {0b0000_0001, 0b0000_0010}, Arrays.copyOf(bytes, 2));
        assertEquals(tuple, TupleSerializer.deserialize(schema, bytes));
    }

    @Test
    void stringsAreStoredAsUtf8WithAByteLengthPrefix() {
        String name = "Ñandú 🦤";
        Schema schema = Schema.of(new Column("name", DataType.varchar(20), false));
        int utf8Length = name.getBytes(StandardCharsets.UTF_8).length;

        byte[] bytes = TupleSerializer.serialize(schema, Tuple.of(Value.of(name)));

        assertEquals(1 + 2 + utf8Length, bytes.length);
        assertEquals(utf8Length, bytes[2]);
        assertEquals(Tuple.of(Value.of(name)), TupleSerializer.deserialize(schema, bytes));
    }

    @Test
    void doublesKeepTheirExactBits() {
        Schema schema = Schema.of(new Column("x", DataType.DOUBLE, false));

        for (double x : new double[] {-0.0, Double.NaN, Double.MIN_VALUE, Double.MAX_VALUE, 0.1}) {
            assertEquals(Tuple.of(Value.of(x)), roundTrip(schema, Tuple.of(Value.of(x))));
        }
    }

    @Test
    void rejectsValuesOfTheWrongType() {
        Tuple tuple = Tuple.of(Value.of("7"), Value.NULL, Value.NULL, Value.NULL);

        assertThrows(IllegalArgumentException.class, () -> TupleSerializer.serialize(STUDENTS, tuple));
    }

    @Test
    void rejectsTuplesWithTheWrongNumberOfValues() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TupleSerializer.serialize(STUDENTS, Tuple.of(Value.of(1))));
    }

    @Test
    void detectsTruncatedData() {
        byte[] bytes = TupleSerializer.serialize(STUDENTS, Tuple.of(Value.of(7), Value.of("Ana"), Value.NULL, Value.TRUE));

        assertThrows(
                StorageException.class,
                () -> TupleSerializer.deserialize(STUDENTS, Arrays.copyOf(bytes, bytes.length - 1)));
    }

    @Test
    void detectsTrailingBytes() {
        byte[] bytes = TupleSerializer.serialize(STUDENTS, Tuple.of(Value.of(7), Value.NULL, Value.NULL, Value.NULL));

        assertThrows(
                StorageException.class,
                () -> TupleSerializer.deserialize(STUDENTS, Arrays.copyOf(bytes, bytes.length + 1)));
    }

    @Test
    void detectsInvalidBooleans() {
        Schema schema = Schema.of(new Column("flag", DataType.BOOLEAN, false));

        assertThrows(StorageException.class, () -> TupleSerializer.deserialize(schema, new byte[] {0, 2}));
    }
}
