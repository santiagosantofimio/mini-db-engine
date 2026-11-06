package dev.santiagosantofimio.minidb.plan;

import dev.santiagosantofimio.minidb.CatalogException;
import dev.santiagosantofimio.minidb.QueryException;
import dev.santiagosantofimio.minidb.catalog.Catalog;
import dev.santiagosantofimio.minidb.catalog.Table;
import dev.santiagosantofimio.minidb.plan.Expr.ColumnRef;
import dev.santiagosantofimio.minidb.plan.LogicalPlan.Projection;
import dev.santiagosantofimio.minidb.plan.LogicalPlan.Scan;
import dev.santiagosantofimio.minidb.plan.LogicalPlan.Selection;
import dev.santiagosantofimio.minidb.sql.Expression;
import dev.santiagosantofimio.minidb.sql.Expression.ColumnReference;
import dev.santiagosantofimio.minidb.sql.SqlPrinter;
import dev.santiagosantofimio.minidb.sql.Statement.AllColumns;
import dev.santiagosantofimio.minidb.sql.Statement.Identifier;
import dev.santiagosantofimio.minidb.sql.Statement.Select;
import dev.santiagosantofimio.minidb.sql.Statement.SelectExpression;
import dev.santiagosantofimio.minidb.sql.Statement.SelectItem;
import java.util.ArrayList;
import java.util.List;

public final class Planner {

    private final Catalog catalog;

    public Planner(Catalog catalog) {
        this.catalog = catalog;
    }

    public LogicalPlan plan(Select select) {
        Table table = requireTable(select.from().table());
        LogicalPlan plan = new Scan(table, select.from().effectiveName());
        if (select.where().isPresent()) {
            plan = new Selection(plan, predicate(select.where().get(), plan.attributes()));
        }
        return project(plan, select.items());
    }

    private Table requireTable(Identifier name) {
        return catalog.table(name.name())
                .orElseThrow(() -> new CatalogException("no such table: " + name.name(), name.position()));
    }

    private static Expr predicate(Expression where, List<Attribute> scope) {
        Expr predicate = new ExpressionBinder(scope).bind(where);
        if (!predicate.type().isBooleanOrNull()) {
            throw new QueryException(
                    "WHERE needs a BOOLEAN condition, but this expression is " + predicate.type(),
                    where.position());
        }
        return predicate;
    }

    private static Projection project(LogicalPlan input, List<SelectItem> items) {
        List<Attribute> scope = input.attributes();
        ExpressionBinder binder = new ExpressionBinder(scope);
        List<Expr> expressions = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (SelectItem item : items) {
            switch (item) {
                case AllColumns all -> {
                    for (int i = 0; i < scope.size(); i++) {
                        expressions.add(new ColumnRef(i, scope.get(i)));
                        names.add(scope.get(i).name());
                    }
                }
                case SelectExpression selected -> {
                    expressions.add(binder.bind(selected.expression()));
                    names.add(selected.alias().map(Identifier::name).orElseGet(() -> nameOf(selected.expression())));
                }
            }
        }
        return new Projection(input, expressions, names);
    }

    private static String nameOf(Expression expression) {
        return expression instanceof ColumnReference column ? column.name() : SqlPrinter.print(expression);
    }
}
