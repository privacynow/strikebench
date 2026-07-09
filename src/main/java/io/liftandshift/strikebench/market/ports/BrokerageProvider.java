package io.liftandshift.strikebench.market.ports;

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

    OrderPreview previewOrder(String accountIdKey, Map<String, Object> orderPayload);

    OrderResult placeOrder(String accountIdKey, Map<String, Object> orderPayload, String previewId, String clientOrderId);

    void cancelOrder(String accountIdKey, String brokerOrderId);

    List<Map<String, Object>> orders(String accountIdKey);

    record BrokerAccount(String accountIdKey, String accountId, String name, String type, String status) {}

    record BrokerBalance(String accountIdKey, long cashCents, long buyingPowerCents, long netAccountValueCents, boolean realTime) {}

    record BrokerPosition(String symbol, String description, String positionType, double quantity, long marketValueCents, long costBasisCents) {}

    record OrderPreview(String previewId, long estimatedTotalCents, long estimatedCommissionCents, List<String> messages) {}

    record OrderResult(String brokerOrderId, String status, List<String> messages) {}
}
