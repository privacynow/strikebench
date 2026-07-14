package io.liftandshift.strikebench.auth;

import io.javalin.http.Context;
import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.util.OwnerScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Google-OIDC session authentication and per-user identity. When auth is disabled (the default),
 * everything runs as a single implicit {@link #LOCAL_USER} — behaviour is byte-identical to the
 * pre-auth app. When enabled, /api/* is gated behind a signed-in user and each user is scoped to
 * their own paper account.
 *
 * Security posture: CSRF-safe login (random state compared on callback), replay-safe ID tokens
 * (nonce validated by the provider), verified-email requirement, optional email allowlist, session
 * fixation defense (session id rotated on login), and constant-time state comparison. All token and
 * signature validation is delegated to Nimbus via {@link IdentityProvider}.
 */
public final class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** The implicit user when auth is disabled; owns the single legacy paper account. */
    public static final String LOCAL_USER = OwnerScope.LOCAL;

    private static final String SESSION_UID = "uid";
    private static final String SESSION_STATE = "oidc_state";
    private static final String SESSION_NONCE = "oidc_nonce";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AppConfig cfg;
    private final Db db;
    private final Clock clock;
    private final IdentityProvider provider; // null when disabled
    private final Set<String> allowed;       // lowercased; empty = any verified email

    public AuthService(AppConfig cfg, Db db, Clock clock, IdentityProvider provider) {
        this.cfg = cfg;
        this.db = db;
        this.clock = clock;
        this.provider = provider;
        this.allowed = new HashSet<>(cfg.authAllowedEmails());
    }

    public boolean enabled() { return cfg.authEnabled(); }

    /** {@link #LOCAL_USER} when auth is off; the signed-in user's id, or null when not signed in. */
    public String currentUserId(Context ctx) {
        if (!enabled()) return LOCAL_USER;
        return ctx.sessionAttribute(SESSION_UID);
    }

    /** Gate for protected routes: throws (-> 401) when auth is on and no user is signed in. */
    public void requireUser(Context ctx) {
        if (!enabled()) return;
        if (ctx.sessionAttribute(SESSION_UID) == null) throw new UnauthorizedException("Sign in to continue");
    }

    /** Sends the browser to Google with a fresh state + nonce bound to the session. */
    public void startLogin(Context ctx) {
        if (!enabled() || provider == null) { ctx.redirect(cfg.authPostLoginUrl()); return; }
        String state = randomToken();
        String nonce = randomToken();
        ctx.sessionAttribute(SESSION_STATE, state);
        ctx.sessionAttribute(SESSION_NONCE, nonce);
        ctx.redirect(provider.authUrl(state, nonce).toString());
    }

    /** OIDC redirect target: verify state, exchange the code, enforce the allowlist, then sign in. */
    public void callback(Context ctx) {
        if (!enabled() || provider == null) { ctx.redirect(cfg.authPostLoginUrl()); return; }
        String returnedError = ctx.queryParam("error");
        if (returnedError != null) throw new UnauthorizedException("Google sign-in did not complete");

        String code = ctx.queryParam("code");
        String state = ctx.queryParam("state");
        String expectedState = ctx.sessionAttribute(SESSION_STATE);
        String expectedNonce = ctx.sessionAttribute(SESSION_NONCE);
        // One-shot: clear the transient flow values regardless of outcome.
        ctx.sessionAttribute(SESSION_STATE, null);
        ctx.sessionAttribute(SESSION_NONCE, null);

        if (code == null || state == null || expectedState == null || !constantTimeEquals(state, expectedState)) {
            throw new UnauthorizedException("Invalid or expired sign-in request");
        }
        VerifiedIdentity identity;
        try {
            identity = provider.exchange(code, expectedNonce);
        } catch (Exception e) {
            log.warn("Sign-in validation failed; try signing in again");
            log.debug("Sign-in validation detail", e);
            throw new UnauthorizedException("Could not verify the Google sign-in");
        }
        String uid = authorizeAndProvision(identity);

        // Session fixation defense: rotate the session id, then bind the user.
        try { ctx.req().changeSessionId(); } catch (Exception ignored) { /* best-effort */ }
        ctx.sessionAttribute(SESSION_UID, uid);
        ctx.redirect(cfg.authPostLoginUrl());
    }

    /** Enforces the verified-email + allowlist policy and upserts the user; returns the user id. */
    public String authorizeAndProvision(VerifiedIdentity identity) {
        if (identity.email() == null || !identity.emailVerified()) {
            throw new UnauthorizedException("A verified Google email is required");
        }
        if (!allowed.isEmpty() && !allowed.contains(identity.email().toLowerCase(Locale.ROOT))) {
            log.warn("sign-in denied for {} — not on AUTH_ALLOWED_EMAILS", identity.email());
            throw new UnauthorizedException("This account is not permitted to use this app");
        }
        return provisionUser(identity);
    }

    /** Upserts the users row and returns the stable user id ("google:<sub>"). */
    public String provisionUser(VerifiedIdentity identity) {
        String uid = "google:" + identity.subject();
        String now = Instant.now(clock).toString();
        db.exec("INSERT INTO users(id,email,provider,subject,name,created_at,updated_at) VALUES (?,?,?,?,?,?,?) "
              + "ON CONFLICT(id) DO UPDATE SET email=excluded.email, name=excluded.name, updated_at=excluded.updated_at",
                uid, identity.email(), "google", identity.subject(), identity.name(), now, now);
        return uid;
    }

    public void logout(Context ctx) {
        ctx.sessionAttribute(SESSION_UID, null);
        try {
            var session = ctx.req().getSession(false);
            if (session != null) session.invalidate();
        } catch (Exception ignored) { /* best-effort */ }
        ctx.redirect(cfg.authPostLoginUrl());
    }

    /** What the SPA needs to decide whether to show the sign-in screen. */
    public Map<String, Object> me(Context ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("authEnabled", enabled());
        if (!enabled()) {
            out.put("authenticated", true);
            out.put("user", Map.of("id", LOCAL_USER, "name", "Local user"));
            return out;
        }
        String uid = ctx.sessionAttribute(SESSION_UID);
        if (uid == null) {
            out.put("authenticated", false);
            out.put("loginUrl", "/auth/login");
            return out;
        }
        out.put("authenticated", true);
        out.put("user", userView(uid));
        out.put("logoutUrl", "/auth/logout");
        return out;
    }

    private Map<String, Object> userView(String uid) {
        var rows = db.query("SELECT id,email,name FROM users WHERE id=?", r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.str("id"));
            m.put("email", r.str("email"));
            m.put("name", r.str("name"));
            return m;
        }, uid);
        return rows.isEmpty() ? Map.of("id", uid) : rows.getFirst();
    }

    private static String randomToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
