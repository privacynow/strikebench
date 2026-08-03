package io.liftandshift.strikebench.recommend;

import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionQuote;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.util.Money;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Wire form of a leg: plain strings only (no java.time / BigDecimal in JSON DTOs). */
public record LegView(
        String action,       // BUY | SELL
        String type,         // CALL | PUT | STOCK
        String strike,       // decimal string, null for stock
        String expiration,   // ISO date, null for stock
        int ratio,
        String entryPrice,   // decimal string per share, may be null on requests
        int multiplier,      // required deliverable units per ratio unit
        String positionEffect, // OPEN | CLOSE; pricing consumes OPEN, transformation preview consumes both
        String quoteBid,     // exact source-book bid used when the candidate was constructed
        String quoteAsk,     // exact source-book ask used when the candidate was constructed
        Long quoteAsOfEpochMs,
        String quoteSource,
        String quoteFreshness,
        Double quoteIv,      // exact captured quote IV ratio; null when the source did not provide it
        Double quoteDelta,   // exact captured quote delta; null when the source did not provide it
        String quoteMid,
        String fillBasis,
        String quoteProvenance,
        String quoteDataAge,
        Double quoteGamma,
        Double quoteTheta,
        Double quoteVega
) {
    public LegView {
        if (action == null || action.isBlank()) throw new IllegalArgumentException("leg action required");
        if (type == null || type.isBlank()) throw new IllegalArgumentException("leg type required");
        if (ratio < 1) throw new IllegalArgumentException("leg ratio must be >= 1");
        if (multiplier < 1 || multiplier > 10_000) {
            throw new IllegalArgumentException("leg multiplier must be 1..10,000");
        }
        if (positionEffect == null || !("OPEN".equalsIgnoreCase(positionEffect)
                || "CLOSE".equalsIgnoreCase(positionEffect))) {
            throw new IllegalArgumentException("leg positionEffect must be OPEN or CLOSE");
        }
    }

    /**
     * THE strike-cents read for the wire form. Consumers that need the strike as money parse it
     * here, once, instead of each keeping a private BigDecimal-and-round dialect. Null for stock
     * legs and for malformed input — absence, never a substituted zero.
     */
    public Long strikeCents() {
        if (strike == null || strike.isBlank()) return null;
        try { return Money.toCents(new BigDecimal(strike)); }
        catch (NumberFormatException invalid) { return null; }
    }

    /** Candidate wire form with the exact quote that supplied the executable entry side. */
    public static LegView of(Leg leg, OptionQuote quote) {
        DataEvidence markEvidence = quote == null ? null : quote.evidence();
        return new LegView(
                leg.action().name(),
                leg.isStock() ? "STOCK" : leg.type().name(),
                // Normalized decimal formatting (strip trailing zeros) so a candidate's legs round-trip
                // byte-identically through the custom-builder store, which persists + re-emits via the
                // same stripped form. Without this, "13.20" (engine) vs "13.2" (store) broke exact-leg
                // equality whenever a strip-sensitive price surfaced at candidates[0]. The spelling
                // itself lives in ONE place — Money.stablePriceText — shared with the §7.2 package
                // fingerprint, which was hashing the unstripped form and so disagreed with this one.
                leg.isStock() ? null : Money.stablePriceText(leg.strike()),
                leg.isStock() ? null : leg.expiration().toString(),
                leg.ratio(),
                Money.stablePriceText(leg.entryPrice()),
                leg.multiplier(),
                "OPEN",
                quote == null || quote.bid() == null ? null : Money.stablePriceText(quote.bid()),
                quote == null || quote.ask() == null ? null : Money.stablePriceText(quote.ask()),
                quote == null ? null : quote.asOfEpochMs(),
                markEvidence == null ? null : markEvidence.source(),
                markEvidence == null ? null : markEvidence.code(),
                quote == null ? null : quote.iv(),
                quote == null ? null : quote.delta(),
                quote == null || quote.mid() == null ? null : Money.stablePriceText(quote.mid()),
                // A quote alone does not say which pricing policy selected this leg. The
                // canonical package pricer stamps the basis after it chooses the exact side.
                null,
                markEvidence == null ? null : markEvidence.provenance().name(),
                markEvidence == null ? null : markEvidence.age().name(),
                quote == null ? null : quote.gamma(),
                quote == null ? null : quote.theta(),
                quote == null ? null : quote.vega());
    }

    public LegView withEntryPrice(String price, String basis) {
        return new LegView(action, type, strike, expiration, ratio, price, multiplier,
                positionEffect, quoteBid, quoteAsk, quoteAsOfEpochMs, quoteSource,
                quoteFreshness, quoteIv, quoteDelta, quoteMid, basis, quoteProvenance,
                quoteDataAge, quoteGamma, quoteTheta, quoteVega);
    }

    public Leg toLeg() {
        LegAction a = LegAction.valueOf(action.toUpperCase(java.util.Locale.ROOT));
        BigDecimal price = entryPrice == null ? BigDecimal.ZERO : new BigDecimal(entryPrice);
        if ("STOCK".equalsIgnoreCase(type)) {
            return new Leg(a, null, null, null, ratio, price, multiplier);
        }
        return Leg.option(a, OptionType.valueOf(type.toUpperCase(java.util.Locale.ROOT)),
                new BigDecimal(strike), LocalDate.parse(expiration), ratio, price, multiplier);
    }
}
