package dev.santiagosantofimio.minidb.storage;

import dev.santiagosantofimio.minidb.ConstraintViolationException;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.OptionalInt;

public final class HeapFile {

    private final Storage storage;
    private final int firstPageId;

    private HeapFile(Storage storage, int firstPageId) {
        this.storage = storage;
        this.firstPageId = firstPageId;
    }

    public static HeapFile create(Storage storage) {
        try (PageHandle page = storage.allocate(PageType.HEAP)) {
            SlottedPage.initialize(page);
            return new HeapFile(storage, page.pageId());
        }
    }

    public static HeapFile open(Storage storage, int firstPageId) {
        return new HeapFile(storage, firstPageId);
    }

    public int firstPageId() {
        return firstPageId;
    }

    public Rid insert(byte[] record) {
        if (record.length > SlottedPage.MAX_RECORD_SIZE) {
            throw new ConstraintViolationException("a row of " + record.length
                    + " bytes does not fit in a page; the limit is " + SlottedPage.MAX_RECORD_SIZE + " bytes");
        }
        int pageId = firstPageId;
        while (true) {
            try (PageHandle page = storage.fetch(pageId)) {
                SlottedPage slotted = new SlottedPage(page);
                OptionalInt slot = slotted.insert(record);
                if (slot.isPresent()) {
                    return new Rid(pageId, slot.getAsInt());
                }
                if (slotted.nextPageId() == Storage.NO_PAGE) {
                    return insertIntoNewPage(slotted, record);
                }
                pageId = slotted.nextPageId();
            }
        }
    }

    private Rid insertIntoNewPage(SlottedPage last, byte[] record) {
        try (PageHandle page = storage.allocate(PageType.HEAP)) {
            int slot = SlottedPage.initialize(page).insert(record).orElseThrow();
            last.setNextPageId(page.pageId());
            return new Rid(page.pageId(), slot);
        }
    }

    public Optional<byte[]> read(Rid rid) {
        try (PageHandle page = storage.fetch(rid.pageId())) {
            SlottedPage slotted = new SlottedPage(page);
            if (!holdsRecord(slotted, rid.slot())) {
                return Optional.empty();
            }
            return Optional.of(slotted.read(rid.slot()));
        }
    }

    public boolean delete(Rid rid) {
        try (PageHandle page = storage.fetch(rid.pageId())) {
            SlottedPage slotted = new SlottedPage(page);
            if (!holdsRecord(slotted, rid.slot())) {
                return false;
            }
            slotted.delete(rid.slot());
            return true;
        }
    }

    public Iterator<HeapRecord> scan() {
        return new Scan();
    }

    private static boolean holdsRecord(SlottedPage page, int slot) {
        return slot < page.slotCount() && page.isLive(slot);
    }

    private final class Scan implements Iterator<HeapRecord> {

        private final ArrayDeque<HeapRecord> pending = new ArrayDeque<>();
        private int nextPageId = firstPageId;

        @Override
        public boolean hasNext() {
            while (pending.isEmpty() && nextPageId != Storage.NO_PAGE) {
                bufferNextPage();
            }
            return !pending.isEmpty();
        }

        @Override
        public HeapRecord next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return pending.removeFirst();
        }

        private void bufferNextPage() {
            try (PageHandle page = storage.fetch(nextPageId)) {
                SlottedPage slotted = new SlottedPage(page);
                for (int slot = 0; slot < slotted.slotCount(); slot++) {
                    if (slotted.isLive(slot)) {
                        pending.addLast(new HeapRecord(new Rid(page.pageId(), slot), slotted.read(slot)));
                    }
                }
                nextPageId = slotted.nextPageId();
            }
        }
    }
}
