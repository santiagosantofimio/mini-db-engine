package dev.santiagosantofimio.minidb.storage;

import dev.santiagosantofimio.minidb.StorageException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;

final class HeaderPage {

    static final int PAGE_ID = 0;
    static final int FORMAT_VERSION = 1;

    private static final byte[] MAGIC = "MINIDB01".getBytes(StandardCharsets.US_ASCII);
    private static final int MAGIC_OFFSET = 0;
    private static final int VERSION_OFFSET = 8;
    private static final int PAGE_SIZE_OFFSET = 12;
    private static final int PAGE_COUNT_OFFSET = 16;
    private static final int FREE_LIST_HEAD_OFFSET = 20;
    private static final int CATALOG_ROOT_OFFSET = 24;

    private final PageHandle page;

    HeaderPage(PageHandle page) {
        if (page.pageId() != PAGE_ID) {
            throw new IllegalArgumentException("the header lives in page " + PAGE_ID + ", not " + page.pageId());
        }
        this.page = page;
    }

    void initialize() {
        page.putBytes(MAGIC_OFFSET, MAGIC);
        page.putInt(VERSION_OFFSET, FORMAT_VERSION);
        page.putInt(PAGE_SIZE_OFFSET, Pager.PAGE_SIZE);
        setPageCount(1);
        setFreeListHead(Storage.NO_PAGE);
        setCatalogRoot(Storage.NO_PAGE);
    }

    void validate(Path path) {
        if (!Arrays.equals(page.getBytes(MAGIC_OFFSET, MAGIC.length), MAGIC)) {
            throw new StorageException(path + " is not a minidb database");
        }
        int version = page.getInt(VERSION_OFFSET);
        if (version != FORMAT_VERSION) {
            throw new StorageException(path + " uses format version " + version
                    + ", but this build reads version " + FORMAT_VERSION);
        }
        int pageSize = page.getInt(PAGE_SIZE_OFFSET);
        if (pageSize != Pager.PAGE_SIZE) {
            throw new StorageException(path + " uses " + pageSize + "-byte pages, but this build uses "
                    + Pager.PAGE_SIZE);
        }
    }

    int pageCount() {
        return page.getInt(PAGE_COUNT_OFFSET);
    }

    void setPageCount(int pageCount) {
        page.putInt(PAGE_COUNT_OFFSET, pageCount);
    }

    int freeListHead() {
        return page.getInt(FREE_LIST_HEAD_OFFSET);
    }

    void setFreeListHead(int pageId) {
        page.putInt(FREE_LIST_HEAD_OFFSET, pageId);
    }

    int catalogRoot() {
        return page.getInt(CATALOG_ROOT_OFFSET);
    }

    void setCatalogRoot(int pageId) {
        page.putInt(CATALOG_ROOT_OFFSET, pageId);
    }
}
