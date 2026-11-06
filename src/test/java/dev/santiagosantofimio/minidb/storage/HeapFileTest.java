package dev.santiagosantofimio.minidb.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.santiagosantofimio.minidb.ConstraintViolationException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeapFileTest {

    @TempDir
    Path directory;

    private Storage open(int bufferPages) {
        return Storage.open(directory.resolve("heap.db"), bufferPages);
    }

    private static byte[] record(int id, int length) {
        ByteBuffer record = ByteBuffer.allocate(length);
        record.putInt(0, id);
        for (int i = Integer.BYTES; i < length; i++) {
            record.put(i, (byte) (id + i));
        }
        return record.array();
    }

    private static List<HeapRecord> scanAll(HeapFile heap) {
        List<HeapRecord> records = new ArrayList<>();
        heap.scan().forEachRemaining(records::add);
        return records;
    }

    @Test
    void recordsSpanSeveralPagesAndScanInInsertionOrder() {
        try (Storage storage = open(16)) {
            HeapFile heap = HeapFile.create(storage);
            List<Rid> rids = new ArrayList<>();
            for (int id = 0; id < 1_000; id++) {
                rids.add(heap.insert(record(id, 50)));
            }

            List<HeapRecord> scanned = scanAll(heap);

            assertTrue(rids.stream().map(Rid::pageId).distinct().count() > 10);
            assertEquals(1_000, scanned.size());
            for (int id = 0; id < 1_000; id++) {
                assertEquals(rids.get(id), scanned.get(id).rid());
                assertArrayEquals(record(id, 50), scanned.get(id).bytes());
            }
            assertEquals(0, storage.pinnedPageCount());
        }
    }

    @Test
    void deletedRecordsDisappearFromReadsAndScans() {
        try (Storage storage = open(8)) {
            HeapFile heap = HeapFile.create(storage);
            Rid kept = heap.insert(record(1, 10));
            Rid deleted = heap.insert(record(2, 10));

            assertTrue(heap.delete(deleted));

            assertTrue(heap.read(deleted).isEmpty());
            assertFalse(heap.delete(deleted));
            assertArrayEquals(record(1, 10), heap.read(kept).orElseThrow());
            assertEquals(List.of(kept), scanAll(heap).stream().map(HeapRecord::rid).toList());
        }
    }

    @Test
    void spaceFreedByDeletesIsReused() {
        try (Storage storage = open(8)) {
            HeapFile heap = HeapFile.create(storage);
            List<Rid> firstPage = new ArrayList<>();
            Rid rid;
            for (int id = 0; (rid = heap.insert(record(id, 200))).pageId() == heap.firstPageId(); id++) {
                firstPage.add(rid);
            }
            firstPage.forEach(heap::delete);

            Rid reused = heap.insert(record(500, 200));

            assertEquals(heap.firstPageId(), reused.pageId());
        }
    }

    @Test
    void contentsSurviveReopening() {
        int firstPageId;
        List<Rid> rids = new ArrayList<>();
        try (Storage storage = open(8)) {
            HeapFile heap = HeapFile.create(storage);
            firstPageId = heap.firstPageId();
            for (int id = 0; id < 300; id++) {
                rids.add(heap.insert(record(id, 120)));
            }
        }

        try (Storage storage = open(8)) {
            HeapFile heap = HeapFile.open(storage, firstPageId);
            List<HeapRecord> scanned = scanAll(heap);

            assertEquals(rids, scanned.stream().map(HeapRecord::rid).toList());
            assertArrayEquals(record(299, 120), heap.read(rids.get(299)).orElseThrow());
        }
    }

    @Test
    void tinyBufferPoolEvictsPagesWithoutLosingRecords() {
        try (Storage storage = open(3)) {
            HeapFile heap = HeapFile.create(storage);
            List<Rid> rids = new ArrayList<>();
            for (int id = 0; id < 2_000; id++) {
                rids.add(heap.insert(record(id, 64)));
            }

            for (int id = 0; id < 2_000; id++) {
                assertArrayEquals(record(id, 64), heap.read(rids.get(id)).orElseThrow());
            }
            assertEquals(2_000, scanAll(heap).size());
            assertTrue(storage.stats().evictions() > 0);
            assertEquals(0, storage.pinnedPageCount());
        }
    }

    @Test
    void randomInsertsAndDeletesMatchAReferenceMap() {
        Random random = new Random(20_261_105L);
        Map<Rid, byte[]> expected = new HashMap<>();
        List<Rid> live = new ArrayList<>();

        try (Storage storage = open(8)) {
            HeapFile heap = HeapFile.create(storage);
            for (int operation = 0; operation < 10_000; operation++) {
                if (live.isEmpty() || random.nextInt(10) < 7) {
                    byte[] bytes = record(operation, Integer.BYTES + random.nextInt(300));
                    Rid rid = heap.insert(bytes);
                    assertFalse(expected.containsKey(rid));
                    expected.put(rid, bytes);
                    live.add(rid);
                } else {
                    int index = random.nextInt(live.size());
                    Rid rid = live.get(index);
                    live.set(index, live.get(live.size() - 1));
                    live.remove(live.size() - 1);
                    assertTrue(heap.delete(rid));
                    expected.remove(rid);
                }
            }

            List<HeapRecord> scanned = scanAll(heap);
            assertEquals(expected.size(), scanned.size());
            for (HeapRecord record : scanned) {
                assertArrayEquals(expected.get(record.rid()), record.bytes());
            }
            assertTrue(storage.stats().evictions() > 0);
            assertEquals(0, storage.pinnedPageCount());
        }
    }

    @Test
    void abandonedScanLeavesNoPinnedPages() {
        try (Storage storage = open(8)) {
            HeapFile heap = HeapFile.create(storage);
            for (int id = 0; id < 200; id++) {
                heap.insert(record(id, 100));
            }

            Iterator<HeapRecord> scan = heap.scan();
            scan.next();

            assertEquals(0, storage.pinnedPageCount());
        }
    }

    @Test
    void emptyHeapScansNothing() {
        try (Storage storage = open(8)) {
            assertFalse(HeapFile.create(storage).scan().hasNext());
        }
    }

    @Test
    void rowsLargerThanAPageAreRejected() {
        try (Storage storage = open(8)) {
            HeapFile heap = HeapFile.create(storage);

            assertThrows(
                    ConstraintViolationException.class,
                    () -> heap.insert(new byte[SlottedPage.MAX_RECORD_SIZE + 1]));
            assertEquals(0, storage.pinnedPageCount());
        }
    }
}
