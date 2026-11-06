package dev.santiagosantofimio.minidb;

import dev.santiagosantofimio.minidb.catalog.Catalog;
import dev.santiagosantofimio.minidb.catalog.Table;
import dev.santiagosantofimio.minidb.exec.Executor;
import dev.santiagosantofimio.minidb.exec.StatementResult;
import dev.santiagosantofimio.minidb.sql.Parser;
import dev.santiagosantofimio.minidb.sql.Statement;
import dev.santiagosantofimio.minidb.storage.BufferPoolStats;
import dev.santiagosantofimio.minidb.storage.Storage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class Database implements AutoCloseable {

    public static final int DEFAULT_BUFFER_PAGES = 1024;
    public static final int MIN_BUFFER_PAGES = 16;

    private final Storage storage;
    private final Catalog catalog;
    private final Executor executor;
    private final int bufferPages;

    private Database(Storage storage, Catalog catalog, int bufferPages) {
        this.storage = storage;
        this.catalog = catalog;
        this.executor = new Executor(catalog);
        this.bufferPages = bufferPages;
    }

    public static Database open(Path path) {
        return open(path, DEFAULT_BUFFER_PAGES);
    }

    public static Database open(Path path, int bufferPages) {
        if (bufferPages < MIN_BUFFER_PAGES) {
            throw new IllegalArgumentException(
                    "the buffer pool needs at least " + MIN_BUFFER_PAGES + " pages, got " + bufferPages);
        }
        Storage storage = Storage.open(path, bufferPages);
        try {
            Catalog catalog = Catalog.open(storage);
            storage.flush();
            return new Database(storage, catalog, bufferPages);
        } catch (RuntimeException e) {
            try {
                storage.close();
            } catch (RuntimeException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    public List<Statement> parse(String sql) {
        return Parser.parse(sql);
    }

    public StatementResult execute(Statement statement) {
        try {
            return executor.execute(statement);
        } finally {
            storage.flush();
        }
    }

    public List<StatementResult> execute(String sql) {
        List<StatementResult> results = new ArrayList<>();
        for (Statement statement : parse(sql)) {
            results.add(execute(statement));
        }
        return results;
    }

    public List<Table> tables() {
        return catalog.tables();
    }

    public Optional<Table> table(String name) {
        return catalog.table(name);
    }

    public BufferPoolStats stats() {
        return storage.stats();
    }

    public int bufferPages() {
        return bufferPages;
    }

    public int pinnedPageCount() {
        return storage.pinnedPageCount();
    }

    public int pageCount() {
        return storage.pageCount();
    }

    @Override
    public void close() {
        storage.close();
    }
}
