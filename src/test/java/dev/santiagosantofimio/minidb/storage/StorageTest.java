package dev.santiagosantofimio.minidb.storage;

import static dev.santiagosantofimio.minidb.storage.Pager.PAGE_SIZE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.santiagosantofimio.minidb.StorageException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageTest {

    private static final int BUFFER_PAGES = 8;

    @TempDir
    Path directory;

    private Path file() {
        return directory.resolve("storage.db");
    }

    private Storage open() {
        return Storage.open(file(), BUFFER_PAGES);
    }

    private static int allocate(Storage storage, PageType type) {
        try (PageHandle page = storage.allocate(type)) {
            return page.pageId();
        }
    }

    private static PageType typeOf(Storage storage, int pageId) {
        try (PageHandle page = storage.fetch(pageId)) {
            return PageType.readFrom(page);
        }
    }

    @Test
    void newDatabaseStartsWithOnlyTheHeaderPage() throws IOException {
        try (Storage storage = open()) {
            assertEquals(1, storage.pageCount());
            assertEquals(Storage.NO_PAGE, storage.catalogRoot());
        }

        byte[] bytes = Files.readAllBytes(file());
        ByteBuffer header = ByteBuffer.wrap(bytes);
        assertEquals(PAGE_SIZE, bytes.length);
        assertArrayEquals("MINIDB01".getBytes(StandardCharsets.US_ASCII), Arrays.copyOf(bytes, 8));
        assertEquals(HeaderPage.FORMAT_VERSION, header.getInt(8));
        assertEquals(PAGE_SIZE, header.getInt(12));
        assertEquals(1, header.getInt(16));
        assertEquals(Storage.NO_PAGE, header.getInt(20));
        assertEquals(Storage.NO_PAGE, header.getInt(24));
    }

    @Test
    void rejectsFilesThatAreNotMiniDbDatabases() throws IOException {
        byte[] other = new byte[PAGE_SIZE];
        byte[] signature = "SQLite format 3".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(signature, 0, other, 0, signature.length);
        Files.write(file(), other);

        StorageException error = assertThrows(StorageException.class, this::open);

        assertTrue(error.getMessage().endsWith("is not a minidb database"));
    }

    @Test
    void rejectsFilesWithAPartialPage() throws IOException {
        Files.write(file(), new byte[100]);

        assertThrows(StorageException.class, this::open);
    }

    @Test
    void rejectsAnUnknownFormatVersion() throws IOException {
        open().close();
        byte[] bytes = Files.readAllBytes(file());
        ByteBuffer.wrap(bytes).putInt(8, 2);
        Files.write(file(), bytes);

        StorageException error = assertThrows(StorageException.class, this::open);

        assertTrue(error.getMessage().contains("format version 2"));
    }

    @Test
    void failedOpenReleasesTheFile() throws IOException {
        Files.write(file(), new byte[100]);
        assertThrows(StorageException.class, this::open);
        Files.delete(file());

        try (Storage storage = open()) {
            assertEquals(1, storage.pageCount());
        }
    }

    @Test
    void allocatedPagesKeepTheirTypeAcrossReopening() {
        try (Storage storage = open()) {
            assertEquals(1, allocate(storage, PageType.HEAP));
            assertEquals(2, allocate(storage, PageType.BTREE_LEAF));
        }

        try (Storage storage = open()) {
            assertEquals(3, storage.pageCount());
            assertEquals(PageType.HEAP, typeOf(storage, 1));
            assertEquals(PageType.BTREE_LEAF, typeOf(storage, 2));
        }
    }

    @Test
    void freedPagesAreReusedBeforeTheFileGrows() {
        try (Storage storage = open()) {
            allocate(storage, PageType.HEAP);
            allocate(storage, PageType.HEAP);
            allocate(storage, PageType.HEAP);

            storage.free(2);
            storage.free(3);

            assertEquals(PageType.FREE, typeOf(storage, 2));
            assertEquals(3, allocate(storage, PageType.CATALOG));
            assertEquals(2, allocate(storage, PageType.CATALOG));
            assertEquals(4, allocate(storage, PageType.CATALOG));
            assertEquals(5, storage.pageCount());
        }
    }

    @Test
    void reusedPagesComeBackZeroed() {
        try (Storage storage = open()) {
            try (PageHandle page = storage.allocate(PageType.HEAP)) {
                page.putInt(100, 42);
            }
            storage.free(1);

            try (PageHandle page = storage.allocate(PageType.BTREE_INTERNAL)) {
                assertEquals(1, page.pageId());
                assertEquals(PageType.BTREE_INTERNAL, PageType.readFrom(page));
                assertEquals(0, page.getInt(1));
                assertEquals(0, page.getInt(100));
            }
        }
    }

    @Test
    void freeListSurvivesReopening() {
        try (Storage storage = open()) {
            allocate(storage, PageType.HEAP);
            allocate(storage, PageType.HEAP);
            storage.free(1);
        }

        try (Storage storage = open()) {
            assertEquals(1, allocate(storage, PageType.HEAP));
            assertEquals(3, allocate(storage, PageType.HEAP));
        }
    }

    @Test
    void refusesToFreeTheHeaderUnknownPagesOrFreePages() {
        try (Storage storage = open()) {
            allocate(storage, PageType.HEAP);
            storage.free(1);

            assertThrows(IllegalArgumentException.class, () -> storage.free(0));
            assertThrows(IllegalArgumentException.class, () -> storage.free(7));
            assertThrows(IllegalStateException.class, () -> storage.free(1));
            assertThrows(IllegalArgumentException.class, () -> storage.allocate(PageType.FREE));
            assertEquals(0, storage.pinnedPageCount());
        }
    }

    @Test
    void catalogRootSurvivesReopening() {
        try (Storage storage = open()) {
            storage.setCatalogRoot(allocate(storage, PageType.CATALOG));
        }

        try (Storage storage = open()) {
            assertEquals(1, storage.catalogRoot());
        }
    }

    @Test
    void operationsLeaveNoPinnedPages() {
        try (Storage storage = open()) {
            allocate(storage, PageType.HEAP);
            allocate(storage, PageType.HEAP);
            storage.free(1);
            allocate(storage, PageType.HEAP);
            storage.setCatalogRoot(2);
            storage.pageCount();

            assertEquals(0, storage.pinnedPageCount());
        }
    }

    @Test
    void anOpenDatabaseCannotBeOpenedTwice() {
        try (Storage storage = open()) {
            assertEquals(1, storage.pageCount());
            assertThrows(StorageException.class, this::open);
        }
    }
}
