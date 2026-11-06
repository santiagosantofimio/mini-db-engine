package dev.santiagosantofimio.minidb.storage;

import static dev.santiagosantofimio.minidb.storage.Pager.PAGE_SIZE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.santiagosantofimio.minidb.StorageException;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PagerTest {

    @TempDir
    Path directory;

    private Path file() {
        return directory.resolve("pager.db");
    }

    private static byte[] filledWith(int value) {
        byte[] page = new byte[PAGE_SIZE];
        Arrays.fill(page, (byte) value);
        return page;
    }

    @Test
    void writtenPagesReadBackAfterReopening() {
        try (Pager pager = Pager.open(file())) {
            pager.write(0, filledWith(1));
            pager.write(1, filledWith(2));
        }

        try (Pager pager = Pager.open(file())) {
            byte[] page = new byte[PAGE_SIZE];
            pager.read(1, page);

            assertArrayEquals(filledWith(2), page);
            assertEquals(2L * PAGE_SIZE, pager.sizeInBytes());
        }
    }

    @Test
    void openingCreatesAnEmptyFile() {
        try (Pager pager = Pager.open(file())) {
            assertEquals(0, pager.sizeInBytes());
        }
    }

    @Test
    void readingPastTheEndOfTheFileFails() {
        try (Pager pager = Pager.open(file())) {
            pager.write(0, filledWith(1));

            assertThrows(StorageException.class, () -> pager.read(1, new byte[PAGE_SIZE]));
        }
    }

    @Test
    void aFileCanBeOpenedByOnlyOnePagerAtATime() {
        try (Pager pager = Pager.open(file())) {
            assertEquals(file(), pager.path());
            assertThrows(StorageException.class, () -> Pager.open(file()));
        }
        try (Pager reopened = Pager.open(file())) {
            assertEquals(0, reopened.sizeInBytes());
        }
    }

    @Test
    void rejectsBuffersThatAreNotExactlyOnePage() {
        try (Pager pager = Pager.open(file())) {
            assertThrows(IllegalArgumentException.class, () -> pager.write(0, new byte[100]));
            assertThrows(IllegalArgumentException.class, () -> pager.read(-1, new byte[PAGE_SIZE]));
        }
    }
}
