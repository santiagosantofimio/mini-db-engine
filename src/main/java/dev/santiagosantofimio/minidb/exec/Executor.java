package dev.santiagosantofimio.minidb.exec;

import dev.santiagosantofimio.minidb.CatalogException;
import dev.santiagosantofimio.minidb.ConstraintViolationException;
import dev.santiagosantofimio.minidb.QueryException;
import dev.santiagosantofimio.minidb.SourcePosition;
import dev.santiagosantofimio.minidb.catalog.Catalog;
import dev.santiagosantofimio.minidb.catalog.Index;
import dev.santiagosantofimio.minidb.catalog.Table;
import dev.santiagosantofimio.minidb.exec.StatementResult.IndexCreated;
import dev.santiagosantofimio.minidb.exec.StatementResult.Rows;
import dev.santiagosantofimio.minidb.exec.StatementResult.RowsInserted;
import dev.santiagosantofimio.minidb.exec.StatementResult.TableCreated;
import dev.santiagosantofimio.minidb.exec.StatementResult.TableDropped;
import dev.santiagosantofimio.minidb.index.BPlusTree;
import dev.santiagosantofimio.minidb.plan.Attribute;
import dev.santiagosantofimio.minidb.plan.Expr;
import dev.santiagosantofimio.minidb.plan.ExpressionBinder;
import dev.santiagosantofimio.minidb.plan.LogicalPlan;
import dev.santiagosantofimio.minidb.plan.Planner;
import dev.santiagosantofimio.minidb.plan.ValueType;
import dev.santiagosantofimio.minidb.record.Column;
import dev.santiagosantofimio.minidb.record.DataType;
import dev.santiagosantofimio.minidb.record.DataType.VarcharType;
import dev.santiagosantofimio.minidb.record.Schema;
import dev.santiagosantofimio.minidb.record.Tuple;
import dev.santiagosantofimio.minidb.record.TupleSerializer;
import dev.santiagosantofimio.minidb.record.Value;
import dev.santiagosantofimio.minidb.record.Value.IntValue;
import dev.santiagosantofimio.minidb.record.Value.StringValue;
import dev.santiagosantofimio.minidb.sql.Expression;
import dev.santiagosantofimio.minidb.sql.Statement;
import dev.santiagosantofimio.minidb.sql.Statement.ColumnDefinition;
import dev.santiagosantofimio.minidb.sql.Statement.CreateIndex;
import dev.santiagosantofimio.minidb.sql.Statement.CreateTable;
import dev.santiagosantofimio.minidb.sql.Statement.DropTable;
import dev.santiagosantofimio.minidb.sql.Statement.Identifier;
import dev.santiagosantofimio.minidb.sql.Statement.Insert;
import dev.santiagosantofimio.minidb.sql.Statement.Select;
import dev.santiagosantofimio.minidb.storage.HeapFile;
import dev.santiagosantofimio.minidb.storage.Rid;
import dev.santiagosantofimio.minidb.storage.SlottedPage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class Executor {

    private final Catalog catalog;
    private final Planner planner;
    private final PhysicalPlanner physicalPlanner;

    public Executor(Catalog catalog) {
        this.catalog = catalog;
        this.planner = new Planner(catalog);
        this.physicalPlanner = new PhysicalPlanner(catalog);
    }

    public StatementResult execute(Statement statement) {
        return switch (statement) {
            case CreateTable create -> createTable(create);
            case CreateIndex create -> createIndex(create);
            case DropTable drop -> dropTable(drop);
            case Insert insert -> insert(insert);
            case Select select -> select(select);
        };
    }

    private StatementResult createTable(CreateTable create) {
        Identifier name = create.table();
        if (catalog.table(name.name()).isPresent()) {
            throw new CatalogException("table " + name.name() + " already exists", name.position());
        }
        Set<String> seen = new HashSet<>();
        List<Column> columns = new ArrayList<>();
        Optional<Identifier> primaryKey = Optional.empty();
        for (ColumnDefinition definition : create.columns()) {
            Identifier column = definition.name();
            if (!seen.add(column.name())) {
                throw new CatalogException("column " + column.name() + " is defined twice", column.position());
            }
            if (definition.primaryKey()) {
                if (primaryKey.isPresent()) {
                    throw new CatalogException("a table can have only one PRIMARY KEY column", column.position());
                }
                if (!DataType.INT.equals(definition.type())) {
                    throw new CatalogException("PRIMARY KEY columns must be INT, but " + column.name() + " is "
                            + definition.type(), column.position());
                }
                primaryKey = Optional.of(column);
            }
            columns.add(new Column(column.name(), definition.type(), definition.nullable()));
        }
        String primaryIndex = primaryIndexName(name.name());
        if (primaryKey.isPresent() && catalog.index(primaryIndex).isPresent()) {
            throw new CatalogException("index " + primaryIndex + " already exists", primaryKey.get().position());
        }
        catalog.createTable(name.name(), columns);
        primaryKey.ifPresent(column -> catalog.createIndex(primaryIndex, name.name(), column.name(), true));
        return new TableCreated(name.name());
    }

    public static String primaryIndexName(String table) {
        return table + "_pkey";
    }

    private StatementResult createIndex(CreateIndex create) {
        Identifier name = create.name();
        if (catalog.index(name.name()).isPresent()) {
            throw new CatalogException("index " + name.name() + " already exists", name.position());
        }
        Table table = requireTable(create.table());
        Identifier column = create.column();
        int position = indexOf(table, column);
        DataType type = table.schema().column(position).type();
        if (!DataType.INT.equals(type)) {
            throw new CatalogException(
                    "only INT columns can be indexed, but " + column.name() + " is " + type, column.position());
        }
        catalog.createIndex(name.name(), table.name(), column.name(), false);
        return new IndexCreated(name.name(), table.name(), column.name());
    }

    private StatementResult dropTable(DropTable drop) {
        Table table = requireTable(drop.table());
        catalog.dropTable(table.name());
        return new TableDropped(table.name());
    }

    private StatementResult select(Select select) {
        LogicalPlan plan = planner.plan(select);
        Operator operator = physicalPlanner.build(plan);
        List<Tuple> rows = new ArrayList<>();
        operator.open();
        try {
            for (Optional<Tuple> row = operator.next(); row.isPresent(); row = operator.next()) {
                rows.add(row.get());
            }
        } finally {
            operator.close();
        }
        List<Attribute> attributes = plan.attributes();
        return new Rows(attributes.stream().map(Attribute::name).toList(),
                attributes.stream().map(Attribute::type).toList(), rows);
    }

    private StatementResult insert(Insert insert) {
        Table table = requireTable(insert.table());
        Schema schema = table.schema();
        int[] targets = targetColumns(table, insert.columns());
        List<Tuple> tuples = new ArrayList<>(insert.rows().size());
        List<byte[]> records = new ArrayList<>(insert.rows().size());
        for (List<Expression> row : insert.rows()) {
            Tuple tuple = buildTuple(table, targets, row, insert.table().position());
            byte[] record = TupleSerializer.serialize(schema, tuple);
            if (record.length > SlottedPage.MAX_RECORD_SIZE) {
                throw new ConstraintViolationException("a row of " + record.length + " bytes does not fit in a page;"
                        + " the limit is " + SlottedPage.MAX_RECORD_SIZE + " bytes", row.get(0).position());
            }
            tuples.add(tuple);
            records.add(record);
        }
        List<Index> indexes = catalog.indexesOf(table.name());
        for (Index index : indexes) {
            if (index.primary()) {
                rejectDuplicateKeys(index, tuples, insert, targets);
            }
        }
        HeapFile heap = catalog.heapOf(table);
        for (int i = 0; i < records.size(); i++) {
            Rid rid = heap.insert(records.get(i));
            for (Index index : indexes) {
                if (tuples.get(i).get(index.column()) instanceof IntValue key) {
                    catalog.treeOf(index).insert(key.value(), rid);
                }
            }
        }
        return new RowsInserted(table.name(), records.size());
    }

    private void rejectDuplicateKeys(Index index, List<Tuple> tuples, Insert insert, int[] targets) {
        BPlusTree tree = catalog.treeOf(index);
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < tuples.size(); i++) {
            int key = ((IntValue) tuples.get(i).get(index.column())).value();
            if (!seen.add(key) || tree.contains(key)) {
                throw new ConstraintViolationException("duplicate key " + key + " in primary key " + index.name(),
                        positionOf(insert.rows().get(i), targets, index.column(), insert.table().position()));
            }
        }
    }

    private static SourcePosition positionOf(
            List<Expression> row, int[] targets, int column, SourcePosition fallback) {
        for (int i = 0; i < targets.length; i++) {
            if (targets[i] == column) {
                return row.get(i).position();
            }
        }
        return fallback;
    }

    private static int[] targetColumns(Table table, Optional<List<Identifier>> names) {
        Schema schema = table.schema();
        if (names.isEmpty()) {
            int[] all = new int[schema.size()];
            Arrays.setAll(all, i -> i);
            return all;
        }
        List<Identifier> listed = names.get();
        int[] targets = new int[listed.size()];
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < listed.size(); i++) {
            Identifier name = listed.get(i);
            if (!seen.add(name.name())) {
                throw new QueryException("column " + name.name() + " is listed twice", name.position());
            }
            targets[i] = indexOf(table, name);
        }
        return targets;
    }

    private static int indexOf(Table table, Identifier name) {
        List<Column> columns = table.schema().columns();
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equals(name.name())) {
                return i;
            }
        }
        throw new CatalogException("table " + table.name() + " has no column " + name.name(), name.position());
    }

    private static Tuple buildTuple(Table table, int[] targets, List<Expression> row, SourcePosition tablePosition) {
        if (row.size() != targets.length) {
            Expression culprit = row.size() > targets.length ? row.get(targets.length) : row.get(row.size() - 1);
            throw new QueryException("this row has " + row.size() + " values but " + targets.length
                    + " columns are being inserted", culprit.position());
        }
        Schema schema = table.schema();
        Value[] values = new Value[schema.size()];
        Arrays.fill(values, Value.NULL);
        ExpressionBinder binder = new ExpressionBinder(List.of());
        for (int i = 0; i < targets.length; i++) {
            Expression source = row.get(i);
            Column column = schema.column(targets[i]);
            values[targets[i]] = coerce(evaluateConstant(binder, source, column), column, source.position());
        }
        for (int i = 0; i < values.length; i++) {
            if (values[i].isNull() && !schema.column(i).nullable()) {
                throw new ConstraintViolationException(
                        "column " + schema.column(i).name() + " cannot be NULL", tablePosition);
            }
        }
        return new Tuple(Arrays.asList(values));
    }

    private static Value evaluateConstant(ExpressionBinder binder, Expression source, Column column) {
        Expr bound = binder.bind(source);
        ValueType target = ValueType.of(column.type());
        if (!bound.type().isAssignableTo(target)) {
            throw new QueryException("column " + column.name() + " is " + column.type() + ", but this value is "
                    + bound.type(), source.position());
        }
        return Evaluator.evaluate(bound);
    }

    private static Value coerce(Value value, Column column, SourcePosition position) {
        if (value.isNull()) {
            if (!column.nullable()) {
                throw new ConstraintViolationException("column " + column.name() + " cannot be NULL", position);
            }
            return value;
        }
        if (column.type() instanceof VarcharType varchar && value instanceof StringValue string) {
            int length = string.value().codePointCount(0, string.value().length());
            if (length > varchar.maxLength()) {
                throw new ConstraintViolationException("column " + column.name() + " is " + varchar + ", but this"
                        + " value has " + length + " characters", position);
            }
        }
        if (ValueType.of(column.type()) == ValueType.DOUBLE && value instanceof IntValue integer) {
            return Value.of((double) integer.value());
        }
        return value;
    }

    private Table requireTable(Identifier name) {
        return catalog.table(name.name())
                .orElseThrow(() -> new CatalogException("no such table: " + name.name(), name.position()));
    }
}
