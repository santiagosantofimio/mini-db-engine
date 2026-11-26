package dev.santiagosantofimio.minidb.index;

import dev.santiagosantofimio.minidb.storage.Rid;
import java.util.Comparator;
import java.util.Objects;

public record IndexEntry(int key, Rid rid) implements Comparable<IndexEntry> {

    private static final Comparator<IndexEntry> ORDER = Comparator.comparingInt(IndexEntry::key)
            .thenComparingInt(entry -> entry.rid().pageId())
            .thenComparingInt(entry -> entry.rid().slot());

    public IndexEntry {
        Objects.requireNonNull(rid, "rid");
    }

    @Override
    public int compareTo(IndexEntry other) {
        return ORDER.compare(this, other);
    }
}
