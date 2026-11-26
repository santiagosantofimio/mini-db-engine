package dev.santiagosantofimio.minidb.index;

import dev.santiagosantofimio.minidb.StorageException;
import dev.santiagosantofimio.minidb.index.Node.Internal;
import dev.santiagosantofimio.minidb.index.Node.Leaf;
import dev.santiagosantofimio.minidb.storage.PageHandle;
import dev.santiagosantofimio.minidb.storage.Rid;
import dev.santiagosantofimio.minidb.storage.Storage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

public final class BPlusTree {

    public static final int MIN_ORDER = 3;
    public static final int MAX_ORDER = Node.LEAF_CAPACITY;

    private final Storage storage;
    private final int rootPageId;
    private final int maxLeafEntries;
    private final int maxInternalKeys;

    private record Split(IndexEntry separator, int rightPageId) {
    }

    public BPlusTree(Storage storage, int rootPageId, int order) {
        if (order < MIN_ORDER || order > MAX_ORDER) {
            throw new IllegalArgumentException(
                    "a B+ tree order must be between " + MIN_ORDER + " and " + MAX_ORDER + ", got " + order);
        }
        this.storage = storage;
        this.rootPageId = rootPageId;
        this.maxLeafEntries = Math.min(order, Node.LEAF_CAPACITY);
        this.maxInternalKeys = Math.min(order, Node.INTERNAL_CAPACITY);
    }

    public static BPlusTree create(Storage storage, int order) {
        int root = allocate(storage, Leaf.empty());
        return new BPlusTree(storage, root, order);
    }

    public int rootPageId() {
        return rootPageId;
    }

    public void insert(int key, Rid rid) {
        Optional<Split> split = insert(rootPageId, new IndexEntry(key, rid));
        if (split.isPresent()) {
            growRoot(split.get());
        }
    }

    private Optional<Split> insert(int pageId, IndexEntry entry) {
        return switch (read(pageId)) {
            case Leaf leaf -> insertIntoLeaf(pageId, leaf, entry);
            case Internal internal -> insertIntoInternal(pageId, internal, entry);
        };
    }

    private Optional<Split> insertIntoLeaf(int pageId, Leaf leaf, IndexEntry entry) {
        int position = Collections.binarySearch(leaf.entries, entry);
        if (position >= 0) {
            throw new IllegalStateException(entry + " is already in the index");
        }
        leaf.entries.add(-position - 1, entry);
        if (leaf.size() <= maxLeafEntries) {
            write(pageId, leaf);
            return Optional.empty();
        }
        int middle = leaf.size() / 2;
        Leaf right = new Leaf(new ArrayList<>(leaf.entries.subList(middle, leaf.size())), leaf.nextLeaf);
        int rightPageId = allocate(storage, right);
        Leaf left = new Leaf(new ArrayList<>(leaf.entries.subList(0, middle)), rightPageId);
        write(pageId, left);
        return Optional.of(new Split(right.entries.get(0), rightPageId));
    }

    private Optional<Split> insertIntoInternal(int pageId, Internal internal, IndexEntry entry) {
        int child = childFor(internal, entry);
        Optional<Split> childSplit = insert(internal.children.get(child), entry);
        if (childSplit.isEmpty()) {
            return Optional.empty();
        }
        internal.separators.add(child, childSplit.get().separator());
        internal.children.add(child + 1, childSplit.get().rightPageId());
        if (internal.size() <= maxInternalKeys) {
            write(pageId, internal);
            return Optional.empty();
        }
        int middle = internal.size() / 2;
        IndexEntry promoted = internal.separators.get(middle);
        Internal right = new Internal(
                new ArrayList<>(internal.separators.subList(middle + 1, internal.size())),
                new ArrayList<>(internal.children.subList(middle + 1, internal.children.size())));
        Internal left = new Internal(
                new ArrayList<>(internal.separators.subList(0, middle)),
                new ArrayList<>(internal.children.subList(0, middle + 1)));
        int rightPageId = allocate(storage, right);
        write(pageId, left);
        return Optional.of(new Split(promoted, rightPageId));
    }

    private void growRoot(Split split) {
        Node oldRoot = read(rootPageId);
        int leftPageId = allocate(storage, oldRoot);
        List<IndexEntry> separators = new ArrayList<>(List.of(split.separator()));
        List<Integer> children = new ArrayList<>(List.of(leftPageId, split.rightPageId()));
        write(rootPageId, new Internal(separators, children));
    }

    public boolean delete(int key, Rid rid) {
        IndexEntry entry = new IndexEntry(key, rid);
        int pageId = rootPageId;
        while (true) {
            switch (read(pageId)) {
                case Internal internal -> pageId = internal.children.get(childFor(internal, entry));
                case Leaf leaf -> {
                    int position = Collections.binarySearch(leaf.entries, entry);
                    if (position < 0) {
                        return false;
                    }
                    leaf.entries.remove(position);
                    write(pageId, leaf);
                    return true;
                }
            }
        }
    }

    public List<Rid> lookup(int key) {
        List<Rid> rids = new ArrayList<>();
        range(key, key).forEachRemaining(entry -> rids.add(entry.rid()));
        return rids;
    }

    public boolean contains(int key) {
        return range(key, key).hasNext();
    }

    public Iterator<IndexEntry> range(int low, int high) {
        return new RangeScan(low, high);
    }

    public void drop() {
        drop(rootPageId);
    }

    private void drop(int pageId) {
        if (read(pageId) instanceof Internal internal) {
            internal.children.forEach(this::drop);
        }
        storage.free(pageId);
    }

