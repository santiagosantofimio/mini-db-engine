package dev.santiagosantofimio.minidb.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.santiagosantofimio.minidb.storage.PageHandle;
import dev.santiagosantofimio.minidb.storage.PageType;
import dev.santiagosantofimio.minidb.storage.Rid;
import dev.santiagosantofimio.minidb.storage.Storage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BPlusTreeTest {

    @TempDir
    Path directory;

    private Storage storage;

    @BeforeEach
    void openStorage() {
        storage = Storage.open(directory.resolve("tree.db"), 16);
    }

    @AfterEach
    void closeStorage() {
        assertEquals(0, storage.pinnedPageCount());
        storage.close();
    }

    private static Rid rid(int n) {
        return new Rid(1 + n / 100, n % 100);
    }

    private static List<Integer> keys(Iterator<IndexEntry> entries) {
        List<Integer> keys = new ArrayList<>();
        entries.forEachRemaining(entry -> keys.add(entry.key()));
        return keys;
    }

    private static List<Integer> numbers(int fromInclusive, int toInclusive) {
        List<Integer> numbers = new ArrayList<>();
        for (int i = fromInclusive; i <= toInclusive; i++) {
            numbers.add(i);
        }
        return numbers;
    }

    @Test
    void emptyTreeFindsNothing() {
        BPlusTree tree = BPlusTree.create(storage, 4);

        assertTrue(tree.lookup(1).isEmpty());
        assertFalse(tree.range(Integer.MIN_VALUE, Integer.MAX_VALUE).hasNext());
        assertEquals(new TreeShape(1, 1, 0), tree.checkIntegrity());
    }

    @Test
    void splitsLeavesAndGrowsTheRootInPlace() {
        BPlusTree tree = BPlusTree.create(storage, 3);
        int root = tree.rootPageId();

        for (int key = 1; key <= 7; key++) {
            tree.insert(key, rid(key));
        }

        assertEquals(root, tree.rootPageId());
        List<List<NodeView>> levels = tree.levels();
        assertEquals(List.of(List.of(3, 5)), levels.get(0).stream().map(NodeView::keys).toList());
        assertEquals(
                List.of(List.of(1, 2), List.of(3, 4), List.of(5, 6, 7)),
                levels.get(1).stream().map(NodeView::keys).toList());
        assertTrue(levels.get(1).stream().allMatch(NodeView::leaf));
        assertEquals(2, tree.checkIntegrity().height());
    }

    @Test
    void splitsInternalNodesAsTheTreeGrows() {
        BPlusTree tree = BPlusTree.create(storage, 3);

        for (int key = 1; key <= 200; key++) {
            tree.insert(key, rid(key));
        }

        TreeShape shape = tree.checkIntegrity();
        assertTrue(shape.height() >= 4);
        assertEquals(200, shape.entries());
        assertEquals(numbers(1, 200), keys(tree.range(Integer.MIN_VALUE, Integer.MAX_VALUE)));
    }

    @Test
    void handlesDescendingAndShuffledInsertOrders() {
        BPlusTree descending = BPlusTree.create(storage, 4);
        BPlusTree shuffled = BPlusTree.create(storage, 4);
        List<Integer> order = numbers(1, 500);
        Collections.shuffle(order, new Random(7));

        for (int key = 500; key >= 1; key--) {
            descending.insert(key, rid(key));
        }
        order.forEach(key -> shuffled.insert(key, rid(key)));

        assertEquals(numbers(1, 500), keys(descending.range(1, 500)));
        assertEquals(numbers(1, 500), keys(shuffled.range(1, 500)));
        descending.checkIntegrity();
        shuffled.checkIntegrity();
    }

    @Test
    void duplicateKeysAreOrderedByRid() {
        BPlusTree tree = BPlusTree.create(storage, 3);
        for (int n = 20; n >= 1; n--) {
            tree.insert(42, rid(n));
            tree.insert(n, rid(1000 + n));
        }

        List<Rid> rids = tree.lookup(42);

        assertEquals(numbers(1, 20).stream().map(BPlusTreeTest::rid).toList(), rids);
        assertTrue(tree.contains(42));
        tree.checkIntegrity();
    }

    @Test
    void rangesAreInclusiveAndMayBeEmpty() {
        BPlusTree tree = BPlusTree.create(storage, 4);
        for (int key = 0; key < 100; key += 2) {
            tree.insert(key, rid(key));
        }

        assertEquals(List.of(10, 12, 14, 16, 18, 20), keys(tree.range(10, 20)));
        assertEquals(List.of(10, 12, 14, 16, 18, 20), keys(tree.range(9, 21)));
        assertEquals(List.of(), keys(tree.range(11, 11)));
        assertEquals(List.of(), keys(tree.range(30, 20)));
        assertEquals(List.of(0, 2), keys(tree.range(Integer.MIN_VALUE, 3)));
        assertEquals(List.of(96, 98), keys(tree.range(95, Integer.MAX_VALUE)));
    }

    @Test
    void deleteRemovesOnlyTheMatchingEntry() {
        BPlusTree tree = BPlusTree.create(storage, 3);
        for (int n = 1; n <= 30; n++) {
            tree.insert(n % 3, rid(n));
        }

        assertTrue(tree.delete(1, rid(4)));
        assertFalse(tree.delete(1, rid(4)));
        assertFalse(tree.delete(7, rid(4)));

        assertEquals(9, tree.lookup(1).size());
        assertFalse(tree.lookup(1).contains(rid(4)));
        tree.checkIntegrity();
    }

    @Test
    void emptiedLeavesStayInPlaceAndScansSkipThem() {
        BPlusTree tree = BPlusTree.create(storage, 3);
        for (int key = 1; key <= 30; key++) {
            tree.insert(key, rid(key));
        }
        for (int key = 5; key <= 25; key++) {
            tree.delete(key, rid(key));
        }

        assertEquals(List.of(1, 2, 3, 4, 26, 27, 28, 29, 30), keys(tree.range(Integer.MIN_VALUE, Integer.MAX_VALUE)));
        assertEquals(List.of(26), keys(tree.range(10, 26)));
        assertEquals(9, tree.checkIntegrity().entries());
    }

    @Test
    void contentsSurviveReopening() {
        int root;
        BPlusTree tree = BPlusTree.create(storage, 4);
        root = tree.rootPageId();
        for (int key = 0; key < 1_000; key++) {
            tree.insert(key * 7 % 1_000, rid(key));
        }
        storage.close();

        storage = Storage.open(directory.resolve("tree.db"), 16);
        BPlusTree reopened = new BPlusTree(storage, root, 4);

        assertEquals(1_000, reopened.checkIntegrity().entries());
        assertEquals(numbers(0, 999), keys(reopened.range(0, 999)));
    }

    @Test
    void dropFreesEveryPage() {
        BPlusTree tree = BPlusTree.create(storage, 3);
        for (int key = 0; key < 100; key++) {
            tree.insert(key, rid(key));
        }
        int pages = tree.checkIntegrity().pages();
        int root = tree.rootPageId();

        tree.drop();

        try (PageHandle page = storage.fetch(root)) {
            assertEquals(PageType.FREE, PageType.readFrom(page));
        }
        int before = storage.pageCount();
        BPlusTree reused = BPlusTree.create(storage, 3);
        for (int key = 0; key < 100; key++) {
            reused.insert(key, rid(key));
        }
        assertEquals(pages, reused.checkIntegrity().pages());
        assertEquals(before, storage.pageCount());
    }

    @Test
    void abandonedRangeScanLeavesNoPinnedPages() {
        BPlusTree tree = BPlusTree.create(storage, 3);
        for (int key = 0; key < 100; key++) {
            tree.insert(key, rid(key));
        }

        Iterator<IndexEntry> scan = tree.range(10, 90);
        scan.next();

        assertEquals(0, storage.pinnedPageCount());
    }

    @Test
    void pageSizedNodesHoldHundredsOfKeys() {
        BPlusTree tree = BPlusTree.create(storage, BPlusTree.MAX_ORDER);
        for (int key = 0; key < 100_000; key++) {
            tree.insert(key, rid(key));
        }

        TreeShape shape = tree.checkIntegrity();
        assertEquals(3, shape.height());
        assertEquals(100_000, shape.entries());
        assertEquals(numbers(25_000, 25_010), keys(tree.range(25_000, 25_010)));
    }

    @Test
    void rejectsOrdersOutsideTheSupportedRange() {
        assertThrows(IllegalArgumentException.class, () -> BPlusTree.create(storage, BPlusTree.MIN_ORDER - 1));
        assertThrows(IllegalArgumentException.class, () -> BPlusTree.create(storage, BPlusTree.MAX_ORDER + 1));
    }

    @Test
    void randomOperationsMatchATreeMap() {
        for (int order : new int[] {3, 4, 7, 64}) {
            compareAgainstTreeMap(order, 40_000, new Random(20_261_126L + order));
        }
    }

    private void compareAgainstTreeMap(int order, int operations, Random random) {
        BPlusTree tree = BPlusTree.create(storage, order);
        TreeMap<IndexEntry, Boolean> expected = new TreeMap<>();
        List<IndexEntry> live = new ArrayList<>();
        int nextRid = 0;

        for (int operation = 0; operation < operations; operation++) {
            int choice = random.nextInt(100);
            if (choice < 60 || live.isEmpty()) {
                IndexEntry entry = new IndexEntry(random.nextInt(3_000) - 1_500, rid(nextRid++));
                tree.insert(entry.key(), entry.rid());
                expected.put(entry, true);
                live.add(entry);
            } else if (choice < 90) {
                int index = random.nextInt(live.size());
                IndexEntry entry = live.get(index);
                live.set(index, live.get(live.size() - 1));
                live.remove(live.size() - 1);
                assertTrue(tree.delete(entry.key(), entry.rid()));
                expected.remove(entry);
            } else {
                assertFalse(tree.delete(random.nextInt(3_000), rid(10_000_000)));
            }

            if (operation % 2_000 == 0) {
                assertEquals(expected.size(), tree.checkIntegrity().entries());
            }
            if (operation % 97 == 0) {
                int low = random.nextInt(3_200) - 1_600;
                int high = low + random.nextInt(200);
                assertEquals(expectedRange(expected, low, high), entries(tree.range(low, high)), "order " + order);
            }
        }

        assertEquals(new ArrayList<>(expected.keySet()), entries(tree.range(Integer.MIN_VALUE, Integer.MAX_VALUE)));
        assertEquals(expected.size(), tree.checkIntegrity().entries());
        tree.drop();
    }

    private static List<IndexEntry> expectedRange(TreeMap<IndexEntry, Boolean> expected, int low, int high) {
        IndexEntry from = new IndexEntry(low, new Rid(1, 0));
        return expected.keySet().stream()
                .dropWhile(entry -> entry.compareTo(from) < 0)
                .takeWhile(entry -> entry.key() <= high)
                .toList();
    }

    private static List<IndexEntry> entries(Iterator<IndexEntry> scan) {
        List<IndexEntry> entries = new ArrayList<>();
        scan.forEachRemaining(entries::add);
        return entries;
    }
}
