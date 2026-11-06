package dev.santiagosantofimio.minidb.exec;

import dev.santiagosantofimio.minidb.record.Schema;
import dev.santiagosantofimio.minidb.record.Tuple;
import dev.santiagosantofimio.minidb.record.TupleSerializer;
import dev.santiagosantofimio.minidb.storage.HeapFile;
import dev.santiagosantofimio.minidb.storage.HeapRecord;
import java.util.Iterator;
import java.util.Optional;

public final class SeqScan implements Operator {

    private final HeapFile heap;
    private final Schema schema;
    private Iterator<HeapRecord> records;

    public SeqScan(HeapFile heap, Schema schema) {
        this.heap = heap;
        this.schema = schema;
    }

    @Override
    public void open() {
        records = heap.scan();
    }

    @Override
    public Optional<Tuple> next() {
        if (records == null) {
            throw new IllegalStateException("SeqScan.next() called before open()");
        }
        if (!records.hasNext()) {
            return Optional.empty();
        }
        return Optional.of(TupleSerializer.deserialize(schema, records.next().bytes()));
    }

    @Override
    public void close() {
        records = null;
    }
}
