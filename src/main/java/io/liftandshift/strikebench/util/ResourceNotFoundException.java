package io.liftandshift.strikebench.util;

/** Deliberate, user-addressable resource absence. Internal empty Optionals must not use this. */
public final class ResourceNotFoundException extends java.util.NoSuchElementException {
    public ResourceNotFoundException(String message) { super(message); }
}
