package io.liftandshift.strikebench.paper;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.util.Json;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Append-only audit trail. Rejections land here (and only here) — zero account mutation. */
public final class AuditLog {

    private final Db db;
    private final Clock clock;

    public AuditLog(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public void log(String accountId, String tradeId, String action, String level, Map<String, Object> detail) {
        db.exec("INSERT INTO audit(ts, account_id, trade_id, action, level, detail_json) VALUES (?,?,?,?,?,?)",
                Instant.now(clock).toString(), accountId, tradeId, action, level,
                detail == null ? null : Json.write(detail));
    }

    public List<Map<String, Object>> forTrade(String tradeId, int limit) {
        return db.query("SELECT * FROM audit WHERE trade_id=? ORDER BY id DESC LIMIT ?", AuditLog::mapRow, tradeId, limit);
    }

    public List<Map<String, Object>> page(int page, int size) {
        int offset = Math.max(0, page) * size;
        return db.query("SELECT * FROM audit ORDER BY id DESC LIMIT ? OFFSET ?", AuditLog::mapRow, size, offset);
    }

    private static Map<String, Object> mapRow(Db.Row r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.lng("id"));
        m.put("ts", r.str("ts"));
        m.put("accountId", r.str("account_id"));
        m.put("tradeId", r.str("trade_id"));
        m.put("action", r.str("action"));
        m.put("level", r.str("level"));
        String detail = r.str("detail_json");
        m.put("detail", detail == null ? null : Json.read(detail, Map.class));
        return m;
    }
}
