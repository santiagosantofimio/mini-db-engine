package dev.santiagosantofimio.minidb.plan;

import dev.santiagosantofimio.minidb.record.DataType;
import dev.santiagosantofimio.minidb.record.DataType.BooleanType;
import dev.santiagosantofimio.minidb.record.DataType.DoubleType;
import dev.santiagosantofimio.minidb.record.DataType.IntType;
import dev.santiagosantofimio.minidb.record.DataType.VarcharType;
import dev.santiagosantofimio.minidb.record.Value;
import dev.santiagosantofimio.minidb.record.Value.BooleanValue;
import dev.santiagosantofimio.minidb.record.Value.DoubleValue;
import dev.santiagosantofimio.minidb.record.Value.IntValue;
import dev.santiagosantofimio.minidb.record.Value.NullValue;
import dev.santiagosantofimio.minidb.record.Value.StringValue;

public enum ValueType {
    INT,
    DOUBLE,
    BOOLEAN,
    VARCHAR,
    NULL;

    public static ValueType of(DataType type) {
        return switch (type) {
            case IntType integer -> INT;
            case DoubleType real -> DOUBLE;
            case BooleanType bool -> BOOLEAN;
            case VarcharType varchar -> VARCHAR;
        };
    }

    public static ValueType of(Value value) {
        return switch (value) {
            case NullValue nullValue -> NULL;
            case IntValue integer -> INT;
            case DoubleValue real -> DOUBLE;
            case BooleanValue bool -> BOOLEAN;
            case StringValue string -> VARCHAR;
        };
    }

    public boolean isNumeric() {
        return this == INT || this == DOUBLE;
    }

    public boolean isBooleanOrNull() {
        return this == BOOLEAN || this == NULL;
    }

    public boolean isNumericOrNull() {
        return isNumeric() || this == NULL;
    }

    public boolean isComparableWith(ValueType other) {
        return this == NULL || other == NULL || this == other || (isNumeric() && other.isNumeric());
    }

    public boolean isAssignableTo(ValueType column) {
        return this == NULL || this == column || (this == INT && column == DOUBLE);
    }
}
