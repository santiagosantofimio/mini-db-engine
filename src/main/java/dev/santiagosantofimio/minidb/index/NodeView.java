package dev.santiagosantofimio.minidb.index;

import java.util.List;

public record NodeView(int pageId, boolean leaf, List<Integer> keys) {

    public NodeView {
        keys = List.copyOf(keys);
    }
}
