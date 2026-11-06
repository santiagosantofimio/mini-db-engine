package dev.santiagosantofimio.minidb.record;

import java.util.List;

public record Tuple(List<Value> values) {

    public Tuple {
        values = List.copyOf(values);
    }

    public static Tuple of(Value... values) {
        return new Tuple(List.of(values));
    }

    public int size() {
        return values.size();
    }

    public Value get(int index) {
        return values.get(index);
    }
}
