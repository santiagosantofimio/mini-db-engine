package dev.santiagosantofimio.minidb.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.santiagosantofimio.minidb.StorageException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.OptionalInt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlottedPageTest {

    @TempDir
    Path directory;

    private Storage storage;
    private PageHandle handle;
    private SlottedPage page;

    @BeforeEach
    void createEmptyHeapPage() {
        storage = Storage.open(directory.resolve("slotted.db"), 4);
        handle = storage.allocate(PageType.HEAP);
        page = SlottedPage.initialize(handle);
    }

    @AfterEach
    void closeStorage() {
        handle.close();
        assertEquals(0, storage.pinnedPageCount());
        storage.close();
    }

    private static byte[] record(int length, int fill) {
        byte[] record = new byte[length];
        Arrays.fill(record, (byte) fill);
        return record;
    }

    private int insert(byte[] record) {
        return page.insert(record).orElseThrow();
    }

    @Test
    void insertedRecordsReadBackBySlot() {
        assertEquals(0, insert(record(10, 1)));
        assertEquals(1, insert(record(20, 2)));

        assertEquals(2, page.slotCount());
        assertArrayEquals(record(10, 1), page.read(0));
        assertArrayEquals(record(20, 2), page.read(1));
    }

    @Test
    void deletedSlotIsEmptyAndGetsReused() {
        insert(record(10, 1));
        insert(record(10, 2));

        page.delete(0);

        assertFalse(page.isLive(0));
        assertThrows(IllegalArgumentException.class, () -> page.read(0));
        assertEquals(0, insert(record(30, 3)));
        assertEquals(2, page.slotCount());
        assertArrayEquals(record(10, 2), page.read(1));
    }

    @Test
    void compactsFragmentedSpaceWithoutMovingSlots() {
        int slots = 0;
        while (page.insert(record(100, slots)).isPresent()) {
            slots++;
        }
        for (int slot = 0; slot < slots; slot += 2) {
            page.delete(slot);
        }

        OptionalInt large = page.insert(record(250, 99));

        assertTrue(large.isPresent());
        assertArrayEquals(record(250, 99), page.read(large.getAsInt()));
        for (int slot = 1; slot < slots; slot += 2) {
            assertArrayEquals(record(100, slot), page.read(slot));
        }
    }

    @Test
    void fullPageRejectsInsertWithoutChangingAnything() {
        insert(record(SlottedPage.MAX_RECORD_SIZE - 10, 1));

        assertTrue(page.insert(record(20, 2)).isEmpty());
        assertEquals(1, page.slotCount());
    }

    @Test
    void largestRecordFillsAnEmptyPageExactly() {
        insert(record(SlottedPage.MAX_RECORD_SIZE, 7));

        assertEquals(0, page.totalFreeSpace());
        assertTrue(page.insert(new byte[0]).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> page.insert(record(SlottedPage.MAX_RECORD_SIZE + 1, 0)));
    }

    @Test
    void nextPageLinkIsStoredInTheHeader() {
        assertEquals(Storage.NO_PAGE, page.nextPageId());

        page.setNextPageId(9);

        assertEquals(9, new SlottedPage(handle).nextPageId());
    }

    @Test
    void refusesToWrapPagesOfAnotherType() {
        try (PageHandle leaf = storage.allocate(PageType.BTREE_LEAF)) {
            assertThrows(StorageException.class, () -> new SlottedPage(leaf));
        }
    }

    @Test
    void rejectsSlotsThatDoNotExist() {
        insert(record(4, 1));

        assertThrows(IllegalArgumentException.class, () -> page.read(1));
        assertThrows(IllegalArgumentException.class, () -> page.delete(-1));
    }
}
