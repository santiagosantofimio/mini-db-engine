package dev.santiagosantofimio.minidb.catalog;

import dev.santiagosantofimio.minidb.CatalogException;
import dev.santiagosantofimio.minidb.ConstraintViolationException;
import dev.santiagosantofimio.minidb.index.BPlusTree;
import dev.santiagosantofimio.minidb.record.Column;
import dev.santiagosantofimio.minidb.record.DataType;
import dev.santiagosantofimio.minidb.record.Schema;
import dev.santiagosantofimio.minidb.record.TupleSerializer;
import dev.santiagosantofimio.minidb.record.Value;
import dev.santiagosantofimio.minidb.record.Value.IntValue;
import dev.santiagosantofimio.minidb.storage.HeapFile;
import dev.santiagosantofimio.minidb.storage.HeapRecord;
import dev.santiagosantofimio.minidb.storage.PageType;
import dev.santiagosantofimio.minidb.storage.Rid;
import dev.santiagosantofimio.minidb.storage.SlottedPage;
import dev.santiagosantofimio.minidb.storage.Storage;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

public final class Catalog {

    private final Storage storage;
    private final HeapFile records;
    private final int btreeOrder;
    private final Map<String, Entry<Table>> tables = new TreeMap<>();
    private final Map<String, Entry<Index>> indexes = new TreeMap<>();

    private record Entry<T extends Definition>(T definition, Rid rid) {
    }

    private Catalog(Storage storage, HeapFile records, int btreeOrder) {
        this.storage = storage;
        this.records = records;
        this.btreeOrder = btreeOrder;
    }

    public static Catalog open(Storage storage, int btreeOrder) {
        int root = storage.catalogRoot();
        HeapFile records;
        if (root == Storage.NO_PAGE) {
            records = HeapFile.create(storage, PageType.CATALOG);
            storage.setCatalogRoot(records.firstPageId());
        } else {
            records = HeapFile.open(storage, root);
        }
        Catalog catalog = new Catalog(storage, records, btreeOrder);
        catalog.load();
        return catalog;
    }

    private void load() {
        Iterator<HeapRecord> scan = records.scan();
        while (scan.hasNext()) {
            HeapRecord record = scan.next();
            switch (CatalogRecords.decode(record.bytes())) {
                case Table table -> tables.put(table.name(), new Entry<>(table, record.rid()));
                case Index index -> indexes.put(index.name(), new Entry<>(index, record.rid()));
            }
        }
    }

    public int btreeOrder() {
        return btreeOrder;
    }

    public Optional<Table> table(String name) {
        return Optional.ofNullable(tables.get(name)).map(Entry::definition);
    }

    public List<Table> tables() {
        return tables.values().stream().map(Entry::definition).toList();
    }

    public Optional<Index> index(String name) {
        return Optional.ofNullable(indexes.get(name)).map(Entry::definition);
    }

    public List<Index> indexes() {
        return indexes.values().stream().map(Entry::definition).toList();
    }

    public List<Index> indexesOf(String table) {
        return indexes().stream().filter(index -> index.table().equals(table)).toList();
    }

    public Table createTable(String name, List<Column> columns) {
        if (tables.containsKey(name)) {
            throw new CatalogException("table " + name + " already exists");
        }
        Schema schema = new Schema(columns);
        rejectDuplicateColumns(name, schema);
        int recordSize = CatalogRecords.encode(new Table(name, schema, Storage.NO_PAGE)).length;
        if (recordSize > SlottedPage.MAX_RECORD_SIZE) {
            throw new CatalogException("the definition of table " + name + " is too large to store");
        }
        HeapFile heap = HeapFile.create(storage);
        Table table = new Table(name, schema, heap.firstPageId());
        Rid rid = records.insert(CatalogRecords.encode(table));
        tables.put(name, new Entry<>(table, rid));
        return table;
    }

    public Index createIndex(String name, String tableName, String columnName, boolean primary) {
        if (indexes.containsKey(name)) {
            throw new CatalogException("index " + name + " already exists");
        }
        Table table = table(tableName).orElseThrow(() -> new CatalogException("no such table: " + tableName));
        int column = columnIndex(table, columnName);
        if (table.schema().column(column).type() != DataType.INT) {
            throw new CatalogException("only INT columns can be indexed, but " + columnName + " is "
                    + table.schema().column(column).type());
        }
        if (primary && indexesOf(tableName).stream().anyMatch(Index::primary)) {
            throw new CatalogException("table " + tableName + " already has a primary key");
        }
        BPlusTree tree = BPlusTree.create(storage, btreeOrder);
        try {
            fill(tree, table, column, primary, name);
        } catch (RuntimeException e) {
            tree.drop();
            throw e;
        }
        Index index = new Index(name, tableName, column, tree.rootPageId(), primary);
        Rid rid = records.insert(CatalogRecords.encode(index));
        indexes.put(name, new Entry<>(index, rid));
        return index;
    }

    private void fill(BPlusTree tree, Table table, int column, boolean unique, String indexName) {
        Iterator<HeapRecord> rows = heapOf(table).scan();
        while (rows.hasNext()) {
            HeapRecord row = rows.next();
            Value key = TupleSerializer.deserialize(table.schema(), row.bytes()).get(column);
            if (key instanceof IntValue integer) {
                if (unique && tree.contains(integer.value())) {
                    throw new ConstraintViolationException(
                            "cannot create " + indexName + ": key " + integer.value() + " appears more than once");
                }
                tree.insert(integer.value(), row.rid());
            }
        }
    }

    public void dropTable(String name) {
        Entry<Table> entry = tables.get(name);
        if (entry == null) {
            throw new CatalogException("no such table: " + name);
        }
        for (Index index : indexesOf(name)) {
            Entry<Index> indexEntry = indexes.remove(index.name());
            records.delete(indexEntry.rid());
            treeOf(index).drop();
        }
        records.delete(entry.rid());
        heapOf(entry.definition()).drop();
        tables.remove(name);
    }

    public HeapFile heapOf(Table table) {
        return HeapFile.open(storage, table.firstPageId());
    }

    public BPlusTree treeOf(Index index) {
        return new BPlusTree(storage, index.rootPageId(), btreeOrder);
    }

    private static int columnIndex(Table table, String columnName) {
        List<Column> columns = table.schema().columns();
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equals(columnName)) {
                return i;
            }
        }
        throw new CatalogException("table " + table.name() + " has no column " + columnName);
    }

    private static void rejectDuplicateColumns(String table, Schema schema) {
        Set<String> names = new HashSet<>();
        for (Column column : schema.columns()) {
            if (!names.add(column.name())) {
                throw new CatalogException("table " + table + " has two columns named " + column.name());
            }
        }
    }
}
