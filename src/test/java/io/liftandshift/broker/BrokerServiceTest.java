package io.liftandshift.broker;

import io.liftandshift.db.Db;
import io.liftandshift.support.TestDb;
import io.liftandshift.market.ports.BrokerageProvider;
import io.liftandshift.paper.AuditLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrokerServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T15:30:00Z"), ZoneId.of("America/New_York"));

    @TempDir Path tmp;
    private Db db;
    private BrokerService service;
    private FakeBroker fake;

    static final class FakeBroker implements BrokerageProvider {
        final AtomicInteger placeCalls = new AtomicInteger();
        @Override public String name() { return "fake"; }
        @Override public boolean configured() { return true; }
        @Override public boolean connected() { return true; }
        @Override public String startConnect() { return "https://example.com/authorize"; }
        @Override public void verifyConnect(String code) {}
        @Override public List<BrokerAccount> accounts() { return List.of(); }
        @Override public BrokerBalance balance(String k) { return new BrokerBalance(k, 0, 0, 0, true); }
        @Override public List<BrokerPosition> positions(String k) { return List.of(); }
        final AtomicInteger previewCalls = new AtomicInteger();
        @Override public OrderPreview previewOrder(String k, Map<String, Object> p) {
            return new OrderPreview("pv-" + previewCalls.incrementAndGet(), -18135, 65, List.of());
        }
        @Override public OrderResult placeOrder(String k, Map<String, Object> p, String previewId, String clientOrderId) {
            placeCalls.incrementAndGet();
            return new OrderResult("529", "OPEN", List.of());
        }
        @Override public void cancelOrder(String k, String brokerOrderId) {}
        @Override public List<Map<String, Object>> orders(String k) { return List.of(); }
    }

    @BeforeEach
    void setUp() {
        db = TestDb.fresh();
        fake = new FakeBroker();
        service = new BrokerService(fake, db, new AuditLog(db, CLOCK), CLOCK);
    }

    @AfterEach
    void closeDb() {
        if (db != null) db.close();
    }

    private static final Map<String, Object> ORDER = Map.of("orderType", "SPREADS");

    @Test
    void placeRequiresPreviewConfirmTextAndClientOrderId() {
        assertThatThrownBy(() -> service.place("k", ORDER, "", "c1", BrokerService.CONFIRM_TEXT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("preview");
        assertThatThrownBy(() -> service.place("k", ORDER, "pv-1", "", BrokerService.CONFIRM_TEXT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("clientOrderId");
        assertThatThrownBy(() -> service.place("k", ORDER, "pv-1", "c1", "yes I am sure"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exact confirmation");
        assertThat(fake.placeCalls.get()).isZero();
    }

    @Test
    void placeIsIdempotentByClientOrderId() {
        String previewId = service.preview("k", ORDER).preview().previewId();
        BrokerService.PlaceOutcome first = service.place("k", ORDER, previewId, "client-42", BrokerService.CONFIRM_TEXT);
        assertThat(first.replay()).isFalse();
        assertThat(first.brokerOrderId()).isEqualTo("529");

        BrokerService.PlaceOutcome again = service.place("k", ORDER, previewId, "client-42", BrokerService.CONFIRM_TEXT);
        assertThat(again.replay()).isTrue();
        assertThat(again.brokerOrderId()).isEqualTo("529");
        assertThat(fake.placeCalls.get()).isEqualTo(1); // never re-sent
    }

    @Test
    void placeValidatesPreviewIdentityAndFreshness() {
        // Unknown previewId
        assertThatThrownBy(() -> service.place("k", ORDER, "pv-unknown", "c1", BrokerService.CONFIRM_TEXT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not match any recorded preview");
        // Previewed one order, placing a DIFFERENT one
        String previewId = service.preview("k", ORDER).preview().previewId();
        Map<String, Object> edited = Map.of("orderType", "SPREADS", "priceType", "MARKET");
        assertThatThrownBy(() -> service.place("k", edited, previewId, "c2", BrokerService.CONFIRM_TEXT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("differs from the one you previewed");
        // Preview from another account
        assertThatThrownBy(() -> service.place("otherAccount", ORDER, previewId, "c3", BrokerService.CONFIRM_TEXT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("different account");
        // Stale preview
        String stale = service.preview("k", ORDER).preview().previewId();
        db.exec("UPDATE live_orders SET created_at=? WHERE preview_id=?", "2026-07-08T00:00:00Z", stale);
        assertThatThrownBy(() -> service.place("k", ORDER, stale, "c4", BrokerService.CONFIRM_TEXT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("expired");
        assertThat(fake.placeCalls.get()).isZero();
    }

    @Test
    void sameClientOrderIdWithDifferentOrderIsHardError() {
        String previewId = service.preview("k", ORDER).preview().previewId();
        service.place("k", ORDER, previewId, "client-77", BrokerService.CONFIRM_TEXT);
        Map<String, Object> edited = Map.of("orderType", "SPREADS", "priceType", "LIMIT");
        String previewId2 = service.preview("k", edited).preview().previewId();
        assertThatThrownBy(() -> service.place("k", edited, previewId2, "client-77", BrokerService.CONFIRM_TEXT))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("DIFFERENT order");
        assertThat(fake.placeCalls.get()).isEqualTo(1);
    }

    @Test
    void cancelRecordsRequestNotTerminalFact() {
        String previewId = service.preview("k", ORDER).preview().previewId();
        service.place("k", ORDER, previewId, "client-9", BrokerService.CONFIRM_TEXT);
        service.cancel("k", "529");
        List<String> status = db.query("SELECT status FROM live_orders WHERE broker_order_id='529'", r -> r.str("status"));
        assertThat(status).containsExactly("CANCEL_REQUESTED");
    }

    @Test
    void previewPersistsLocalOrderRow() {
        BrokerService.PreviewOutcome outcome = service.preview("k", ORDER);
        assertThat(outcome.preview().previewId()).isEqualTo("pv-1");
        List<String> rows = db.query("SELECT status FROM live_orders", r -> r.str("status"));
        assertThat(rows).containsExactly("PREVIEWED");
    }

    @Test
    void unconfiguredBrokerRejectsEverything() {
        BrokerService none = new BrokerService(null, db, new AuditLog(db, CLOCK), CLOCK);
        assertThat(none.status().get("configured")).isEqualTo(false);
        assertThatThrownBy(none::accounts).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> none.place("k", ORDER, "pv", "c", BrokerService.CONFIRM_TEXT))
                .isInstanceOf(IllegalStateException.class);
    }
}
