package io.liftandshift.strikebench.market;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.OptionQuote;
import io.liftandshift.strikebench.model.Quote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Records a point-in-time EOD snapshot of the active universe's option chains and underlying
 * quotes into {@code option_bar} / {@code underlying_bar}. This is the historical-evidence moat:
 * every day we own another slice of the market that no cancelled data subscription can take back,
 * and it is the substrate for IV-rank history, honest backtests, and recommendation calibration.
 *
 * Provenance is preserved LOUDLY, never hidden. Rows carry {@code source='snapshot'} to mark them
 * as our own recordings, and the per-dimension evidence columns ({@code observed},
 * {@code bid_ask_observed}, {@code iv_source}, {@code greeks_source}) say exactly how real each
 * value is — a fixture/demo snapshot is flagged as modeled so it can never masquerade as observed
 * market data downstream.
 */
public final class SnapshotService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotService.class);
    private static final String SOURCE = "snapshot";

    private final MarketDataService market;
    private final UniverseService universe;
    private final Db db;
    private final Clock clock;

    public SnapshotService(MarketDataService market, UniverseService universe, Db db, Clock clock) {
        this.market = market;
        this.universe = universe;
        this.db = db;
        this.clock = clock;
    }

    /** Outcome of one snapshot run — surfaced by the admin endpoint and logged by the scheduler. */
    public record SnapshotResult(LocalDate asof, int symbols, int underlyingRows, int optionRows,
                                 List<String> errors, long elapsedMs) {}

    /** Snapshots the currently active universe. */
    public SnapshotResult snapshotActiveUniverse() {
        return snapshot(universe.active().symbols());
    }

    /**
     * Records a snapshot for the given symbols. Network fetches happen OUTSIDE the write
     * transaction; each symbol's rows are written in one transaction so a partial chain never
     * lands half-committed. One symbol failing never aborts the rest.
     */
    public SnapshotResult snapshot(List<String> symbols) {
        long start = System.currentTimeMillis();
        LocalDate asof = LocalDate.now(clock);
        int underlyingRows = 0, optionRows = 0;
        List<String> errors = new ArrayList<>();

        for (String rawSym : symbols == null ? List.<String>of() : symbols) {
            String sym = rawSym == null ? "" : rawSym.trim().toUpperCase(java.util.Locale.ROOT);
            if (sym.isEmpty()) continue;
            try {
                // Gather everything for this symbol first (may hit the network / caches).
                Optional<Quote> quote = market.quote(sym);
                List<LocalDate> expirations = market.expirations(sym);
                List<OptionChain> chains = new ArrayList<>();
                for (LocalDate exp : expirations) {
                    market.chain(sym, exp).filter(c -> !c.isEmpty()).ifPresent(chains::add);
                }
                if (quote.isEmpty() && chains.isEmpty()) {
                    errors.add(sym + ": no quote or chain data");
                    continue;
                }
                int[] written = writeSymbol(asof, sym, quote.orElse(null), chains);
                underlyingRows += written[0];
                optionRows += written[1];
            } catch (RuntimeException e) {
                log.warn("snapshot for {} failed: {}", sym, e.toString());
                errors.add(sym + ": " + e.getClass().getSimpleName() + " " + e.getMessage());
            }
        }

        long ms = System.currentTimeMillis() - start;
        int syms = symbols == null ? 0 : (int) symbols.stream().filter(s -> s != null && !s.isBlank()).count();
        log.info("snapshot {} — {} symbols, {} underlying + {} option bars, {} error(s), {} ms",
                asof, syms, underlyingRows, optionRows, errors.size(), ms);
        return new SnapshotResult(asof, syms, underlyingRows, optionRows, errors, ms);
    }

    /** Writes one symbol's underlying + option rows in a single transaction; returns {underlying, option} counts. */
    private int[] writeSymbol(LocalDate asof, String sym, Quote quote, List<OptionChain> chains) {
        final Quote observedQuote = quote != null && snapshotEligible(quote.evidence()) ? quote : null;
        return db.tx(c -> {
            int u = 0, o = 0;
            if (observedQuote != null) {
                BigDecimal close = observedQuote.last() != null ? observedQuote.last() : observedQuote.mark();
                if (close != null) {
                    Db.execOn(c,
                            "INSERT INTO underlying_bar (symbol, d, open, high, low, close, volume, source, observed) "
                          + "VALUES (?,?,?,?,?,?,?,?,?) "
                          + "ON CONFLICT (symbol, d, source, dataset_id) DO UPDATE SET "
                          + "open=excluded.open, high=excluded.high, low=excluded.low, close=excluded.close, "
                          + "volume=excluded.volume, observed=excluded.observed",
                            sym, asof, null, observedQuote.dayHigh(), observedQuote.dayLow(), close,
                            observedQuote.volume(), SOURCE, true);
                    u++;
                }
            }
            for (OptionChain chain : chains) {
                if (!snapshotEligible(chain.evidence())) continue;
                boolean observed = true;
                BigDecimal underlying = chain.underlyingPrice();
                List<OptionQuote> legs = new ArrayList<>(chain.calls());
                legs.addAll(chain.puts());
                for (OptionQuote q : legs) {
                    if (!snapshotEligible(q.evidence())) continue;
                    boolean baObserved = observed && q.bid() != null && q.ask() != null;
                    String ivSource = q.iv() != null ? (observed ? "vendor" : "model") : null;
                    boolean anyGreek = q.delta() != null || q.gamma() != null || q.theta() != null || q.vega() != null;
                    String greeksSource = anyGreek ? (observed ? "vendor" : "model") : null;
                    Db.execOn(c,
                            "INSERT INTO option_bar (symbol, asof, expiration, strike, opt_type, bid, ask, last, mark, "
                          + "iv, delta, gamma, theta, vega, open_interest, volume, underlying, source, "
                          + "bid_ask_observed, iv_source, greeks_source) "
                          + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                          + "ON CONFLICT (symbol, asof, expiration, strike, opt_type, source, dataset_id) DO UPDATE SET "
                          + "bid=excluded.bid, ask=excluded.ask, last=excluded.last, mark=excluded.mark, "
                          + "iv=excluded.iv, delta=excluded.delta, gamma=excluded.gamma, theta=excluded.theta, "
                          + "vega=excluded.vega, open_interest=excluded.open_interest, volume=excluded.volume, "
                          + "underlying=excluded.underlying, bid_ask_observed=excluded.bid_ask_observed, "
                          + "iv_source=excluded.iv_source, greeks_source=excluded.greeks_source",
                            sym, asof, q.expiration(), q.strike(), q.type().name(),
                            q.bid(), q.ask(), q.last(), q.mid(),
                            q.iv(), q.delta(), q.gamma(), q.theta(), q.vega(),
                            q.openInterest(), q.volume(), underlying, SOURCE,
                            baObserved, ivSource, greeksSource);
                    o++;
                }
            }
            return new int[]{u, o};
        });
    }

    /** Only attributable, current-enough observed inputs may enter the canonical observed tables. */
    private static boolean snapshotEligible(io.liftandshift.strikebench.model.DataEvidence evidence) {
        if (evidence == null || evidence.provenance() != io.liftandshift.strikebench.model.DataProvenance.OBSERVED) {
            return false;
        }
        return evidence.age() == io.liftandshift.strikebench.model.DataAge.REALTIME
                || evidence.age() == io.liftandshift.strikebench.model.DataAge.DELAYED
                || evidence.age() == io.liftandshift.strikebench.model.DataAge.EOD;
    }
}
