package dev.santiagosantofimio.minidb.storage;

import static dev.santiagosantofimio.minidb.storage.Pager.PAGE_SIZE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.santiagosantofimio.minidb.StorageException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BufferPoolTest {

    private static final int PAGES_ON_DISK = 10;

    @TempDir
    Path directory;

    private Pager pager;

    @BeforeEach
    void writePagesThatStoreTheirOwnId() {
        pager = Pager.open(directory.resolve("pool.db"));
        for (int pageId = 0; pageId < PAGES_ON_DISK; pageId++) {
            pager.write(pageId, pageStoring(pageId));
        }
    }

    @AfterEach
    void closePager() {
        pager.close();
    }

    private static byte[] pageStoring(int value) {
        return ByteBuffer.allocate(PAGE_SIZE).putInt(0, value).array();
    }

    private int valueOnDisk(int pageId) {
        byte[] page = new byte[PAGE_SIZE];
        pager.read(pageId, page);
        return ByteBuffer.wrap(page).getInt(0);
    }

    private static int touch(BufferPool pool, int pageId) {
        try (PageHandle page = pool.fetch(pageId)) {
            return page.getInt(0);
        }
    }

    @Test
    void secondFetchOfAPageIsAHit() {
        BufferPool pool = new BufferPool(pager, 4);

        assertEquals(3, touch(pool, 3));
        assertEquals(3, touch(pool, 3));

        assertEquals(new BufferPoolStats(1, 1, 0), pool.stats());
        assertEquals(0.5, pool.stats().hitRatio());
    }

    @Test
    void evictsTheLeastRecentlyUsedPage() {
        BufferPool pool = new BufferPool(pager, 3);
        touch(pool, 1);
        touch(pool, 2);
        touch(pool, 3);
        touch(pool, 1);

        touch(pool, 4);

        assertEquals(1, pool.stats().evictions());
        long misses = pool.stats().misses();
        touch(pool, 1);
        touch(pool, 3);
        touch(pool, 4);
        assertEquals(misses, pool.stats().misses());
        touch(pool, 2);
        assertEquals(misses + 1, pool.stats().misses());
    }

    @Test
    void pinnedPagesAreNeverEvicted() {
        BufferPool pool = new BufferPool(pager, 2);

        try (PageHandle pinned = pool.fetch(1)) {
            for (int pageId = 2; pageId < PAGES_ON_DISK; pageId++) {
                touch(pool, pageId);
            }
            assertEquals(1, pinned.getInt(0));
        }

        long hits = pool.stats().hits();
        touch(pool, 1);
        assertEquals(hits + 1, pool.stats().hits());
    }

    @Test
    void fetchFailsWhenEveryPageIsPinned() {
        BufferPool pool = new BufferPool(pager, 2);

        try (PageHandle first = pool.fetch(1); PageHandle second = pool.fetch(2)) {
            StorageException error = assertThrows(StorageException.class, () -> pool.fetch(3));

            assertTrue(error.getMessage().contains("all 2 pages are pinned"));
            assertEquals(1, first.getInt(0));
            assertEquals(2, second.getInt(0));
        }
        assertEquals(0, pool.pinnedPageCount());
    }

    @Test
    void dirtyPageIsWrittenBackWhenEvicted() {
        BufferPool pool = new BufferPool(pager, 1);
        try (PageHandle page = pool.fetch(5)) {
            page.putInt(0, 500);
        }

        touch(pool, 6);

        assertEquals(500, valueOnDisk(5));
        assertEquals(500, touch(pool, 5));
    }

    @Test
    void cleanPageIsNotWrittenBackWhenEvicted() {
        BufferPool pool = new BufferPool(pager, 1);
        touch(pool, 5);
        pager.write(5, pageStoring(99));

        touch(pool, 6);

        assertEquals(99, valueOnDisk(5));
    }

    @Test
    void flushAllWritesDirtyPagesAndKeepsThemCached() {
        BufferPool pool = new BufferPool(pager, 4);
        try (PageHandle page = pool.fetch(2)) {
            page.putInt(0, 200);
        }

        pool.flushAll();

        assertEquals(200, valueOnDisk(2));
        assertEquals(1, pool.cachedPageCount());
    }

    @Test
    void pagesAreStoredBigEndian() {
        BufferPool pool = new BufferPool(pager, 4);
        try (PageHandle page = pool.fetch(0)) {
            page.putInt(8, 0x0A0B0C0D);
            page.putUnsignedShort(12, 0xFFEE);
        }

        pool.flushAll();

        byte[] raw = new byte[PAGE_SIZE];
        pager.read(0, raw);
        assertArrayEquals(
                new byte[] {0x0A, 0x0B, 0x0C, 0x0D, (byte) 0xFF, (byte) 0xEE},
                Arrays.copyOfRange(raw, 8, 14));
    }

    @Test
    void handleCannotBeUsedAfterItIsClosed() {
        BufferPool pool = new BufferPool(pager, 4);
        PageHandle page = pool.fetch(1);
        page.close();

        assertThrows(IllegalStateException.class, () -> page.getInt(0));
        assertThrows(IllegalStateException.class, () -> page.putInt(0, 1));
    }

    @Test
    void closingAHandleTwiceUnpinsOnlyOnce() {
        BufferPool pool = new BufferPool(pager, 4);
        PageHandle first = pool.fetch(1);
        PageHandle second = pool.fetch(1);

        first.close();
        first.close();
        assertEquals(1, pool.pinnedPageCount());

        second.close();
        assertEquals(0, pool.pinnedPageCount());
    }

    @Test
    void failedReadLeavesNothingCachedOrPinned() {
        BufferPool pool = new BufferPool(pager, 4);

        assertThrows(StorageException.class, () -> pool.fetch(PAGES_ON_DISK));

        assertEquals(0, pool.cachedPageCount());
        assertEquals(0, pool.pinnedPageCount());
    }

    @Test
    void newPagesStartZeroedAndDirty() {
        BufferPool pool = new BufferPool(pager, 4);
        try (PageHandle page = pool.fetchNew(PAGES_ON_DISK)) {
            assertEquals(0, page.getInt(0));
        }

        pool.flushAll();

        assertEquals((PAGES_ON_DISK + 1L) * PAGE_SIZE, pager.sizeInBytes());
    }

    @Test
    void rejectsAnEmptyPool() {
        assertThrows(IllegalArgumentException.class, () -> new BufferPool(pager, 0));
    }
}
