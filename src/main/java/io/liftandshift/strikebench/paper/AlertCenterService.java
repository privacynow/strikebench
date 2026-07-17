package io.liftandshift.strikebench.paper;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.market.EventService;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.util.EventBus;
import io.liftandshift.strikebench.util.Money;
import io.liftandshift.strikebench.util.OwnerScope;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The alert center (spec 10.1): one computed attention list per user across lanes — protocol
 * breaches, expiries, earnings proximity, pin risk, early-assignment risk, and unresolved
 * imports. Every alert is derived from REAL persisted state and live marks; heuristics say they
 * are heuristics, estimates say they are estimates, and ex-dividend honesty is absolute: with no
 * sourced dividend calendar the surface says "unavailable" — never a guessed date (§3.15).
 *
 * <p>Home's needs-attention ordering is driven by this list (severity first, then recency). The
 * mutation/market invalidations recompute through this same service and publish an
 * {@code alerts.updated} EventBus hint when a user's set materially changes; GET remains a pure
 * authoritative read and there is no separate SSE channel.</p>
 */
public final class AlertCenterService implements AutoCloseable {

    /** Severity ladder, strongest first. Labels are the product wording at both levels. */
    public static final String URGENT = "URGENT";
    public static final String ATTENTION = "ATTENTION";
    public static final String INFO = "INFO";

    /** Pin risk: spot within this fraction of a short strike ... */
    static final double PIN_BAND_FRACTION = 0.01;
    /** ... with at most this many trading sessions to expiry (weekends excluded). */
    static final int PIN_SESSIONS = 3;
    /** Assignment/expiry attention window in trading sessions. */
    static final int EXPIRY_SESSIONS = 5;

    /** Narrow seam over {@link EventService#nextEarnings} so tests stub estimates directly. */
    @FunctionalInterface
    public interface EarningsSource {
        Optional<EventService.EarningsEstimate> nextEarnings(String symbol);
    }

    public record Alert(String id, String kind, String severity, String severityLabel,
                        String headline, String detail, String symbol, String lane,
                        String entityType, String tradeId, String planId, String structureId,
                        String accountId, String accountName, String deepLink, String at,
                        Map<String, Object> meta) {}

    public record Counts(int urgent, int attention, int info, int total) {}

    /** Ex-div honesty note rendered wherever assignment risk appears (§3.15). */
    public record ExDividendNote(boolean available, String note) {}

    public record AlertSet(List<Alert> alerts, Counts counts, ExDividendNote exDividend, String asOf) {}

    public static final String EX_DIVIDEND_NOTE =
            "Ex-dividend dates: unavailable — no sourced dividend calendar is connected. "
                    + "StrikeBench never guesses dividend dates.";

    private final Db db;
    private final Clock clock;
    private final TradeService trades;
    private final MarksSource marks;
    private final EarningsSource earnings;
    private final EventBus events;
    private final long feePerContractCents;
    private final Map<String, String> lastFingerprint = new ConcurrentHashMap<>();
    private final java.util.Set<String> knownOwners = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> pendingOwners = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService refreshes;
    private final Runnable unsubscribeEvents;

