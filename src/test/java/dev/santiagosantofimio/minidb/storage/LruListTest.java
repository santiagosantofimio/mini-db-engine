package dev.santiagosantofimio.minidb.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LruListTest {

    private final LruList list = new LruList();

    private static Frame frame(int pageId) {
        Frame frame = new Frame();
        frame.pageId = pageId;
        return frame;
    }

    private List<Integer> drain() {
        List<Integer> order = new ArrayList<>();
        for (Frame frame = list.leastRecent(); frame != null; frame = list.leastRecent()) {
            order.add(frame.pageId);
            list.remove(frame);
        }
        return order;
    }

    @Test
    void emptyListHasNoLeastRecentFrame() {
        assertNull(list.leastRecent());
        assertEquals(0, list.size());
    }

    @Test
    void framesLeaveInTheOrderTheyWereAdded() {
        list.addMostRecent(frame(1));
        list.addMostRecent(frame(2));
        list.addMostRecent(frame(3));

        assertEquals(List.of(1, 2, 3), drain());
    }

    @Test
    void removingFromTheMiddleKeepsTheOthersLinked() {
        Frame middle = frame(2);
        list.addMostRecent(frame(1));
        list.addMostRecent(middle);
        list.addMostRecent(frame(3));

        list.remove(middle);

        assertEquals(2, list.size());
        assertEquals(List.of(1, 3), drain());
    }

    @Test
    void readdingAFrameMakesItTheMostRecent() {
        Frame first = frame(1);
        list.addMostRecent(first);
        list.addMostRecent(frame(2));

        list.remove(first);
        list.addMostRecent(first);

        assertSame(first, list.leastRecent().next);
        assertEquals(List.of(2, 1), drain());
    }
}
