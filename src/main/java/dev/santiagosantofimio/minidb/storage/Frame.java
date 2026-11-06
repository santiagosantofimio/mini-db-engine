package dev.santiagosantofimio.minidb.storage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class Frame {

    final byte[] data = new byte[Pager.PAGE_SIZE];
    final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

    int pageId;
    int pinCount;
    boolean dirty;

    Frame previous;
    Frame next;
}
