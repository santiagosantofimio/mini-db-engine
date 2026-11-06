package dev.santiagosantofimio.minidb.exec;

import dev.santiagosantofimio.minidb.plan.Expr;
import dev.santiagosantofimio.minidb.record.Tuple;
import dev.santiagosantofimio.minidb.record.Value;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class Project implements Operator {

    private final Operator input;
    private final List<Expr> expressions;

    public Project(Operator input, List<Expr> expressions) {
        this.input = input;
        this.expressions = List.copyOf(expressions);
    }

    @Override
    public void open() {
        input.open();
    }

    @Override
    public Optional<Tuple> next() {
        return input.next().map(this::project);
    }

    private Tuple project(Tuple row) {
        List<Value> values = new ArrayList<>(expressions.size());
        for (Expr expression : expressions) {
            values.add(Evaluator.evaluate(expression, row));
        }
        return new Tuple(values);
    }

    @Override
    public void close() {
        input.close();
    }
}
