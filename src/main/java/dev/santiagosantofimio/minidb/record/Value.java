package dev.santiagosantofimio.minidb.record;

import java.util.Objects;

public sealed interface Value {

    Value NULL = new NullValue();
    Value TRUE = new BooleanValue(true);
    Value FALSE = new BooleanValue(false);

    static Value of(int value) {
        return new IntValue(value);
    }

    static Value of(double value) {
        return new DoubleValue(value);
    }

    static Value of(boolean value) {
        return value ? TRUE : FALSE;
    }

    static Value of(String value) {
        return new StringValue(value);
    }

    default boolean isNull() {
        return this instanceof NullValue;
    }

    record NullValue() implements Value {
    }

    record IntValue(int value) implements Value {
    }

    record DoubleValue(double value) implements Value {
    }

    record BooleanValue(boolean value) implements Value {
    }

    record StringValue(String value) implements Value {

        public StringValue {
            Objects.requireNonNull(value, "a string value cannot be null; use Value.NULL for SQL NULL");
        }
    }
}
