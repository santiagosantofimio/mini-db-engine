package dev.santiagosantofimio.minidb.storage;

import dev.santiagosantofimio.minidb.StorageException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class BufferPool {

    private final Pager pager;
    private final int capacity;
    private final Map<Integer, Frame> frames;
    private final LruList unpinned = new LruList();

    private long hits;
    private long misses;
    private long evictions;

    public BufferPool(Pager pager, int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("a buffer pool needs at least one page, got " + capacity);
        }
        this.pager = Objects.requireNonNull(pager, "pager");
        this.capacity = capacity;
        this.frames = HashMap.newHashMap(capacity);
    }

    public PageHandle fetch(int pageId) {
        checkPageId(pageId);
        Frame frame = frames.get(pageId);
        if (frame != null) {
            hits++;
            pin(frame);
        } else {
            misses++;
            frame = claimFrame();
            pager.read(pageId, frame.data);
            install(frame, pageId, false);
        }
        return new PageHandle(this, frame);
    }

    PageHandle fetchNew(int pageId) {
        checkPageId(pageId);
        if (frames.containsKey(pageId)) {
            throw new IllegalStateException("page " + pageId + " is already in the buffer pool");
        }
        Frame frame = claimFrame();
        Arrays.fill(frame.data, (byte) 0);
        install(frame, pageId, true);
        return new PageHandle(this, frame);
    }

    void unpin(Frame frame) {
        frame.pinCount--;
        if (frame.pinCount == 0) {
            unpinned.addMostRecent(frame);
        }
    }

    public void flushAll() {
        frames.values().stream()
                .filter(frame -> frame.dirty)
                .sorted(Comparator.comparingInt(frame -> frame.pageId))
                .forEach(this::writeBack);
    }

    public BufferPoolStats stats() {
        return new BufferPoolStats(hits, misses, evictions);
    }

    public int capacity() {
        return capacity;
    }

    public int cachedPageCount() {
        return frames.size();
    }

    public int pinnedPageCount() {
        return frames.size() - unpinned.size();
    }

    private Frame claimFrame() {
        if (frames.size() < capacity) {
            return new Frame();
        }
        Frame victim = unpinned.leastRecent();
        if (victim == null) {
            throw new StorageException("buffer pool exhausted: all " + capacity + " pages are pinned");
        }
        if (victim.dirty) {
            writeBack(victim);
        }
        unpinned.remove(victim);
        frames.remove(victim.pageId);
        evictions++;
        return victim;
    }

    private void install(Frame frame, int pageId, boolean dirty) {
        frame.pageId = pageId;
        frame.pinCount = 1;
        frame.dirty = dirty;
        frames.put(pageId, frame);
    }

    private void pin(Frame frame) {
        if (frame.pinCount == 0) {
            unpinned.remove(frame);
        }
        frame.pinCount++;
    }

    private void writeBack(Frame frame) {
        pager.write(frame.pageId, frame.data);
        frame.dirty = false;
    }

    private static void checkPageId(int pageId) {
        if (pageId < 0) {
            throw new IllegalArgumentException("page id must not be negative, got " + pageId);
        }
    }
}
