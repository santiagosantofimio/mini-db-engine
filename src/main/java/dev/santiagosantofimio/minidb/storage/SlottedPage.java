package dev.santiagosantofimio.minidb.storage;

import dev.santiagosantofimio.minidb.StorageException;
import java.util.OptionalInt;

public final class SlottedPage {

    private static final int SLOT_COUNT_OFFSET = 1;
    private static final int FREE_SPACE_END_OFFSET = 3;
    private static final int NEXT_PAGE_OFFSET = 5;
    private static final int HEADER_SIZE = 9;
    private static final int SLOT_SIZE = 4;
    private static final int EMPTY_SLOT = 0;

    public static final int MAX_RECORD_SIZE = Pager.PAGE_SIZE - HEADER_SIZE - SLOT_SIZE;

    private final PageHandle page;

    public SlottedPage(PageHandle page) {
        PageType type = PageType.readFrom(page);
        if (type != PageType.HEAP) {
            throw new StorageException("page " + page.pageId() + " is a " + type + " page, not a heap page");
        }
        this.page = page;
    }

    public static SlottedPage initialize(PageHandle page) {
        PageType.HEAP.writeTo(page);
        page.putUnsignedShort(SLOT_COUNT_OFFSET, 0);
        page.putUnsignedShort(FREE_SPACE_END_OFFSET, Pager.PAGE_SIZE);
        page.putInt(NEXT_PAGE_OFFSET, Storage.NO_PAGE);
        return new SlottedPage(page);
    }

    public int pageId() {
        return page.pageId();
    }

    public int slotCount() {
        return page.getUnsignedShort(SLOT_COUNT_OFFSET);
    }

    public int nextPageId() {
        return page.getInt(NEXT_PAGE_OFFSET);
    }

    public void setNextPageId(int pageId) {
        page.putInt(NEXT_PAGE_OFFSET, pageId);
    }

    public boolean isLive(int slot) {
        checkSlot(slot);
        return recordOffset(slot) != EMPTY_SLOT;
    }

    public byte[] read(int slot) {
        checkLive(slot);
        return page.getBytes(recordOffset(slot), recordLength(slot));
    }

    public OptionalInt insert(byte[] record) {
        if (record.length > MAX_RECORD_SIZE) {
            throw new IllegalArgumentException(
                    "a record of " + record.length + " bytes exceeds the " + MAX_RECORD_SIZE + "-byte limit");
        }
        int slot = firstEmptySlot();
        int needed = record.length + (slot == slotCount() ? SLOT_SIZE : 0);
        if (contiguousFreeSpace() < needed) {
            if (totalFreeSpace() < needed) {
                return OptionalInt.empty();
            }
            compact();
        }
        int offset = freeSpaceEnd() - record.length;
        page.putBytes(offset, record);
        setFreeSpaceEnd(offset);
        if (slot == slotCount()) {
            setSlotCount(slot + 1);
        }
        setSlot(slot, offset, record.length);
        return OptionalInt.of(slot);
    }

    public void delete(int slot) {
        checkLive(slot);
        setSlot(slot, EMPTY_SLOT, 0);
    }

    public int totalFreeSpace() {
        int used = HEADER_SIZE + slotCount() * SLOT_SIZE;
        for (int slot = 0; slot < slotCount(); slot++) {
            used += recordLength(slot);
        }
        return Pager.PAGE_SIZE - used;
    }

    private int contiguousFreeSpace() {
        return freeSpaceEnd() - (HEADER_SIZE + slotCount() * SLOT_SIZE);
    }

    private void compact() {
        int slots = slotCount();
        byte[][] records = new byte[slots][];
        for (int slot = 0; slot < slots; slot++) {
            if (isLive(slot)) {
                records[slot] = read(slot);
            }
        }
        int end = Pager.PAGE_SIZE;
        for (int slot = 0; slot < slots; slot++) {
            if (records[slot] != null) {
                end -= records[slot].length;
                page.putBytes(end, records[slot]);
                setSlot(slot, end, records[slot].length);
            }
        }
        setFreeSpaceEnd(end);
    }

    private int firstEmptySlot() {
        int slots = slotCount();
        for (int slot = 0; slot < slots; slot++) {
            if (recordOffset(slot) == EMPTY_SLOT) {
                return slot;
            }
        }
        return slots;
    }

    private int freeSpaceEnd() {
        return page.getUnsignedShort(FREE_SPACE_END_OFFSET);
    }

    private void setFreeSpaceEnd(int offset) {
        page.putUnsignedShort(FREE_SPACE_END_OFFSET, offset);
    }

    private void setSlotCount(int count) {
        page.putUnsignedShort(SLOT_COUNT_OFFSET, count);
    }

    private static int slotOffset(int slot) {
        return HEADER_SIZE + slot * SLOT_SIZE;
    }

    private int recordOffset(int slot) {
        return page.getUnsignedShort(slotOffset(slot));
    }

    private int recordLength(int slot) {
        return page.getUnsignedShort(slotOffset(slot) + 2);
    }

    private void setSlot(int slot, int offset, int length) {
        page.putUnsignedShort(slotOffset(slot), offset);
        page.putUnsignedShort(slotOffset(slot) + 2, length);
    }

    private void checkSlot(int slot) {
        if (slot < 0 || slot >= slotCount()) {
            throw new IllegalArgumentException("page " + pageId() + " has no slot " + slot);
        }
    }

    private void checkLive(int slot) {
        if (!isLive(slot)) {
            throw new IllegalArgumentException("slot " + slot + " of page " + pageId() + " is empty");
        }
    }
}
