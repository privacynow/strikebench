package io.liftandshift.strikebench.util;

/** A requested analysis cannot run because its active market lacks a required input. */
public final class DataUnavailableException extends IllegalStateException {
    public DataUnavailableException(String message) { super(message); }
    public DataUnavailableException(String message, Throwable cause) { super(message, cause); }
}
