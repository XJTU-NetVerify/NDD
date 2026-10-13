package org.ants.mtpndd;

/**
 * Unchecked exception thrown when the underlying MTPNDD native runtime
 * reports an error.
 */
public class MTPNDDException extends RuntimeException {
    public MTPNDDException(String message) {
        super(message);
    }

    public MTPNDDException(String message, Throwable cause) {
        super(message, cause);
    }
}
