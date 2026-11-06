package dev.santiagosantofimio.minidb.storage;

public record BufferPoolStats(long hits, long misses, long evictions) {

    public double hitRatio() {
        long requests = hits + misses;
        return requests == 0 ? 0.0 : (double) hits / requests;
    }
}
