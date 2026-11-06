package dev.santiagosantofimio.minidb.storage;

import java.nio.ByteBuffer;
import java.util.Arrays;

public final class PageHandle implements AutoCloseable {

    private static final int MAX_UNSIGNED_SHORT = 0xFFFF;

    private final BufferPool pool;
    private final Frame frame;
    private final int pageId;
    private boolean closed;

    PageHandle(BufferPool pool, Frame frame) {
        this.pool = pool;
        this.frame = frame;
        this.pageId = frame.pageId;
    }

    public int pageId() {
        return pageId;
    }

    public byte getByte(int offset) {
        return readable().get(offset);
    }

    public void putByte(int offset, byte value) {
        writable().put(offset, value);
    }

    public int getUnsignedShort(int offset) {
        return Short.toUnsignedInt(readable().getShort(offset));
    }

    public void putUnsignedShort(int offset, int value) {
        if (value < 0 || value > MAX_UNSIGNED_SHORT) {
            throw new IllegalArgumentException(value + " does not fit in an unsigned short");
        }
        writable().putShort(offset, (short) value);
    }

    public int getInt(int offset) {
        return readable().getInt(offset);
    }

    public void putInt(int offset, int value) {
        writable().putInt(offset, value);
    }

    public byte[] getBytes(int offset, int length) {
        byte[] bytes = new byte[length];
        readable().get(offset, bytes);
        return bytes;
    }

    public void putBytes(int offset, byte[] bytes) {
        writable().put(offset, bytes);
    }

    public void clear() {
        writable();
        Arrays.fill(frame.data, (byte) 0);
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            pool.unpin(frame);
        }
    }

    private ByteBuffer readable() {
        if (closed) {
            throw new IllegalStateException("page " + pageId + " was used after its handle was closed");
        }
        return frame.buffer;
    }

    private ByteBuffer writable() {
        ByteBuffer buffer = readable();
        frame.dirty = true;
        return buffer;
    }
}
