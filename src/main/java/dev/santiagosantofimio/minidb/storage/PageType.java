package dev.santiagosantofimio.minidb.storage;

import dev.santiagosantofimio.minidb.StorageException;

public enum PageType {
    HEAP(1),
    BTREE_INTERNAL(2),
    BTREE_LEAF(3),
    CATALOG(4),
    FREE(5);

    private static final int TYPE_OFFSET = 0;

    private final byte code;

    PageType(int code) {
        this.code = (byte) code;
    }

    public byte code() {
        return code;
    }

    public static PageType fromCode(byte code) {
        for (PageType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new StorageException("unknown page type code " + code);
    }

    public static PageType readFrom(PageHandle page) {
        try {
            return fromCode(page.getByte(TYPE_OFFSET));
        } catch (StorageException e) {
            throw new StorageException("page " + page.pageId() + " has an " + e.getMessage(), e);
        }
    }

    public void writeTo(PageHandle page) {
        page.putByte(TYPE_OFFSET, code);
    }
}
