package dev.santiagosantofimio.minidb.index;

import dev.santiagosantofimio.minidb.StorageException;
import dev.santiagosantofimio.minidb.storage.PageHandle;
import dev.santiagosantofimio.minidb.storage.PageType;
import dev.santiagosantofimio.minidb.storage.Pager;
import dev.santiagosantofimio.minidb.storage.Rid;
import dev.santiagosantofimio.minidb.storage.Storage;
import java.util.ArrayList;
import java.util.List;

sealed interface Node {

    int COUNT_OFFSET = 1;
    int LINK_OFFSET = 3;
    int HEADER_SIZE = 7;
    int ENTRY_SIZE = 10;
    int CHILD_SIZE = 4;

    int LEAF_CAPACITY = (Pager.PAGE_SIZE - HEADER_SIZE) / ENTRY_SIZE;
    int INTERNAL_CAPACITY = (Pager.PAGE_SIZE - HEADER_SIZE) / (ENTRY_SIZE + CHILD_SIZE);

    PageType pageType();

    int size();

    void writeTo(PageHandle page);

    static Node readFrom(PageHandle page) {
        PageType type = PageType.readFrom(page);
        int count = page.getUnsignedShort(COUNT_OFFSET);
        int link = page.getInt(LINK_OFFSET);
        return switch (type) {
            case BTREE_LEAF -> {
                List<IndexEntry> entries = new ArrayList<>(count + 1);
                for (int i = 0; i < count; i++) {
                    entries.add(readEntry(page, HEADER_SIZE + i * ENTRY_SIZE));
                }
                yield new Leaf(entries, link);
            }
            case BTREE_INTERNAL -> {
                List<IndexEntry> separators = new ArrayList<>(count + 1);
                List<Integer> children = new ArrayList<>(count + 2);
                children.add(link);
                for (int i = 0; i < count; i++) {
                    int offset = HEADER_SIZE + i * (ENTRY_SIZE + CHILD_SIZE);
                    separators.add(readEntry(page, offset));
                    children.add(page.getInt(offset + ENTRY_SIZE));
                }
                yield new Internal(separators, children);
            }
            default -> throw new StorageException("page " + page.pageId() + " is a " + type + " page, not a B+ tree node");
        };
    }

    private static IndexEntry readEntry(PageHandle page, int offset) {
        Rid rid = new Rid(page.getInt(offset + 4), page.getUnsignedShort(offset + 8));
        return new IndexEntry(page.getInt(offset), rid);
    }

    private static void writeEntry(PageHandle page, int offset, IndexEntry entry) {
        page.putInt(offset, entry.key());
        page.putInt(offset + 4, entry.rid().pageId());
        page.putUnsignedShort(offset + 8, entry.rid().slot());
    }

    final class Leaf implements Node {

        final List<IndexEntry> entries;
        int nextLeaf;

        Leaf(List<IndexEntry> entries, int nextLeaf) {
            this.entries = entries;
            this.nextLeaf = nextLeaf;
        }

        static Leaf empty() {
            return new Leaf(new ArrayList<>(), Storage.NO_PAGE);
        }

        @Override
        public PageType pageType() {
            return PageType.BTREE_LEAF;
        }

        @Override
        public int size() {
            return entries.size();
        }

        @Override
        public void writeTo(PageHandle page) {
            if (entries.size() > LEAF_CAPACITY) {
                throw new IllegalStateException("a leaf holds at most " + LEAF_CAPACITY + " entries");
            }
            PageType.BTREE_LEAF.writeTo(page);
            page.putUnsignedShort(COUNT_OFFSET, entries.size());
            page.putInt(LINK_OFFSET, nextLeaf);
            for (int i = 0; i < entries.size(); i++) {
                writeEntry(page, HEADER_SIZE + i * ENTRY_SIZE, entries.get(i));
            }
        }
    }

    final class Internal implements Node {

        final List<IndexEntry> separators;
        final List<Integer> children;

        Internal(List<IndexEntry> separators, List<Integer> children) {
            if (children.size() != separators.size() + 1) {
                throw new IllegalArgumentException("an internal node has one more child than separators");
            }
            this.separators = separators;
            this.children = children;
        }

        @Override
        public PageType pageType() {
            return PageType.BTREE_INTERNAL;
        }

        @Override
        public int size() {
            return separators.size();
        }

        @Override
        public void writeTo(PageHandle page) {
            if (separators.size() > INTERNAL_CAPACITY) {
                throw new IllegalStateException("an internal node holds at most " + INTERNAL_CAPACITY + " keys");
            }
            PageType.BTREE_INTERNAL.writeTo(page);
            page.putUnsignedShort(COUNT_OFFSET, separators.size());
            page.putInt(LINK_OFFSET, children.get(0));
            for (int i = 0; i < separators.size(); i++) {
                int offset = HEADER_SIZE + i * (ENTRY_SIZE + CHILD_SIZE);
                writeEntry(page, offset, separators.get(i));
                page.putInt(offset + ENTRY_SIZE, children.get(i + 1));
            }
        }
    }
}
