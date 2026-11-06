package dev.santiagosantofimio.minidb.exec;

import dev.santiagosantofimio.minidb.record.Tuple;
import java.util.Optional;

public interface Operator {

    void open();

    Optional<Tuple> next();

    void close();
}
