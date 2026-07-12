package io.liftandshift.strikebench.auth;

/** A cryptographically-verified identity from the OIDC provider (Google). */
public record VerifiedIdentity(String subject, String email, boolean emailVerified, String name) {}
