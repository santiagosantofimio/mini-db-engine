package dev.santiagosantofimio.minidb;

public final class StorageException extends MiniDbException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