    public List<List<NodeView>> levels() {
        List<List<NodeView>> levels = new ArrayList<>();
        List<Integer> level = List.of(rootPageId);
        while (!level.isEmpty()) {
            List<NodeView> views = new ArrayList<>(level.size());
            List<Integer> next = new ArrayList<>();
            for (int pageId : level) {
                Node node = read(pageId);
                switch (node) {
                    case Leaf leaf -> views.add(new NodeView(pageId, true, keys(leaf.entries)));
                    case Internal internal -> {
                        views.add(new NodeView(pageId, false, keys(internal.separators)));
                        next.addAll(internal.children);
                    }
                }
            }
            levels.add(views);
            level = next;
        }
        return levels;
    }

    public TreeShape checkIntegrity() {
        List<Integer> leavesInOrder = new ArrayList<>();
        int[] counts = new int[2];
        int height = check(rootPageId, null, null, leavesInOrder, counts);
        int pageId = leavesInOrder.get(0);
        for (int i = 0; i < leavesInOrder.size(); i++) {
            if (pageId != leavesInOrder.get(i)) {
                throw new StorageException("index is damaged: the leaf chain skips page " + leavesInOrder.get(i));
            }
            pageId = ((Leaf) read(pageId)).nextLeaf;
        }
        if (pageId != Storage.NO_PAGE) {
            throw new StorageException("index is damaged: the leaf chain continues past the last leaf");
        }
        return new TreeShape(height, counts[0], counts[1]);
    }

    private int check(int pageId, IndexEntry low, IndexEntry high, List<Integer> leaves, int[] counts) {
        Node node = read(pageId);
        counts[0]++;
        return switch (node) {
            case Leaf leaf -> {
                requireSortedWithin(pageId, leaf.entries, low, high);
                if (leaf.size() > maxLeafEntries) {
                    throw damaged(pageId, "holds " + leaf.size() + " entries");
                }
                leaves.add(pageId);
                counts[1] += leaf.size();
                yield 1;
            }
            case Internal internal -> {
                requireSortedWithin(pageId, internal.separators, low, high);
                if (internal.size() == 0 || internal.size() > maxInternalKeys) {
                    throw damaged(pageId, "holds " + internal.size() + " keys");
                }
                int depth = -1;
                for (int i = 0; i < internal.children.size(); i++) {
                    IndexEntry childLow = i == 0 ? low : internal.separators.get(i - 1);
                    IndexEntry childHigh = i == internal.size() ? high : internal.separators.get(i);
                    int childDepth = check(internal.children.get(i), childLow, childHigh, leaves, counts);
                    if (depth >= 0 && childDepth != depth) {
                        throw damaged(pageId, "has leaves at different depths");
                    }
                    depth = childDepth;
                }
                yield depth + 1;
            }
        };
    }

    private static void requireSortedWithin(int pageId, List<IndexEntry> entries, IndexEntry low, IndexEntry high) {
        for (int i = 0; i < entries.size(); i++) {
            IndexEntry entry = entries.get(i);
            if (i > 0 && entries.get(i - 1).compareTo(entry) >= 0) {
                throw damaged(pageId, "is not sorted");
            }
            boolean belowRange = low != null && entry.compareTo(low) < 0;
            boolean aboveRange = high != null && entry.compareTo(high) >= 0;
            if (belowRange || aboveRange) {
                throw damaged(pageId, "holds " + entry + " outside the range its parent allows");
            }
        }
    }

    private static StorageException damaged(int pageId, String problem) {
        return new StorageException("index is damaged: page " + pageId + " " + problem);
    }

    private static List<Integer> keys(List<IndexEntry> entries) {
        return entries.stream().map(IndexEntry::key).toList();
    }

    private static int childFor(Internal internal, IndexEntry entry) {
        int position = Collections.binarySearch(internal.separators, entry);
        return position >= 0 ? position + 1 : -position - 1;
    }

    private static int childForLowestKey(Internal internal, int key) {
        int low = 0;
        int high = internal.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (internal.separators.get(middle).key() < key) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private int leafFor(int key) {
        int pageId = rootPageId;
        while (read(pageId) instanceof Internal internal) {
            pageId = internal.children.get(childForLowestKey(internal, key));
        }
        return pageId;
    }

    private Node read(int pageId) {
        try (PageHandle page = storage.fetch(pageId)) {
            return Node.readFrom(page);
        }
    }

    private void write(int pageId, Node node) {
        try (PageHandle page = storage.fetch(pageId)) {
            node.writeTo(page);
        }
    }

    private static int allocate(Storage storage, Node node) {
        try (PageHandle page = storage.allocate(node.pageType())) {
            node.writeTo(page);
            return page.pageId();
        }
    }

    private final class RangeScan implements Iterator<IndexEntry> {

        private final int low;
        private final int high;
        private final ArrayDeque<IndexEntry> pending = new ArrayDeque<>();
        private int nextLeaf;
        private boolean finished;

        RangeScan(int low, int high) {
            this.low = low;
            this.high = high;
            this.finished = low > high;
            this.nextLeaf = finished ? Storage.NO_PAGE : leafFor(low);
        }

        @Override
        public boolean hasNext() {
            while (pending.isEmpty() && !finished && nextLeaf != Storage.NO_PAGE) {
                bufferNextLeaf();
            }
            return !pending.isEmpty();
        }

        @Override
        public IndexEntry next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return pending.removeFirst();
        }

        private void bufferNextLeaf() {
            Leaf leaf = (Leaf) read(nextLeaf);
            for (IndexEntry entry : leaf.entries) {
                if (entry.key() > high) {
                    finished = true;
                    break;
                }
                if (entry.key() >= low) {
                    pending.addLast(entry);
                }
            }
            nextLeaf = leaf.nextLeaf;
        }
    }
}
