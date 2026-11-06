package dev.santiagosantofimio.minidb.catalog;

import dev.santiagosantofimio.minidb.CatalogException;
import dev.santiagosantofimio.minidb.record.Column;
import dev.santiagosantofimio.minidb.record.Schema;
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
    private final Map<String, Entry> tables = new TreeMap<>();

    private record Entry(Table table, Rid rid) {
    }

    private Catalog(Storage storage, HeapFile records) {
        this.storage = storage;
        this.records = records;
    }

    public static Catalog open(Storage storage) {
        int root = storage.catalogRoot();
        HeapFile records;
        if (root == Storage.NO_PAGE) {
            records = HeapFile.create(storage, PageType.CATALOG);
            storage.setCatalogRoot(records.firstPageId());
        } else {
            records = HeapFile.open(storage, root);
        }
        Catalog catalog = new Catalog(storage, records);
        catalog.load();
        return catalog;
    }

    private void load() {
        Iterator<HeapRecord> scan = records.scan();
        while (scan.hasNext()) {
            HeapRecord record = scan.next();
            Table table = CatalogRecords.decode(record.bytes());
            tables.put(table.name(), new Entry(table, record.rid()));
        }
    }

    public Optional<Table> table(String name) {
        return Optional.ofNullable(tables.get(name)).map(Entry::table);
    }

    public List<Table> tables() {
        return tables.values().stream().map(Entry::table).toList();
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
        tables.put(name, new Entry(table, rid));
        return table;
    }

    public void dropTable(String name) {
        Entry entry = tables.get(name);
        if (entry == null) {
            throw new CatalogException("no such table: " + name);
        }
        records.delete(entry.rid());
        HeapFile.open(storage, entry.table().firstPageId()).drop();
        tables.remove(name);
    }

    public HeapFile heapOf(Table table) {
        return HeapFile.open(storage, table.firstPageId());
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
