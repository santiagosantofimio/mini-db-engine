package dev.santiagosantofimio.minidb.record;

import dev.santiagosantofimio.minidb.record.Value.BooleanValue;
import dev.santiagosantofimio.minidb.record.Value.DoubleValue;
import dev.santiagosantofimio.minidb.record.Value.IntValue;
import dev.santiagosantofimio.minidb.record.Value.NullValue;
import dev.santiagosantofimio.minidb.record.Value.StringValue;

public sealed interface DataType {

    DataType INT = new IntType();
    DataType DOUBLE = new DoubleType();
    DataType BOOLEAN = new BooleanType();

    static DataType varchar(int maxLength) {
        return new VarcharType(maxLength);
    }

    default boolean accepts(Value value) {
        return switch (value) {
            case NullValue nullValue -> true;
            case IntValue intValue -> this instanceof IntType;
            case DoubleValue doubleValue -> this instanceof DoubleType;
            case BooleanValue booleanValue -> this instanceof BooleanType;
            case StringValue stringValue -> this instanceof VarcharType;
        };
    }

    record IntType() implements DataType {

        @Override
        public String toString() {
            return "INT";
        }
    }

    record DoubleType() implements DataType {

        @Override
        public String toString() {
            return "DOUBLE";
        }
    }

    record BooleanType() implements DataType {

        @Override
        public String toString() {
            return "BOOLEAN";
        }
    }

    record VarcharType(int maxLength) implements DataType {

        public VarcharType {
            if (maxLength < 1) {
                throw new IllegalArgumentException("VARCHAR length must be at least 1, got " + maxLength);
            }
        }

        @Override
        public String toString() {
            return "VARCHAR(" + maxLength + ")";
        }
    }
}
