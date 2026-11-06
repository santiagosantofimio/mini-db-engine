package dev.santiagosantofimio.minidb.storage;

final class LruList {

    private Frame leastRecent;
    private Frame mostRecent;
    private int size;

    void addMostRecent(Frame frame) {
        frame.previous = mostRecent;
        frame.next = null;
        if (mostRecent == null) {
            leastRecent = frame;
        } else {
            mostRecent.next = frame;
        }
        mostRecent = frame;
        size++;
    }

    void remove(Frame frame) {
        if (frame.previous == null) {
            leastRecent = frame.next;
        } else {
            frame.previous.next = frame.next;
        }
        if (frame.next == null) {
            mostRecent = frame.previous;
        } else {
            frame.next.previous = frame.previous;
        }
        frame.previous = null;
        frame.next = null;
        size--;
    }

    Frame leastRecent() {
        return leastRecent;
    }

    int size() {
        return size;
    }
}
