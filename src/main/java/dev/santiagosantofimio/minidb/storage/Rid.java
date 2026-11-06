package dev.santiagosantofimio.minidb.storage;

public record Rid(int pageId, int slot) {

    public Rid {
        if (pageId <= Storage.NO_PAGE) {
            throw new IllegalArgumentException("a record cannot live in page " + pageId);
        }
        if (slot < 0) {
            throw new IllegalArgumentException("slot must not be negative, got " + slot);
        }
    }

    @Override
    public String toString() {
        return "(" + pageId + ", " + slot + ")";
    }
}
