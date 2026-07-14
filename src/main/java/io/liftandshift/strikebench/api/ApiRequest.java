package io.liftandshift.strikebench.api;

import io.javalin.http.Context;

/** Shared HTTP input rules; domain controllers must not invent their own null or number semantics. */
final class ApiRequest {
    private ApiRequest() {}

    static <T> T bodyOrNull(Context ctx, Class<T> type) {
        String raw = ctx.body();
        if (raw == null || raw.isBlank() || raw.trim().equals("null")) return null;
        return ctx.bodyAsClass(type);
    }

    static <T> T requireBody(T body) {
        if (body == null) throw new IllegalArgumentException("request body is required");
        return body;
    }

    static int intParam(Context ctx, String name, int defaultValue) {
        String value = ctx.queryParam(name);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
    }
}
