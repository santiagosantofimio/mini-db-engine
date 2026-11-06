package dev.santiagosantofimio.minidb.storage;

import dev.santiagosantofimio.minidb.StorageException;
import java.nio.file.Path;

public final class Storage implements AutoCloseable {

    public static final int NO_PAGE = 0;

    private static final int NEXT_FREE_PAGE_OFFSET = 1;

    private final Pager pager;
    private final BufferPool pool;

    private Storage(Pager pager, BufferPool pool) {
        this.pager = pager;
        this.pool = pool;
    }

    public static Storage open(Path path, int bufferPages) {
        Pager pager = Pager.open(path);
        try {
            Storage storage = new Storage(pager, new BufferPool(pager, bufferPages));
            if (pager.sizeInBytes() == 0) {
                storage.initialize();
            } else {
                storage.validate();
            }
            return storage;
        } catch (RuntimeException e) {
            try {
                pager.close();
            } catch (RuntimeException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    private void initialize() {
        try (PageHandle page = pool.fetchNew(HeaderPage.PAGE_ID)) {
            new HeaderPage(page).initialize();
        }
        flush();
    }

    private void validate() {
        if (pager.sizeInBytes() % Pager.PAGE_SIZE != 0) {
            throw new StorageException(pager.path() + " is not a minidb database or is damaged");
        }
        try (PageHandle page = pool.fetch(HeaderPage.PAGE_ID)) {
            new HeaderPage(page).validate(pager.path());
        }
    }

    public PageHandle fetch(int pageId) {
        return pool.fetch(pageId);
    }

    public PageHandle allocate(PageType type) {
        if (type == PageType.FREE) {
            throw new IllegalArgumentException("pages are freed with free(), not allocated as FREE");
        }
        try (PageHandle headerPage = pool.fetch(HeaderPage.PAGE_ID)) {
            HeaderPage header = new HeaderPage(headerPage);
            PageHandle page = header.freeListHead() == NO_PAGE ? appendPage(header) : reuseFreePage(header);
            type.writeTo(page);
            return page;
        }
    }

    private PageHandle appendPage(HeaderPage header) {
        int pageId = header.pageCount();
        PageHandle page = pool.fetchNew(pageId);
        header.setPageCount(pageId + 1);
        return page;
    }

    private PageHandle reuseFreePage(HeaderPage header) {
        int pageId = header.freeListHead();
        PageHandle page = pool.fetch(pageId);
        try {
            if (PageType.readFrom(page) != PageType.FREE) {
                throw new StorageException("the free list is damaged: page " + pageId + " is not free");
            }
            header.setFreeListHead(page.getInt(NEXT_FREE_PAGE_OFFSET));
            page.clear();
            return page;
        } catch (RuntimeException e) {
            page.close();
            throw e;
        }
    }

    public void free(int pageId) {
        try (PageHandle headerPage = pool.fetch(HeaderPage.PAGE_ID)) {
            HeaderPage header = new HeaderPage(headerPage);
            if (pageId <= HeaderPage.PAGE_ID || pageId >= header.pageCount()) {
                throw new IllegalArgumentException("page " + pageId + " cannot be freed");
            }
            try (PageHandle page = pool.fetch(pageId)) {
                if (PageType.readFrom(page) == PageType.FREE) {
                    throw new IllegalStateException("page " + pageId + " is already free");
                }
                page.clear();
                PageType.FREE.writeTo(page);
                page.putInt(NEXT_FREE_PAGE_OFFSET, header.freeListHead());
                header.setFreeListHead(pageId);
            }
        }
    }

    public int pageCount() {
        try (PageHandle page = pool.fetch(HeaderPage.PAGE_ID)) {
            return new HeaderPage(page).pageCount();
        }
    }

    public int catalogRoot() {
        try (PageHandle page = pool.fetch(HeaderPage.PAGE_ID)) {
            return new HeaderPage(page).catalogRoot();
        }
    }

    public void setCatalogRoot(int pageId) {
        try (PageHandle page = pool.fetch(HeaderPage.PAGE_ID)) {
            new HeaderPage(page).setCatalogRoot(pageId);
        }
    }

    public BufferPoolStats stats() {
        return pool.stats();
    }

    public int pinnedPageCount() {
        return pool.pinnedPageCount();
    }

    public void flush() {
        pool.flushAll();
        pager.force();
    }

    @Override
    public void close() {
        try {
            flush();
        } finally {
            pager.close();
        }
    }
}
