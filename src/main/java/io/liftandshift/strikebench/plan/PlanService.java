package io.liftandshift.strikebench.plan;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.strategy.StrategyIntent;
import io.liftandshift.strikebench.util.EventBus;
import io.liftandshift.strikebench.util.Ids;
import io.liftandshift.strikebench.util.Json;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/** Server-owned Plan lifecycle with owner isolation, versioned assumptions and optimistic revisions. */
public final class PlanService {
    public static final String CONTEXT_ENGINE_VERSION = "plan-context-1";

    private final Db db;
    private final Clock clock;
    private EventBus events;

    public PlanService(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public void setEvents(EventBus events) { this.events = events; }

    public Plan.View create(String userId, Plan.MarketKind marketKind, String worldId, String accountId,
                            Plan.CreateRequest raw) {
        if (raw == null) throw new IllegalArgumentException("plan body is required");
        String requestId = required(raw.clientRequestId(), "clientRequestId");
        if (requestId.length() > 120) throw new IllegalArgumentException("clientRequestId is too long");
        String symbol = normalizeSymbol(raw.symbol());
        String intent = normalizeIntent(raw.intent(), true);
        Plan.MarketKind market = Objects.requireNonNull(marketKind, "marketKind");
        String resolvedWorld = market == Plan.MarketKind.SIMULATED ? required(worldId, "worldId") : null;
        validateContext(raw.horizonDays(), raw.targetCents(), raw.riskMode(), raw.holdingsShares(),
                raw.costBasisCents(), raw.priceAssumptionCents());
        String origin = blankToNull(raw.originPlanId());
        String id = Ids.newId("plan");
        String contextId = Ids.newId("pctx");
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        String title = cleanTitle(raw.title());
        String hash = contextHash(symbol, intent, market, resolvedWorld, raw.thesis(), raw.horizonDays(),
                raw.targetCents(), raw.riskMode(), raw.holdingsShares(), raw.costBasisCents(),
                raw.priceAssumptionCents());
        String createHash = createInputHash(hash, origin, title, accountId);
        String ownerKey = userId == null ? "__local__" : userId;

        String createdId = db.tx(c -> {
            lockCreateOn(c, ownerKey, "request:" + requestId);
            List<ExistingRequest> existing = existingRequestOn(c, requestId, userId);
            if (!existing.isEmpty()) return requireSameRequest(existing.getFirst(), createHash);
            lockCreateOn(c, ownerKey, "content:" + createHash);
            List<String> equivalent = activeEquivalentOn(c, userId, hash, origin, title, accountId);
            if (!equivalent.isEmpty()) {
                recordRequestOn(c, ownerKey, requestId, createHash, equivalent.getFirst(), now);
                return equivalent.getFirst();
            }
            if (origin != null) requireOwnedOn(c, origin, userId, false);
            int inserted = Db.execOn(c, "INSERT INTO plans(id,user_id,client_request_id,create_input_hash,origin_plan_id,symbol,intent,market_kind," +
                            "world_id,account_id,custom_title,status,active_stage,active_context_rev,version,is_open," +
                            "created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,NULL,1,1,?,?) ON CONFLICT DO NOTHING",
                    id, userId, requestId, createHash, origin, symbol, intent, market.name(), resolvedWorld,
                    accountId, title, Plan.Status.ACTIVE.name(), Plan.Stage.UNDERSTAND.name(), now, now);
            if (inserted == 0) {
                List<ExistingRequest> raced = existingRequestOn(c, requestId, userId);
                if (raced.isEmpty()) throw new IllegalStateException("The plan request conflicted with another record");
                return requireSameRequest(raced.getFirst(), createHash);
            }
            Db.execOn(c, "INSERT INTO plan_context_revision(id,plan_id,rev,thesis,horizon_days,target_cents," +
                            "risk_mode,holdings_shares,cost_basis_cents,price_assumption_cents,input_hash," +
                            "engine_version,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    contextId, id, 1, cleanThesis(raw.thesis()), raw.horizonDays(), raw.targetCents(),
                    normalizeRisk(raw.riskMode()), raw.holdingsShares(), raw.costBasisCents(),
                    raw.priceAssumptionCents(), hash, CONTEXT_ENGINE_VERSION, now);
            Db.execOn(c, "UPDATE plans SET active_context_rev=1 WHERE id=?", id);
            recordRequestOn(c, ownerKey, requestId, createHash, id, now);
            return id;
        });
        Plan.View out = get(userId, createdId);
        announce(out);
        return out;
    }

    public Plan.View get(String userId, String planId) {
        return db.with(c -> selectViewOn(c, planId, userId, false));
    }

    public List<Plan.View> list(String userId, Plan.MarketKind market, String worldId, boolean openOnly) {
        StringBuilder sql = new StringBuilder(viewSelect())
                .append(" WHERE ").append(ownerClause("p.user_id"));
        List<Object> params = new ArrayList<>();
        params.add(userId);
        params.add(userId);
        if (market != null) {
            sql.append(" AND p.market_kind=?"); params.add(market.name());
            if (market == Plan.MarketKind.SIMULATED) {
                sql.append(" AND p.world_id=?"); params.add(required(worldId, "worldId"));
            } else sql.append(" AND p.world_id IS NULL");
        }
        if (openOnly) sql.append(" AND p.is_open=1 AND p.status<>'ARCHIVED'");
        sql.append(" ORDER BY p.updated_at DESC, p.created_at DESC");
        return db.query(sql.toString(), PlanService::mapView, params.toArray());
    }

    public Plan.View updateContext(String userId, String planId, Plan.ContextUpdateRequest raw) {
        if (raw == null || raw.expectedVersion() == null) {
            throw new IllegalArgumentException("expectedVersion is required");
        }
        Plan.View changed = db.tx(c -> {
            Plan.View current = selectViewOn(c, planId, userId, true);
            requireVersion(current, raw.expectedVersion());
            if (!current.assumptionsEditable()) {
                throw new IllegalStateException("This plan has a frozen decision. Start a linked plan to revise its assumptions without rewriting history.");
            }
            Set<String> clear = raw.clear() == null ? Set.of() : raw.clear();
            Plan.ContextRevision old = current.context();
            String thesis = merged("thesis", raw.thesis(), old.thesis(), clear);
            Integer horizon = merged("horizonDays", raw.horizonDays(), old.horizonDays(), clear);
            Long target = merged("targetCents", raw.targetCents(), old.targetCents(), clear);
            String risk = merged("riskMode", raw.riskMode(), old.riskMode(), clear);
            Long shares = merged("holdingsShares", raw.holdingsShares(), old.holdingsShares(), clear);
            Long basis = merged("costBasisCents", raw.costBasisCents(), old.costBasisCents(), clear);
            Long assumption = merged("priceAssumptionCents", raw.priceAssumptionCents(),
                    old.priceAssumptionCents(), clear);
            validateContext(horizon, target, risk, shares, basis, assumption);
            thesis = cleanThesis(thesis);
            risk = normalizeRisk(risk);
            String hash = contextHash(current.symbol(), current.intent(), current.marketKind(), current.worldId(),
                    thesis, horizon, target, risk, shares, basis, assumption);
            if (hash.equals(old.inputHash())) return current;
            int nextRev = old.rev() + 1;
            OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            Db.execOn(c, "INSERT INTO plan_context_revision(id,plan_id,rev,thesis,horizon_days,target_cents," +
                            "risk_mode,holdings_shares,cost_basis_cents,price_assumption_cents,input_hash," +
                            "engine_version,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Ids.newId("pctx"), planId, nextRev, thesis, horizon, target, risk, shares, basis,
                    assumption, hash, CONTEXT_ENGINE_VERSION, now);
            markDependentsStale(c, planId);
            Db.execOn(c, "UPDATE plans SET active_context_rev=?, version=version+1, updated_at=? WHERE id=?",
                    nextRev, now, planId);
            return selectViewOn(c, planId, userId, false);
        });
        announce(changed);
        return changed;
    }

    public Plan.View claimIntent(String userId, String planId, Plan.IntentRequest raw) {
        if (raw == null || raw.expectedVersion() == null) throw new IllegalArgumentException("expectedVersion is required");
        String intent = normalizeIntent(raw.intent(), false);
        Plan.View changed = db.tx(c -> {
            Plan.View current = selectViewOn(c, planId, userId, true);
            requireVersion(current, raw.expectedVersion());
            if (current.intent() != null) {
                if (current.intent().equals(intent)) return current;
                boolean hasDecision = !Db.queryOn(c,
                        "SELECT 1 ok FROM plan_decision WHERE plan_id=? LIMIT 1", r -> r.intv("ok"), planId).isEmpty();
                if (hasDecision || current.status() != Plan.Status.ACTIVE) {
                    throw new IllegalStateException("This plan has a frozen decision. Start a linked plan to change its goal without rewriting history.");
                }
            }
            Plan.ContextRevision old = current.context();
            String hash = contextHash(current.symbol(), intent, current.marketKind(), current.worldId(),
                    old.thesis(), old.horizonDays(), old.targetCents(), old.riskMode(), old.holdingsShares(),
                    old.costBasisCents(), old.priceAssumptionCents());
            OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            int nextRev = old.rev() + 1;
            Db.execOn(c, "INSERT INTO plan_context_revision(id,plan_id,rev,thesis,horizon_days,target_cents," +
                            "risk_mode,holdings_shares,cost_basis_cents,price_assumption_cents,input_hash," +
                            "engine_version,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Ids.newId("pctx"), planId, nextRev, old.thesis(), old.horizonDays(), old.targetCents(),
                    old.riskMode(), old.holdingsShares(), old.costBasisCents(), old.priceAssumptionCents(),
                    hash, CONTEXT_ENGINE_VERSION, now);
            markDependentsStale(c, planId);
            Db.execOn(c, "UPDATE plans SET intent=?, active_stage='STRATEGY', active_context_rev=?, "
                            + "version=version+1, updated_at=? WHERE id=?",
                    intent, nextRev, now, planId);
            return selectViewOn(c, planId, userId, false);
        });
        announce(changed);
        return changed;
    }

    public Plan.View setStage(String userId, String planId, Plan.StageRequest raw) {
        if (raw == null || raw.expectedVersion() == null) throw new IllegalArgumentException("expectedVersion is required");
        Plan.Stage stage = parseStage(raw.stage());
        Plan.View changed = db.tx(c -> {
            Plan.View current = selectViewOn(c, planId, userId, true);
            requireVersion(current, raw.expectedVersion());
            if (stage == Plan.Stage.MANAGE_REVIEW && current.status() != Plan.Status.DECIDED_CASH
                    && current.status() != Plan.Status.POSITION_OPEN && current.status() != Plan.Status.CLOSED
                    && Db.queryOn(c, "SELECT 1 ok FROM plan_link WHERE plan_id=? AND role='REHEARSAL' LIMIT 1",
                            r -> r.intv("ok"), planId).isEmpty()) {
                throw new IllegalStateException("Manage & Review unlocks after a trade, cash decision, or rehearsal exists");
            }
            if (stage == current.activeStage()) return current;
            OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            Db.execOn(c, "UPDATE plans SET active_stage=?, version=version+1, updated_at=? WHERE id=?",
                    stage.name(), now, planId);
            return selectViewOn(c, planId, userId, false);
        });
        announce(changed);
        return changed;
    }

    public Plan.View setOpen(String userId, String planId, Plan.OpenRequest raw) {
        if (raw == null || raw.expectedVersion() == null || raw.open() == null) {
            throw new IllegalArgumentException("expectedVersion and open are required");
        }
        Plan.View changed = db.tx(c -> {
            Plan.View current = selectViewOn(c, planId, userId, true);
            requireVersion(current, raw.expectedVersion());
            if (Boolean.TRUE.equals(raw.open()) && current.status() == Plan.Status.ARCHIVED) {
                throw new IllegalStateException("Archived plans stay read-only. Review it from the Plan library.");
            }
            if (current.open() == raw.open()) return current;
            OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            Db.execOn(c, "UPDATE plans SET is_open=?, version=version+1, updated_at=? WHERE id=?",
                    raw.open() ? 1 : 0, now, planId);
            return selectViewOn(c, planId, userId, false);
        });
        announce(changed);
        return changed;
    }

    /** A finished simulated exchange cannot be entered again. Remove its Plans from the open
     * collection atomically with the terminal session state; their artifacts remain in the
     * all-market library and the session report remains the review surface. */
    public void closeFinishedWorldPlansOn(java.sql.Connection c, String userId, String worldId)
            throws java.sql.SQLException {
        Db.execOn(c, "UPDATE plans SET is_open=0,version=version+1,updated_at=now() "
                        + "WHERE market_kind='SIMULATED' AND world_id=? AND is_open=1 AND "
                        + ownerClause("user_id"), worldId, userId, userId);
    }

    public Plan.View archive(String userId, String planId, Plan.ArchiveRequest raw) {
        if (raw == null || raw.expectedVersion() == null) throw new IllegalArgumentException("expectedVersion is required");
        Plan.View changed = db.tx(c -> {
            Plan.View current = selectViewOn(c, planId, userId, true);
            requireVersion(current, raw.expectedVersion());
            if (current.status() == Plan.Status.POSITION_OPEN) {
                throw new IllegalStateException("Close or transfer the open position before archiving this plan");
            }
            if (current.status() == Plan.Status.ARCHIVED) return current;
            OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            Db.execOn(c, "UPDATE plans SET status='ARCHIVED', is_open=0, version=version+1, updated_at=? WHERE id=?",
                    now, planId);
            return selectViewOn(c, planId, userId, false);
        });
        announce(changed);
        return changed;
    }

    /** Typed relationship between two owner-matched Plans; idempotent for retry-safe Scout spawning. */
    public void linkRelated(String userId, String planId, String relatedPlanId, String rawRole) {
        String role = required(rawRole, "role").toUpperCase(Locale.ROOT);
        if (!Set.of("PEER", "ALTERNATIVE", "HEDGE", "COMPARISON").contains(role)) {
            throw new IllegalArgumentException("related Plan role must be PEER, ALTERNATIVE, HEDGE, or COMPARISON");
        }
        if (planId.equals(relatedPlanId)) throw new IllegalArgumentException("a Plan cannot link to itself");
        db.tx(c -> {
            requireOwnedOn(c, planId, userId, true);
            requireOwnedOn(c, relatedPlanId, userId, false);
            Db.execOn(c, "INSERT INTO plan_link(id,plan_id,role,related_plan_id,created_at) VALUES(?,?,?,?,?) " +
                            "ON CONFLICT (plan_id,role,related_plan_id) WHERE related_plan_id IS NOT NULL DO NOTHING",
                    Ids.newId("plink"), planId, role, relatedPlanId,
                    OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
            return null;
        });
    }

    private static String viewSelect() {
        return "SELECT p.id,p.origin_plan_id,p.symbol,p.intent,p.market_kind,p.world_id,p.account_id," +
                "p.custom_title,p.status,p.active_stage,p.version,p.is_open,p.created_at::text p_created," +
                "p.updated_at::text p_updated,CASE WHEN p.status='ACTIVE' AND NOT EXISTS " +
                "(SELECT 1 FROM plan_decision pd WHERE pd.plan_id=p.id) THEN 1 ELSE 0 END assumptions_editable," +
                "c.id context_id,c.rev context_rev,c.thesis,c.horizon_days," +
                "c.target_cents,c.risk_mode,c.holdings_shares,c.cost_basis_cents,c.price_assumption_cents," +
                "c.input_hash,c.engine_version,c.created_at::text c_created FROM plans p " +
                "JOIN plan_context_revision c ON c.plan_id=p.id AND c.rev=p.active_context_rev";
    }

    private static Plan.View selectViewOn(java.sql.Connection c, String planId, String userId,
                                          boolean forUpdate) throws java.sql.SQLException {
        String sql = viewSelect() + " WHERE p.id=? AND " + ownerClause("p.user_id")
                + (forUpdate ? " FOR UPDATE OF p" : "");
        List<Plan.View> rows = Db.queryOn(c, sql, PlanService::mapView, planId, userId, userId);
        if (rows.isEmpty()) throw new NoSuchElementException("no such plan: " + planId);
        return rows.getFirst();
    }

    private static void requireOwnedOn(java.sql.Connection c, String planId, String userId,
                                       boolean forUpdate) throws java.sql.SQLException {
        String sql = "SELECT id FROM plans WHERE id=? AND " + ownerClause("user_id") + (forUpdate ? " FOR UPDATE" : "");
        if (Db.queryOn(c, sql, r -> r.str("id"), planId, userId, userId).isEmpty()) {
            throw new NoSuchElementException("no such plan: " + planId);
        }
    }

    private static Plan.View mapView(Db.Row r) {
        Plan.ContextRevision context = new Plan.ContextRevision(r.str("context_id"), r.intv("context_rev"),
                r.str("thesis"), integerOrNull(r, "horizon_days"), r.lngOrNull("target_cents"),
                r.str("risk_mode"), r.lngOrNull("holdings_shares"), r.lngOrNull("cost_basis_cents"),
                r.lngOrNull("price_assumption_cents"), r.str("input_hash"), r.str("engine_version"),
                r.str("c_created"));
        String custom = cleanTitle(r.str("custom_title"));
        return new Plan.View(r.str("id"), r.str("origin_plan_id"), r.str("symbol"), r.str("intent"),
                Plan.MarketKind.valueOf(r.str("market_kind")), r.str("world_id"), r.str("account_id"),
                custom == null ? derivedTitle(r.str("symbol"), r.str("intent"), context) : custom,
                Plan.Status.valueOf(r.str("status")), Plan.Stage.valueOf(r.str("active_stage")),
                r.lng("version"), r.bool("is_open"), r.bool("assumptions_editable"), context,
                r.str("p_created"), r.str("p_updated"));
    }

    private static Integer integerOrNull(Db.Row r, String column) {
        Long n = r.lngOrNull(column);
        return n == null ? null : Math.toIntExact(n);
    }

    private static void markDependentsStale(java.sql.Connection c, String planId) throws java.sql.SQLException {
        for (String table : List.of("plan_evidence", "plan_ensemble", "plan_strategy_run", "plan_candidate", "plan_backtest")) {
            Db.execOn(c, "UPDATE " + table + " SET state='STALE' WHERE plan_id=? AND state='CURRENT'", planId);
        }
    }

    private static void requireVersion(Plan.View current, long expected) {
        if (current.version() != expected) {
            throw new IllegalStateException("This plan changed in another tab (expected version " + expected
                    + ", current " + current.version() + "). Reload it before saving.");
        }
    }

    private void announce(Plan.View view) {
        if (events == null || view == null) return;
        // Local identity is explicit because Map.of rejects null and SSE owner filters use the same token.
        String owner = db.query("SELECT COALESCE(user_id,'local') owner_key FROM plans WHERE id=?",
                r -> r.str("owner_key"), view.id()).stream().findFirst().orElse("local");
        events.publish("plan.updated", Map.of("id", view.id(), "version", view.version(),
                "market", view.marketKind().name(), "world", view.worldId() == null ? "" : view.worldId(),
                "user", owner));
    }

    private static String contextHash(String symbol, String intent, Plan.MarketKind market, String world,
                                      String thesis, Integer horizon, Long target, String risk, Long shares,
                                      Long costBasis, Long assumption) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("symbol", symbol); values.put("intent", intent); values.put("market", market.name());
        values.put("world", world); values.put("thesis", cleanThesis(thesis)); values.put("horizonDays", horizon);
        values.put("targetCents", target); values.put("riskMode", normalizeRisk(risk));
        values.put("holdingsShares", shares); values.put("costBasisCents", costBasis);
        values.put("priceAssumptionCents", assumption); values.put("engineVersion", CONTEXT_ENGINE_VERSION);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Json.canonical(values).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not identify the plan context", e);
        }
    }