    public AlertCenterService(Db db, Clock clock, TradeService trades, MarksSource marks,
                              EarningsSource earnings, EventBus events, long feePerContractCents) {
        this.db = db;
        this.clock = clock;
        this.trades = trades;
        this.marks = marks;
        this.earnings = earnings;
        this.events = events;
        this.feePerContractCents = Math.max(0, feePerContractCents);
        this.refreshes = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "alert-center-refresh");
            thread.setDaemon(true);
            return thread;
        });
        this.unsubscribeEvents = events == null ? () -> {} : events.subscribe(this::onEvent);
    }

    /** Computes the current alert set for one user, ordered severity-first then recency. Pure read. */
    public AlertSet compute(String ownerId) {
        String owner = OwnerScope.id(ownerId);
        LocalDate today = LocalDate.now(clock);
        List<Alert> out = new ArrayList<>();
        Map<String, String> symbolLinks = new LinkedHashMap<>(); // symbol -> a deep link for earnings rows

        practiceAlerts(owner, today, out, symbolLinks);
        trackedAlerts(owner, today, out, symbolLinks);
        earningsAlerts(today, out, symbolLinks);
        pendingImportAlerts(owner, out);

        out.sort(Comparator
                .comparingInt((Alert a) -> severityRank(a.severity())).reversed()
                .thenComparing(Alert::at, Comparator.reverseOrder())
                .thenComparing(a -> String.valueOf(a.symbol()))
                .thenComparing(Alert::id));
        int urgent = 0, attention = 0, info = 0;
        for (Alert a : out) {
            if (URGENT.equals(a.severity())) urgent++;
            else if (ATTENTION.equals(a.severity())) attention++;
            else info++;
        }
        Counts counts = new Counts(urgent, attention, info, out.size());
        return new AlertSet(List.copyOf(out), counts, new ExDividendNote(false, EX_DIVIDEND_NOTE),
                clock.instant().toString());
    }

    /** HTTP read path: establishes a comparison baseline without emitting a circular SSE hint. */
    public AlertSet current(String ownerId) {
        String owner = OwnerScope.id(ownerId);
        AlertSet set = compute(owner);
        knownOwners.add(owner);
        lastFingerprint.put(owner, fingerprint(set.alerts()));
        return set;
    }

    /** Recomputes after a mutation/clock event and publishes only a material change. */
    public AlertSet refreshAndPublish(String ownerId) {
        String owner = OwnerScope.id(ownerId);
        AlertSet set = compute(owner);
        knownOwners.add(owner);
        publishIfChanged(owner, set.alerts(), set.counts());
        return set;
    }

    /** Debounced, non-blocking invalidation seam for owner-scoped mutations. */
    public void invalidateOwner(String ownerId) {
        String owner = OwnerScope.id(ownerId);
        knownOwners.add(owner);
        if (!pendingOwners.add(owner)) return;
        refreshes.schedule(() -> {
            try {
                refreshAndPublish(owner);
            } catch (RuntimeException ignored) {
                // An attention hint is best-effort; the durable GET remains authoritative.
            } finally {
                pendingOwners.remove(owner);
            }
        }, 20, TimeUnit.MILLISECONDS);
    }

    /** Resolves a practice/demo account to its persistence owner, then invalidates that owner. */
    public void invalidateAccount(String accountId) {
        if (accountId == null || accountId.isBlank()) return;
        String owner = db.query("SELECT COALESCE(user_id,'local') user_id FROM accounts WHERE id=?",
                        r -> r.str("user_id"), accountId)
                .stream().findFirst().orElse(null);
        invalidateOwner(owner);
    }

    private void onEvent(EventBus.Event event) {
        if (!switch (event.type()) {
            case "world.tick", "world.control", "world.selected", "dataset.selected", "plan.updated" -> true;
            default -> false;
        }) return;
        Object owner = event.data().get("user");
        if (owner != null) {
            invalidateOwner(String.valueOf(owner));
        } else {
            // Deliberately global system transitions refresh only owners that have used the surface.
            for (String known : knownOwners) invalidateOwner(known);
        }
    }

    // ---- Practice lane: open trades, marked by the exact services the Manage band uses ----

    private void practiceAlerts(String owner, LocalDate today, List<Alert> out,
                                Map<String, String> symbolLinks) {
        List<AccountRow> accounts = db.query(
                "SELECT id,name,type FROM accounts WHERE user_id=?",
                r -> new AccountRow(r.str("id"), r.str("name"), r.str("type")), owner);
        for (AccountRow account : accounts) {
            for (TradeRecord t : trades.list(account.id(), TradeRecord.ACTIVE, 0, 500).trades()) {
                String planId = planForTrade(t.id());
                String deepLink = planId != null ? "#/plan/" + planId + "/manage-review"
                        : "#/portfolio/trade/" + t.id();
                symbolLinks.putIfAbsent(t.symbol(), deepLink);
                TradeService.MarkView mark = safeMark(t.id());
                Long spotCents = mark == null ? null : mark.underlyingCents();
                Long unrealized = mark == null ? null : mark.unrealizedCents();
                positionAlerts(out, new PositionRef("TRADE", t.id(), planId, null,
                                account.id(), account.name(), laneOf(account.type()), t.symbol(),
                                strategyLabel(t.strategy()), deepLink),
                        t.legs(), t.qty(), t.entryNetPremiumCents(), unrealized, spotCents,
                        leg -> optionMidCents(t.symbol(), leg, trades.worldOf(account.id())), today);
            }
        }
    }

    // ---- Tracked lane: open structures over their exact lots, marked from the same source ----

    private void trackedAlerts(String owner, LocalDate today, List<Alert> out,
                               Map<String, String> symbolLinks) {
        List<StructureRow> structures = db.with(c -> loadStructures(c, owner));
        for (StructureRow s : structures) {
            if (s.legs().isEmpty()) continue;
            String planId = planForStructure(s.id());
            String deepLink = planId != null ? "#/plan/" + planId + "/manage-review"
                    : "#/portfolio/book/overview";
            symbolLinks.putIfAbsent(s.symbol(), deepLink);
            Long spotCents = marks.underlyingMark(s.symbol(), null)
                    .map(Money::toCents).orElse(null);
            long entryNet = 0;
            boolean hasOption = false;
            Long closeValue = 0L;
            List<Leg> legs = new ArrayList<>();
            for (LotLeg lot : s.legs()) {
                Leg leg = lot.toLeg();
                legs.add(leg);
                if (lot.option()) {
                    hasOption = true;
                    entryNet += "SHORT".equals(lot.side()) ? lot.economicRemainingCents()
                            : -lot.economicRemainingCents();
                }
                if (closeValue != null) {
                    Long legClose = closeCents(s.symbol(), leg, lot.side(), lot.quantity(), lot.multiplier());
                    closeValue = legClose == null ? null : closeValue + legClose;
                }
            }
            Long unrealized = (hasOption && closeValue != null) ? closeValue + entryNet : null;
            positionAlerts(out, new PositionRef("STRUCTURE", null, planId, s.id(),
                            s.accountId(), s.accountName(), "TRACKED", s.symbol(),
                            s.label() == null || s.label().isBlank() ? "tracked structure" : s.label(),
                            deepLink),
                    legs, 1, hasOption ? entryNet : 0, unrealized, spotCents,
                    leg -> optionMidCents(s.symbol(), leg, null), today);
        }
    }

    /** Per-leg option mid in cents/share, or null when the market has no mark. */
    private Long optionMidCents(String symbol, Leg leg, String world) {
        if (leg.isStock()) return null;
        return marks.legMark(symbol, leg, world)
                .map(MarksSource.LegMark::mid)
                .map(Money::toCents)
                .orElse(null);
    }

    /** Signed unwind cash for a tracked lot at executable sides (long sells, short buys back). */
    private Long closeCents(String symbol, Leg leg, String side, long quantity, int multiplier) {
        var mark = marks.legMark(symbol, leg, null).orElse(null);
        if (mark == null) return null;
        LegAction closeAction = "SHORT".equals(side) ? LegAction.BUY : LegAction.SELL;
        BigDecimal px = mark.executable(closeAction);
        if (px == null) return null;
        long cash = Money.centsFromPrice(px, quantity * multiplier);
        return "SHORT".equals(side) ? -cash : cash;
    }

    /**
     * The shared per-position pass: protocol (via the ONE {@link ProtocolEvaluator}), expiry,
     * pin risk, and extrinsic-based early-assignment risk. Practice trades and tracked
     * structures both land here — same math, different plumbing.
     */
    private void positionAlerts(List<Alert> out, PositionRef ref, List<Leg> legs, int qty,
                                long entryNetPremiumCents, Long unrealizedCents, Long spotCents,
                                java.util.function.Function<Leg, Long> optionMid, LocalDate today) {
        LocalDate nearest = legs.stream().filter(l -> !l.isStock()).map(Leg::expiration)
                .filter(java.util.Objects::nonNull).min(LocalDate::compareTo).orElse(null);
        Integer daysToExpiry = nearest == null ? null
                : (int) java.time.temporal.ChronoUnit.DAYS.between(today, nearest);
        int sessionsToNearest = nearest == null ? Integer.MAX_VALUE : sessionsUntil(today, nearest);

        // Protocol — one alert per position carrying the top trigger; the time rule stays quiet
        // inside the expiry window so the rail never says the same thing twice.
        List<ProtocolEvaluator.Trigger> triggers = ProtocolEvaluator.evaluate(
                new ProtocolEvaluator.Inputs(entryNetPremiumCents, unrealizedCents, daysToExpiry));
        ProtocolEvaluator.Trigger top = triggers.stream()
                .filter(t -> !INFO.equals(t.severity()) || sessionsToNearest > EXPIRY_SESSIONS)
                .findFirst().orElse(null);
        if (top != null && entryNetPremiumCents != 0) {
            boolean credit = entryNetPremiumCents > 0;
            String headline = switch (top.rule()) {
                case ProtocolEvaluator.STOP_LOSS -> ref.symbol() + " " + ref.positionLabel()
                        + ": past your loss line — your protocol says close it.";
                case ProtocolEvaluator.TAKE_PROFIT -> ref.symbol() + " " + ref.positionLabel()
                        + ": most of the profit is in hand — your protocol says take it.";
                case ProtocolEvaluator.ROLL -> ref.symbol() + " " + ref.positionLabel()
                        + ": " + daysToExpiry + " days to expiry — decide: roll or close.";
                default -> ref.symbol() + " " + ref.positionLabel()
                        + ": " + daysToExpiry + " days to expiry — exit if the thesis has not moved.";
            };
            String detail = (credit
                    ? "This position collected " + Money.fmt(entryNetPremiumCents) + " up front"
                    : "This position paid " + Money.fmt(-entryNetPremiumCents) + " up front")
                    + (unrealizedCents != null
                            ? " and now stands at " + Money.fmt(unrealizedCents) + " at live closing prices"
                            : "")
                    + ". Trigger: " + top.summary()
                    + ". A mechanical management rule, not a prediction.";
            out.add(alert(ref, "PROTOCOL_BREACH", top.severity(), headline, detail,
                    Map.of("rule", top.rule(),
                            "entryNetPremiumCents", entryNetPremiumCents,
                            "unrealizedCents", unrealizedCents == null ? 0L : unrealizedCents)));
        }

        // Expiry today / this week, with strike notional (the settlement ceiling, unit-labeled).
        if (nearest != null && sessionsToNearest <= EXPIRY_SESSIONS && daysToExpiry >= 0) {
            long notional = 0;
            for (Leg leg : legs) {
                if (leg.isStock() || !nearest.equals(leg.expiration())) continue;
                notional += Money.centsFromPrice(leg.strike(), (long) leg.multiplier() * leg.ratio() * qty);
            }
            boolean isToday = nearest.equals(today);
            String headline = isToday
                    ? ref.symbol() + " " + ref.positionLabel() + ": expires today — decide before the close."
                    : ref.symbol() + " " + ref.positionLabel() + ": expires " + nearest
                            + " (" + sessionsToNearest + " sessions) — plan the exit, roll, or settlement.";
            String detail = Money.fmt(notional) + " of strike value (strike × contracts × multiplier) "
                    + "settles at this expiry. Options in the money become shares by assignment or "
                    + "exercise; out-of-the-money options expire worthless.";
            out.add(alert(ref, "EXPIRY", isToday ? URGENT : ATTENTION, headline, detail,
                    Map.of("expiration", nearest.toString(),
                            "sessionsToExpiry", sessionsToNearest,
                            "strikeNotionalCents", notional)));
        }

        // Pin risk + extrinsic-based early assignment: short legs only, both labeled heuristics.
        for (Leg leg : legs) {
            if (leg.isStock() || leg.action() != LegAction.SELL || leg.expiration() == null) continue;
            int legSessions = sessionsUntil(today, leg.expiration());
            if (leg.expiration().isBefore(today)) continue;
            long strikeCents = Money.toCents(leg.strike());
            if (spotCents != null && legSessions <= PIN_SESSIONS
                    && Math.abs(spotCents - strikeCents) <= Math.round(strikeCents * PIN_BAND_FRACTION)) {
                out.add(alert(ref, "PIN_RISK", ATTENTION,
                        ref.symbol() + ": the stock is sitting right on your " + Money.fmt(strikeCents)
                                + " short strike into expiry.",
                        "Heuristic: price within 1% of a short strike with " + PIN_SESSIONS
                                + " or fewer trading sessions left (" + legSessions + " remain). Whether the option "
                                + "finishes in the money is close to a coin flip — decide whether you want "
                                + "the shares instead of letting chance decide.",
                        Map.of("strikeCents", strikeCents, "spotCents", spotCents,
                                "optionType", leg.type().name(),
                                "sessionsToExpiry", legSessions, "heuristic", true)));
            }
            if (spotCents != null && legSessions <= EXPIRY_SESSIONS) {
                boolean itm = leg.type() == OptionType.CALL ? spotCents > strikeCents : spotCents < strikeCents;
                Long midCents = itm ? optionMid.apply(leg) : null;
                if (itm && midCents != null) {
                    long intrinsicPerShare = leg.type() == OptionType.CALL
                            ? spotCents - strikeCents : strikeCents - spotCents;
                    long extrinsicPerContract = (midCents - intrinsicPerShare) * leg.multiplier();
                    if (extrinsicPerContract <= feePerContractCents) {
                        String kind = leg.type() == OptionType.CALL ? "call" : "put";
                        out.add(alert(ref, "ASSIGNMENT", ATTENTION,
                                ref.symbol() + ": your short " + Money.fmt(strikeCents) + " " + kind
                                        + " could be assigned early — its remaining time value is below trading fees.",
                                "Heuristic: an in-the-money short option whose extrinsic (time) value ("
                                        + Money.fmt(Math.max(0, extrinsicPerContract)) + " per contract) is at or "
                                        + "below the trading fee gives its owner little reason to keep waiting. "
                                        + EX_DIVIDEND_NOTE,
                                Map.of("strikeCents", strikeCents, "optionType", leg.type().name(),
                                        "extrinsicPerContractCents", Math.max(0, extrinsicPerContract),
                                        "sessionsToExpiry", legSessions, "heuristic", true,
                                        "entryNetPremiumCents", entryNetPremiumCents,
                                        "quantity", (long) leg.ratio() * qty,
                                        "multiplier", leg.multiplier())));
                    }
                }
            }
        }
    }

    // ---- Earnings proximity: estimate-labeled, only where the events surface supplies it ----

    private void earningsAlerts(LocalDate today, List<Alert> out, Map<String, String> symbolLinks) {
        for (Map.Entry<String, String> entry : symbolLinks.entrySet()) {
            String symbol = entry.getKey();
            EventService.EarningsEstimate estimate = earnings.nextEarnings(symbol).orElse(null);
            if (estimate == null || estimate.confirmed()) continue; // confirmed feeds arrive later; today all are estimates
            LocalDate windowStart = estimate.estimated().minusDays(estimate.windowDays());
            LocalDate windowEnd = estimate.estimated().plusDays(estimate.windowDays());
            if (windowStart.isAfter(today.plusDays(14)) || windowEnd.isBefore(today)) continue;
            out.add(new Alert("earnings:" + symbol, "EARNINGS", INFO, severityLabel(INFO),
                    symbol + ": earnings estimated near " + estimate.estimated()
                            + " — prices and option values can gap around it.",
                    "Estimated window (±" + estimate.windowDays() + " days) projected from "
                            + estimate.basis() + ". This is an estimate, not a confirmed calendar date.",
                    symbol, null, "SYMBOL", null, null, null, null, null,
                    "#/research/" + symbol + "?view=evidence", clock.instant().toString(),
                    Map.of("estimated", true, "confirmed", false,
                            "estimatedDate", estimate.estimated().toString(),
                            "windowDays", estimate.windowDays())));
        }
    }

    // ---- Unresolved imports: pending broker packages awaiting resolution, per source account ----

    private void pendingImportAlerts(String owner, List<Alert> out) {
        record PendingGroup(String sourceSystem, String fingerprint, long count, String latest) {}
        List<PendingGroup> groups = db.query(
                "SELECT source_system, source_account_fingerprint, COUNT(*) n, MAX(occurred_at)::text latest "
                        + "FROM portfolio_import_pending WHERE user_id=? AND status IN ('PENDING','PROVISIONAL') "
                        + "GROUP BY source_system, source_account_fingerprint ORDER BY latest DESC",
                r -> new PendingGroup(r.str("source_system"), r.str("source_account_fingerprint"),
                        r.lng("n"), r.str("latest")), owner);
        for (PendingGroup g : groups) {
            String tail = g.fingerprint().length() > 4
                    ? g.fingerprint().substring(g.fingerprint().length() - 4) : g.fingerprint();
            String accountLabel = g.sourceSystem() + " …" + tail;
            out.add(new Alert("imports:" + g.sourceSystem() + ":" + g.fingerprint(),
                    "PENDING_IMPORTS", ATTENTION, severityLabel(ATTENTION),
                    g.count() == 1
                            ? "1 imported broker package needs fills or broker attestation."
                            : g.count() + " imported broker packages need fills or broker attestation.",
                    "From " + accountLabel + ". User allocations remain quarantined; only broker-attested "
                            + "fills create exact lots and complete the tracked book.",
                    null, "TRACKED", "IMPORTS", null, null, null, null, accountLabel,
                    "#/portfolio/book/activity", g.latest(),
                    Map.of("pendingCount", g.count())));
        }
    }

    // ---- helpers ----

    private Alert alert(PositionRef ref, String kind, String severity, String headline,
                        String detail, Map<String, Object> meta) {
        String entityId = ref.tradeId() != null ? ref.tradeId() : ref.structureId();
        return new Alert(kind.toLowerCase(Locale.ROOT) + ":" + entityId
                + (meta.containsKey("optionType") ? ":" + meta.get("optionType") : "")
                + (meta.containsKey("strikeCents") ? ":" + meta.get("strikeCents") : ""),
                kind, severity, severityLabel(severity), headline, detail, ref.symbol(), ref.lane(),
                ref.entityType(), ref.tradeId(), ref.planId(), ref.structureId(), ref.accountId(),
                ref.accountName(), ref.deepLink(), clock.instant().toString(), meta);
    }

    private TradeService.MarkView safeMark(String tradeId) {
        try { return trades.currentMark(tradeId); }
        catch (RuntimeException e) { return null; } // marks unavailable → those alerts simply don't fire
    }

    private String planForTrade(String tradeId) {
        return db.query("SELECT plan_id FROM plan_link WHERE trade_id=? ORDER BY created_at DESC LIMIT 1",
                r -> r.str("plan_id"), tradeId).stream().findFirst().orElse(null);
    }

    private String planForStructure(String structureId) {
        return db.query("SELECT ppa.plan_id FROM plan_portfolio_action ppa "
                        + "JOIN portfolio_structure_revision psr ON psr.id=ppa.structure_revision_id "
                        + "WHERE psr.structure_id=? ORDER BY ppa.created_at DESC LIMIT 1",
                r -> r.str("plan_id"), structureId).stream().findFirst().orElse(null);
    }

    private static List<StructureRow> loadStructures(Connection c, String owner) throws SQLException {
        List<StructureRow> rows = Db.queryOn(c,
                "SELECT s.id, s.symbol, s.label, s.portfolio_account_id, pa.name account_name, "
                        + "s.current_revision_id FROM portfolio_structure s "
                        + "JOIN portfolio_account pa ON pa.id = s.portfolio_account_id "
                        + "WHERE s.user_id=? AND s.status='OPEN' AND s.current_revision_id IS NOT NULL",
                r -> new StructureRow(r.str("id"), r.str("symbol"), r.str("label"),
                        r.str("portfolio_account_id"), r.str("account_name"),
                        r.str("current_revision_id"), new ArrayList<>()), owner);
        for (StructureRow row : rows) {
            row.legs().addAll(Db.queryOn(c,
                    "SELECT pl.instrument_type, pl.side, pl.option_type, pl.strike::text strike, "
                            + "pl.expiration::text expiration, pl.multiplier, psm.allocated_quantity, "
                            + "pl.economic_remaining_open_amount_cents econ "
                            + "FROM portfolio_structure_member psm "
                            + "JOIN portfolio_lot pl ON pl.id = psm.lot_id "
                            + "WHERE psm.revision_id=? AND pl.remaining_quantity > 0 ORDER BY psm.leg_no",
                    r -> new LotLeg(r.str("instrument_type"), r.str("side"), r.str("option_type"),
                            r.str("strike"), r.str("expiration"), r.intv("multiplier"),
                            r.lng("allocated_quantity"), r.lng("econ")), row.revisionId()));
        }
        return rows;
    }

    /** Trading sessions (weekdays) strictly after {@code from} up to and including {@code to}. */
    static int sessionsUntil(LocalDate from, LocalDate to) {
        if (!to.isAfter(from)) return 0;
        int n = 0;
        for (LocalDate d = from.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) {
            DayOfWeek dow = d.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) n++;
        }
        return n;
    }

    static String severityLabel(String severity) {
        return switch (severity) {
            case URGENT -> "Act today";
            case ATTENTION -> "Needs a look";
            default -> "Worth knowing";
        };
    }

    static int severityRank(String severity) {
        return switch (severity) {
            case URGENT -> 3;
            case ATTENTION -> 2;
            default -> 1;
        };
    }

    private static String laneOf(String accountType) {
        return switch (accountType == null ? "" : accountType) {
            case "DEMO" -> "DEMO";
            case "SIMULATION" -> "SIMULATED";
            default -> "PRACTICE";
        };
    }

    private static String strategyLabel(String strategy) {
        String s = String.valueOf(strategy == null ? "position" : strategy)
                .replace('_', ' ').toLowerCase(Locale.ROOT);
        return s.isEmpty() ? "position" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Publishes the material-change hint. Fingerprint = ids + severities, order-independent. */
    private void publishIfChanged(String owner, List<Alert> alerts, Counts counts) {
        String fingerprint = fingerprint(alerts);
        String prior = lastFingerprint.put(owner, fingerprint);
        if (prior != null && prior.equals(fingerprint)) return;
        if (events == null) return;
        // Every owner-scoped EventBus hint uses the mandatory `user` field.  The authenticated
        // SSE transport deliberately rejects malformed alerts.updated hints that omit it, so a
        // future producer typo cannot turn private attention counts into a global event.
        events.publish("alerts.updated", Map.of("user", owner,
                "urgent", counts.urgent(), "attention", counts.attention(),
                "info", counts.info(), "total", counts.total()));
    }

    private static String fingerprint(List<Alert> alerts) {
        return alerts.stream().map(a -> a.id() + "=" + a.severity()).sorted()
                .reduce("", (a, b) -> a + "|" + b);
    }

    @Override
    public void close() {
        unsubscribeEvents.run();
        refreshes.shutdownNow();
    }

    private record AccountRow(String id, String name, String type) {}

    private record PositionRef(String entityType, String tradeId, String planId, String structureId,
                               String accountId, String accountName, String lane, String symbol,
                               String positionLabel, String deepLink) {}

    private record StructureRow(String id, String symbol, String label, String accountId,
                                String accountName, String revisionId, List<LotLeg> legs) {}

    private record LotLeg(String instrumentType, String side, String optionType, String strike,
                          String expiration, int multiplier, long quantity, long economicRemainingCents) {
        boolean option() { return "OPTION".equals(instrumentType); }

        Leg toLeg() {
            LegAction action = "SHORT".equals(side) ? LegAction.SELL : LegAction.BUY;
            int ratio = (int) Math.max(1, Math.min(quantity, Integer.MAX_VALUE));
            if (!option()) {
                return new Leg(action, null, null, null, ratio, BigDecimal.ZERO, Math.max(1, multiplier));
            }
            return new Leg(action, OptionType.valueOf(optionType), new BigDecimal(strike),
                    LocalDate.parse(expiration), ratio, BigDecimal.ZERO, Math.max(1, multiplier));
        }
    }
}
