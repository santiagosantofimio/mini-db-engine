package dev.santiagosantofimio.minidb;

public final class ConstraintViolationException extends MiniDbException {

    public ConstraintViolationException(String message) {
        super(message);
    }
}
