package io.liftandshift.strikebench.market.ports;

import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.paper.OrderInstruction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * A live brokerage connection (E*TRADE). All money fields are String decimals or long cents —
 * plain types so DTOs serialize directly. The recommendation engine NEVER calls this port.
 */
public interface BrokerageProvider {

    String name();

    /** True when consumer key/secret are configured (not necessarily connected). */
    boolean configured();

    /** True when a valid access token exists for this session. */
    boolean connected();

    /** Begins OAuth; returns the URL the user must visit to authorize. */
    String startConnect();

    /** Completes OAuth with the verifier code the user pasted. */
    void verifyConnect(String verifierCode);

    List<BrokerAccount> accounts();

    BrokerBalance balance(String accountIdKey);

    List<BrokerPosition> positions(String accountIdKey);

    OrderPreview previewOrder(String accountIdKey, OrderCommand order);

    OrderResult placeOrder(String accountIdKey, OrderCommand order, String previewId,
                           String clientOrderId);

    void cancelOrder(String accountIdKey, String brokerOrderId);

    List<Map<String, Object>> orders(String accountIdKey);

    /**
     * Resolves an uncertain submission against the broker before StrikeBench considers any retry.
     * NOT_FOUND is a broker response, not permission to resubmit automatically; the original call
     * may still be propagating through the broker.
     */
    OrderLookup findOrder(String accountIdKey, String clientOrderId, String brokerOrderId);

    record BrokerAccount(String accountIdKey, String accountId, String name, String type, String status) {}

    record BrokerBalance(String accountIdKey, Long cashCents, Long buyingPowerCents,
                         Long netAccountValueCents, boolean realTime, Long sourceObservedAtEpochMs) {}

    record BrokerPosition(String symbol, String description, String positionType, double quantity,
                          Long marketValueCents, Long costBasisCents, Long sourceObservedAtEpochMs) {}

    record OrderPreview(String previewId, Long estimatedTotalCents, Long estimatedCommissionCents,
                        List<String> messages, Long sourceObservedAtEpochMs) {}

    record OrderResult(String brokerOrderId, String status, List<String> messages) {}

    /**
     * Provider-neutral order command derived from the canonical Practice package. It contains no
     * alternate pricing or risk math: the signed package limit and package-price fingerprint are
     * copied from the exact TradePreview receipt, and the adapter only translates protocol units.
     */
    record OrderCommand(String symbol, int quantity, List<OrderLeg> legs,
                        OrderInstruction orderInstruction, String packagePriceFingerprint) {
        public OrderCommand {
            if (symbol == null || symbol.isBlank()) {
                throw new IllegalArgumentException("live order symbol is required");
            }
            if (quantity < 1 || quantity > 100) {
                throw new IllegalArgumentException("live order quantity must be 1..100");
            }
            legs = legs == null ? List.of() : List.copyOf(legs);
            if (legs.isEmpty()) throw new IllegalArgumentException("live order legs are required");
            if (orderInstruction == null) {
                throw new IllegalArgumentException("live orders require an explicit order instruction");
            }
            if (packagePriceFingerprint == null || packagePriceFingerprint.isBlank()) {
                throw new IllegalArgumentException(
                        "live orders require the canonical package-price fingerprint");
            }
        }
    }

    record OrderLeg(LegAction action, OptionType type, BigDecimal strike, LocalDate expiration,
                    int ratio, int multiplier) {
        public OrderLeg {
            if (action == null) throw new IllegalArgumentException("live order leg action is required");
            if (ratio < 1) throw new IllegalArgumentException("live order leg ratio must be positive");
            if (multiplier < 1) {
                throw new IllegalArgumentException("live order leg multiplier must be positive");
            }
            if (type == null) {
                if (strike != null || expiration != null) {
                    throw new IllegalArgumentException(
                            "a live stock leg cannot carry option terms");
                }
            } else if (strike == null || strike.signum() <= 0 || expiration == null) {
                throw new IllegalArgumentException(
                        "a live option leg requires a positive strike and expiration");
            }
        }

        public boolean stock() {
            return type == null;
        }
    }

    enum LookupStatus { FOUND, NOT_FOUND, UNAVAILABLE }

    record OrderLookup(LookupStatus lookupStatus, OrderResult order, List<String> messages) {
        public OrderLookup {
            if (lookupStatus == null) lookupStatus = LookupStatus.UNAVAILABLE;
            messages = messages == null ? List.of() : List.copyOf(messages);
            if ((lookupStatus == LookupStatus.FOUND) != (order != null)) {
                throw new IllegalArgumentException(
                        "FOUND reconciliation and a broker order must occur together");
            }
            if (lookupStatus == LookupStatus.FOUND
                    && (order.brokerOrderId() == null || order.brokerOrderId().isBlank())) {
                throw new IllegalArgumentException(
                        "a reconciled broker order requires its broker order id");
            }
        }

        public static OrderLookup found(OrderResult order) {
            return new OrderLookup(LookupStatus.FOUND, order, List.of());
        }

        public static OrderLookup notFound() {
            return new OrderLookup(LookupStatus.NOT_FOUND, null, List.of());
        }

        public static OrderLookup unavailable(String reason) {
            return new OrderLookup(LookupStatus.UNAVAILABLE, null,
                    reason == null || reason.isBlank() ? List.of() : List.of(reason));
        }
    }
}
