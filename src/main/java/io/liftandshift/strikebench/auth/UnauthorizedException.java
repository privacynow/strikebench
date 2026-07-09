package io.liftandshift.strikebench.auth;

/** Thrown by the auth gate when a protected route is reached without a signed-in user (-> 401). */
public final class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) { super(message); }
}
