package io.liftandshift.strikebench.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Shared Jackson mapper. API DTOs use plain types (String/double/long/list/map) so wire formats stay stable. */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)
            .configure(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    /**
     * The HTTP request boundary. A caller stating quantity 2.5 into a whole-unit field must be
     * REFUSED, never silently truncated to 2 — the tracked ledger is factual and Jackson's
     * default float-to-int coercion let it record a different quantity than the caller stated.
     * Only integral targets are strict; decimal fields (prices, rates) are untouched. Persisted
     * JSON keeps reading through {@link #MAPPER}: stored rows may legitimately carry
     * browser-written "2.0"-style integers and re-reading history is not the place to refuse them.
     */
    public static final ObjectMapper API_STRICT = MAPPER.copy();
    static {
        API_STRICT.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Integer)
                .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,
                        com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
    }

    private static final ObjectMapper CANONICAL = MAPPER.copy()
            .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private Json() {}

    /** Deterministic serialization (sorted map keys) — for comparing payload identity. */
    public static String canonical(Object o) {
        try { return CANONICAL.writeValueAsString(o); }
        catch (Exception e) { throw new IllegalStateException("JSON write failed", e); }
    }

    public static String write(Object o) {
        try { return MAPPER.writeValueAsString(o); }
        catch (Exception e) { throw new IllegalStateException("JSON write failed", e); }
    }

    public static <T> T read(String s, Class<T> type) {
        try { return MAPPER.readValue(s, type); }
        catch (Exception e) { throw new IllegalArgumentException("JSON read failed: " + e.getMessage(), e); }
    }

    public static <T> T read(String s, TypeReference<T> type) {
        try { return MAPPER.readValue(s, type); }
        catch (Exception e) { throw new IllegalArgumentException("JSON read failed: " + e.getMessage(), e); }
    }

    public static JsonNode parse(String s) {
        try { return MAPPER.readTree(s); }
        catch (Exception e) { throw new IllegalArgumentException("JSON parse failed: " + e.getMessage(), e); }
    }

    public static ObjectNode obj() { return MAPPER.createObjectNode(); }
    public static ArrayNode arr() { return MAPPER.createArrayNode(); }
}
