package dev.santiagosantofimio.minidb.exec;

import dev.santiagosantofimio.minidb.catalog.Catalog;
import dev.santiagosantofimio.minidb.plan.LogicalPlan;
import dev.santiagosantofimio.minidb.plan.LogicalPlan.Projection;
import dev.santiagosantofimio.minidb.plan.LogicalPlan.Scan;
import dev.santiagosantofimio.minidb.plan.LogicalPlan.Selection;

public final class PhysicalPlanner {

    private final Catalog catalog;

    public PhysicalPlanner(Catalog catalog) {
        this.catalog = catalog;
    }

    public Operator build(LogicalPlan plan) {
        return switch (plan) {
            case Scan scan -> new SeqScan(catalog.heapOf(scan.table()), scan.table().schema());
            case Selection selection -> new Filter(build(selection.input()), selection.predicate());
            case Projection projection -> new Project(build(projection.input()), projection.expressions());
        };
    }
}
