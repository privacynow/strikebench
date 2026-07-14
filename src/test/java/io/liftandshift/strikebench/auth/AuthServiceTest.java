package io.liftandshift.strikebench.auth;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.paper.Account;
import io.liftandshift.strikebench.paper.AccountService;
import io.liftandshift.strikebench.paper.AuditLog;
import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Auth policy + user provisioning + per-user account scoping (no live Google round-trip). */
class AuthServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T15:30:00Z"), ZoneId.of("UTC"));

    private Db db;

    @AfterEach void close() { if (db != null) db.close(); }

    private final IdentityProvider stub = new IdentityProvider() {
        public URI authUrl(String state, String nonce) { return URI.create("https://issuer/authorize?state=" + state); }
        public VerifiedIdentity exchange(String code, String nonce) { return new VerifiedIdentity("sub", "u@x.com", true, "U"); }
    };

    private AuthService auth(Map<String, String> conf) {
        db = TestDb.fresh();
        return new AuthService(new AppConfig(new HashMap<>(conf)), db, CLOCK, stub);
    }

    private long userCount() {
        return db.query("SELECT count(*) AS c FROM users WHERE id NOT IN ('local','system')", r -> r.lng("c")).getFirst();
    }

    @Test void provisionsUsersIdempotently() {
        AuthService a = auth(Map.of());
        String uid = a.provisionUser(new VerifiedIdentity("sub-1", "a@x.com", true, "A"));
        assertThat(uid).isEqualTo("google:sub-1");
        assertThat(userCount()).isEqualTo(1);

        a.provisionUser(new VerifiedIdentity("sub-1", "a2@x.com", true, "A2")); // same sub -> update, not insert
        assertThat(userCount()).isEqualTo(1);
        assertThat(db.query("SELECT email FROM users WHERE id='google:sub-1'", r -> r.str("email")).getFirst())
                .isEqualTo("a2@x.com");
    }

    @Test void rejectsUnverifiedEmail() {
        AuthService a = auth(Map.of());
        assertThatThrownBy(() -> a.authorizeAndProvision(new VerifiedIdentity("s", "a@x.com", false, "A")))
                .isInstanceOf(UnauthorizedException.class);
        assertThat(userCount()).isZero();
    }

    @Test void enforcesEmailAllowlistCaseInsensitively() {
        AuthService a = auth(Map.of("AUTH_ALLOWED_EMAILS", "owner@x.com"));
        assertThatThrownBy(() -> a.authorizeAndProvision(new VerifiedIdentity("s", "intruder@x.com", true, "I")))
                .isInstanceOf(UnauthorizedException.class);
        assertThat(a.authorizeAndProvision(new VerifiedIdentity("s2", "Owner@X.com", true, "O")))
                .isEqualTo("google:s2");
    }

    @Test void behavesAsLocalUserWhenDisabled() {
        AuthService a = auth(Map.of()); // AUTH_ENABLED defaults false
        assertThat(a.enabled()).isFalse();
        assertThat(a.currentUserId(null)).isEqualTo(AuthService.LOCAL_USER);
        assertThat(a.me(null)).containsEntry("authenticated", true).containsEntry("authEnabled", false);
    }

    @Test void scopesAccountsPerUserAndLetsTheOwnerClaimTheLegacyAccount() {
        AuthService a = auth(Map.of());
        AccountService accounts = new AccountService(db, new AppConfig(new HashMap<>()), new AuditLog(db, CLOCK), CLOCK);

        Account legacy = accounts.getOrCreateDefault(); // pre-auth single account (user_id NULL)

        a.provisionUser(new VerifiedIdentity("owner", "o@x.com", true, "O"));
        Account owner = accounts.getOrCreateDefaultForUser("google:owner");
        assertThat(owner.id()).isEqualTo(legacy.id()); // owner adopts it — history preserved

        a.provisionUser(new VerifiedIdentity("other", "u2@x.com", true, "U2"));
        Account other = accounts.getOrCreateDefaultForUser("google:other");
        assertThat(other.id()).isNotEqualTo(legacy.id()); // second user gets a distinct account

        // Isolation: each user's account sees only its own id.
        assertThat(db.query("SELECT count(*) AS c FROM accounts WHERE type='PAPER'", r -> r.lng("c")).getFirst())
                .isEqualTo(2);
    }

    @Test void resetAccountTouchesOnlyTheTargetAccount() {
        auth(Map.of());
        AccountService accounts = new AccountService(db, new AppConfig(new HashMap<>()), new AuditLog(db, CLOCK), CLOCK);
        // Two users, two accounts (the first claims the legacy one, the second gets a fresh one).
        accounts.getOrCreateDefault();
        db.exec("INSERT INTO users(id,email,provider,subject,name,created_at,updated_at) VALUES "
                + "('google:a','a@x.com','google','a','A','2026-07-08T15:30:00Z','2026-07-08T15:30:00Z'),"
                + "('google:b','b@x.com','google','b','B','2026-07-08T15:30:00Z','2026-07-08T15:30:00Z')");
        Account a = accounts.getOrCreateDefaultForUser("google:a");
        Account b = accounts.getOrCreateDefaultForUser("google:b");

        accounts.resetAccount(a.id(), 5_000_000L, true, true); // reset ONLY A

        assertThat(db.query("SELECT cash_cents FROM accounts WHERE id=?", r -> r.lng("cash_cents"), a.id()).getFirst())
                .isEqualTo(5_000_000L);
        assertThat(db.query("SELECT cash_cents FROM accounts WHERE id=?", r -> r.lng("cash_cents"), b.id()).getFirst())
                .isEqualTo(b.cashCents()); // B untouched
    }
}
