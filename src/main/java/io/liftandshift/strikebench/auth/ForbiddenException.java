package io.liftandshift.strikebench.auth;

/** The request reached a protected operation but lacks the required permission. */
public final class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