    private static String createInputHash(String contextHash, String origin, String title, String accountId) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("contextHash", contextHash);
        values.put("originPlanId", origin);
        values.put("title", title);
        values.put("accountId", accountId);
        return sha256(values);
    }

    private static String sha256(Map<String, Object> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Json.canonical(values).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not identify the plan request", e);
        }
    }

    private static List<ExistingRequest> existingRequestOn(java.sql.Connection c, String requestId,
                                                            String userId) throws java.sql.SQLException {
        String ownerKey = userId == null ? "__local__" : userId;
        return Db.queryOn(c, "SELECT plan_id,input_hash FROM plan_create_request " +
                        "WHERE owner_key=? AND client_request_id=?",
                r -> new ExistingRequest(r.str("plan_id"), r.str("input_hash")), ownerKey, requestId);
    }

    private static void lockCreateOn(java.sql.Connection c, String ownerKey, String key)
            throws java.sql.SQLException {
        Db.queryOn(c, "SELECT pg_advisory_xact_lock(hashtext(?),hashtext(?)),1 ok",
                r -> r.intv("ok"), ownerKey, key);
    }

    private static List<String> activeEquivalentOn(java.sql.Connection c, String userId, String contextHash,
            String origin, String title, String accountId) throws java.sql.SQLException {
        return Db.queryOn(c, "SELECT p.id FROM plans p JOIN plan_context_revision c " +
                        "ON c.plan_id=p.id AND c.rev=p.active_context_rev WHERE " + ownerClause("p.user_id") +
                        " AND p.status IN ('DRAFT','ACTIVE','POSITION_OPEN') AND c.input_hash=? " +
                        "AND p.origin_plan_id IS NOT DISTINCT FROM ? AND p.custom_title IS NOT DISTINCT FROM ? " +
                        "AND p.account_id IS NOT DISTINCT FROM ? ORDER BY p.updated_at DESC,p.created_at DESC LIMIT 1",
                r -> r.str("id"), userId, userId, contextHash, origin, title, accountId);
    }

    private static void recordRequestOn(java.sql.Connection c, String ownerKey, String requestId,
            String inputHash, String planId, OffsetDateTime now) throws java.sql.SQLException {
        Db.execOn(c, "INSERT INTO plan_create_request(owner_key,client_request_id,input_hash,plan_id,created_at) " +
                        "VALUES(?,?,?,?,?)",
                ownerKey, requestId, inputHash, planId, now);
    }

    private static String requireSameRequest(ExistingRequest existing, String expectedHash) {
        if (!Objects.equals(existing.inputHash(), expectedHash)) {
            throw new IllegalStateException("clientRequestId was already used for a different plan request");
        }
        return existing.id();
    }

    private record ExistingRequest(String id, String inputHash) {}

    private static void validateContext(Integer horizon, Long target, String risk, Long shares,
                                        Long costBasis, Long assumption) {
        if (horizon != null && horizon <= 0) throw new IllegalArgumentException("horizonDays must be positive");
        if (target != null && target <= 0) throw new IllegalArgumentException("targetCents must be positive");
        normalizeRisk(risk);
        if (shares != null && shares < 0) throw new IllegalArgumentException("holdingsShares cannot be negative");
        if (costBasis != null && costBasis < 0) throw new IllegalArgumentException("costBasisCents cannot be negative");
        if (assumption != null && assumption <= 0) throw new IllegalArgumentException("priceAssumptionCents must be positive");
    }

    private static String normalizeSymbol(String raw) {
        String symbol = required(raw, "symbol").toUpperCase(Locale.ROOT);
        if (!symbol.matches("[A-Z0-9._-]{1,20}")) throw new IllegalArgumentException("invalid symbol: " + raw);
        return symbol;
    }

    private static String normalizeIntent(String raw, boolean nullable) {
        String value = blankToNull(raw);
        if (value == null && nullable) return null;
        if (value == null) throw new IllegalArgumentException("intent is required");
        try { return StrategyIntent.valueOf(value.toUpperCase(Locale.ROOT)).name(); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("unknown intent: " + raw); }
    }

    private static Plan.Stage parseStage(String raw) {
        try { return Plan.Stage.valueOf(required(raw, "stage").toUpperCase(Locale.ROOT).replace('-', '_')); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("unknown plan stage: " + raw); }
    }

    private static String normalizeRisk(String raw) {
        String value = blankToNull(raw);
        if (value == null) return null;
        value = value.toLowerCase(Locale.ROOT);
        if (!List.of("conservative", "balanced", "aggressive").contains(value)) {
            throw new IllegalArgumentException("unknown riskMode: " + raw);
        }
        return value;
    }

    private static String cleanThesis(String raw) {
        String value = blankToNull(raw);
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static String cleanTitle(String raw) {
        String value = blankToNull(raw);
        if (value != null && value.length() > 120) throw new IllegalArgumentException("title is too long");
        return value;
    }

    private static String derivedTitle(String symbol, String intent, Plan.ContextRevision context) {
        String goal = switch (intent == null ? "" : intent) {
            case "INCOME" -> "Earn income";
            case "HEDGE" -> "Protect shares";
            case "ACQUIRE" -> "Buy at a discount";
            case "EXIT" -> "Sell at a target";
            case "DIRECTIONAL" -> context.thesis() == null ? "Trade a view" : context.thesis() + " view";
            default -> "Understand and plan";
        };
        return symbol + " · " + goal;
    }

    private static String ownerClause(String column) {
        return "(" + column + "=?::text OR (?::text IS NULL AND " + column + " IS NULL))";
    }

    private static String required(String raw, String name) {
        String value = blankToNull(raw);
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static String blankToNull(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        return value.isEmpty() ? null : value;
    }

    private static <T> T merged(String key, T supplied, T old, Set<String> clear) {
        return clear.contains(key) ? null : supplied == null ? old : supplied;
    }
}
