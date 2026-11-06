package dev.santiagosantofimio.minidb.exec;

import dev.santiagosantofimio.minidb.plan.Expr;
import dev.santiagosantofimio.minidb.record.Tuple;
import java.util.Optional;

public final class Filter implements Operator {

    private final Operator input;
    private final Expr predicate;

    public Filter(Operator input, Expr predicate) {
        this.input = input;
        this.predicate = predicate;
    }

    @Override
    public void open() {
        input.open();
    }

    @Override
    public Optional<Tuple> next() {
        for (Optional<Tuple> row = input.next(); row.isPresent(); row = input.next()) {
            if (Evaluator.isTrue(Evaluator.evaluate(predicate, row.get()))) {
                return row;
            }
        }
        return Optional.empty();
    }

    @Override
    public void close() {
        input.close();
    }
}
