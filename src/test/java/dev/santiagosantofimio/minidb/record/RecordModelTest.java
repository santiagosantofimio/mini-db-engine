package dev.santiagosantofimio.minidb.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecordModelTest {

    @Test
    void typesPrintTheirSqlNames() {
        assertEquals("INT", DataType.INT.toString());
        assertEquals("DOUBLE", DataType.DOUBLE.toString());
        assertEquals("BOOLEAN", DataType.BOOLEAN.toString());
        assertEquals("VARCHAR(20)", DataType.varchar(20).toString());
    }

    @Test
    void eachTypeAcceptsItsOwnValuesAndNull() {
        assertTrue(DataType.INT.accepts(Value.of(1)));
        assertTrue(DataType.DOUBLE.accepts(Value.of(1.5)));
        assertTrue(DataType.BOOLEAN.accepts(Value.FALSE));
        assertTrue(DataType.varchar(5).accepts(Value.of("abc")));
        assertTrue(DataType.INT.accepts(Value.NULL));

        assertFalse(DataType.INT.accepts(Value.of(1.5)));
        assertFalse(DataType.varchar(5).accepts(Value.of(1)));
        assertFalse(DataType.BOOLEAN.accepts(Value.of("true")));
    }

    @Test
    void varcharNeedsAPositiveLength() {
        assertThrows(IllegalArgumentException.class, () -> DataType.varchar(0));
    }

    @Test
    void sqlNullIsAValueNeverAJavaNull() {
        assertTrue(Value.NULL.isNull());
        assertFalse(Value.of(0).isNull());
        assertThrows(NullPointerException.class, () -> Value.of((String) null));
        assertThrows(NullPointerException.class, () -> Tuple.of(Value.of(1), null));
    }

    @Test
    void booleanValuesAreShared() {
        assertSame(Value.TRUE, Value.of(true));
        assertSame(Value.FALSE, Value.of(false));
    }

    @Test
    void schemasAndTuplesCopyTheirInput() {
        List<Column> columns = new ArrayList<>(List.of(new Column("id", DataType.INT, false)));
        Schema schema = new Schema(columns);
        columns.add(new Column("name", DataType.varchar(10), true));

        assertEquals(1, schema.size());
        assertEquals("id", schema.column(0).name());

        Value[] values = {Value.of(1)};
        Tuple tuple = new Tuple(Arrays.asList(values));
        values[0] = Value.of(2);
        assertEquals(Value.of(1), tuple.get(0));
    }

    @Test
    void schemaNeedsAtLeastOneColumn() {
        assertThrows(IllegalArgumentException.class, Schema::of);
    }

    @Test
    void columnNeedsAName() {
        assertThrows(IllegalArgumentException.class, () -> new Column(" ", DataType.INT, true));
    }
}
