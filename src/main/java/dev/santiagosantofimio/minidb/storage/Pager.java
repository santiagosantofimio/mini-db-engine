package dev.santiagosantofimio.minidb.storage;

import dev.santiagosantofimio.minidb.StorageException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class Pager implements AutoCloseable {

    public static final int PAGE_SIZE = 4096;

    private final Path path;
    private final FileChannel channel;

    private Pager(Path path, FileChannel channel) {
        this.path = path;
        this.channel = channel;
    }

    public static Pager open(Path path) {
        FileChannel channel;
        try {
            channel = FileChannel.open(
                    path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new StorageException("cannot open " + path + ": " + e.getMessage(), e);
        }
        try {
            lock(path, channel);
            return new Pager(path, channel);
        } catch (RuntimeException e) {
            closeAfterFailure(channel, e);
            throw e;
        }
    }

    private static void lock(Path path, FileChannel channel) {
        try {
            if (channel.tryLock() == null) {
                throw new StorageException(path + " is locked by another process");
            }
        } catch (OverlappingFileLockException e) {
            throw new StorageException(path + " is already open", e);
        } catch (IOException e) {
            throw new StorageException("cannot lock " + path + ": " + e.getMessage(), e);
        }
    }

    private static void closeAfterFailure(FileChannel channel, RuntimeException failure) {
        try {
            channel.close();
        } catch (IOException e) {
            failure.addSuppressed(e);
        }
    }

    public Path path() {
        return path;
    }

    public long sizeInBytes() {
        try {
            return channel.size();
        } catch (IOException e) {
            throw new StorageException("cannot read the size of " + path + ": " + e.getMessage(), e);
        }
    }

    public void read(int pageId, byte[] destination) {
        checkArguments(pageId, destination);
        long position = offsetOf(pageId);
        if (position + PAGE_SIZE > sizeInBytes()) {
            throw new StorageException("page " + pageId + " is beyond the end of " + path);
        }
        ByteBuffer buffer = ByteBuffer.wrap(destination);
        try {
            while (buffer.hasRemaining()) {
                if (channel.read(buffer, position + buffer.position()) < 0) {
                    throw new StorageException("unexpected end of " + path + " while reading page " + pageId);
                }
            }
        } catch (IOException e) {
            throw new StorageException("cannot read page " + pageId + " of " + path + ": " + e.getMessage(), e);
        }
    }

    public void write(int pageId, byte[] source) {
        checkArguments(pageId, source);
        long position = offsetOf(pageId);
        ByteBuffer buffer = ByteBuffer.wrap(source);
        try {
            while (buffer.hasRemaining()) {
                channel.write(buffer, position + buffer.position());
            }
        } catch (IOException e) {
            throw new StorageException("cannot write page " + pageId + " of " + path + ": " + e.getMessage(), e);
        }
    }

    public void force() {
        try {
            channel.force(true);
        } catch (IOException e) {
            throw new StorageException("cannot flush " + path + " to disk: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        try {
            channel.close();
        } catch (IOException e) {
            throw new StorageException("cannot close " + path + ": " + e.getMessage(), e);
        }
    }

    private static long offsetOf(int pageId) {
        return (long) pageId * PAGE_SIZE;
    }

    private static void checkArguments(int pageId, byte[] page) {
        if (pageId < 0) {
            throw new IllegalArgumentException("page id must not be negative, got " + pageId);
        }
        if (page.length != PAGE_SIZE) {
            throw new IllegalArgumentException("page buffers hold " + PAGE_SIZE + " bytes, got " + page.length);
        }
    }
}
