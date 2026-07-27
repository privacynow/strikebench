package io.liftandshift.strikebench.paper;

import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.util.Json;
import io.liftandshift.strikebench.util.Money;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE canonical package-price receipt (program §7.2). One shared object priced by candidates,
 * previews, orders, reviews and held-position closes so no surface has to choose among competing
 * price fields and no two surfaces can print unexplained different amounts for the same package
 * (§3.3). Before this record the same package was published under nine different names —
 * {@code entryNetPremiumCents}, legacy proposal fields, {@code valuedNetCents},
 * {@code fillNetCents}, {@code closeCostCents}, … — on four different bases, with no field on
 * either object able to explain the gap.
 *
 * <p><b>Quantity is already applied</b> to every cents amount here; it rides along as disclosure
 * so a rail row and an order dock can be compared without the reader guessing the multiplier.</p>
 *
 * <p><b>Sign convention</b> follows the ledger everywhere: money received is positive, money paid
 * is negative. A buy-write is therefore a positive {@code optionNetPremiumCents} (the call sold)
 * plus a much larger negative {@code stockCashFlowCents} (the shares bought), summing to a
 * negative {@code grossPackageNetCents}.</p>
 *
 * <p><b>Missing evidence never becomes a number</b> (§3.2). A field that cannot be known for a
 * surface is {@code null} and {@code unavailableReason} says why; it is never a substituted zero.
 * In particular {@code executableNetCents} is null — not silently backfilled from the recorded
 * package net — whenever the book is one-sided, because a field named "executable" must never
 * carry a non-executable number.</p>
 *
 * <p>Two additions beyond §7.2's fourteen names, both required to keep the other twelve honest:
 * {@code feeSide}, because the held-close and transformation lanes charge a CLOSING fee and would
 * otherwise have to lie about {@code openingFeesCents}; and {@code unavailableReason}, required by
 * §3.2. This mirrors the §7.1 Greeks precedent that the listed fields are necessary, not
 * sufficient.</p>
 */
// ALWAYS, against the shared mapper's NON_NULL default: a price the surface cannot know must
// arrive as an explicit null beside its reason, not vanish from the payload. A missing key and a
// null key look identical in a browser, so dropping them would quietly reintroduce the "which
// number is real?" ambiguity this record exists to end (§3.2).
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
public record PackagePriceReceipt(
        int quantity,                  // already applied below; disclosed so surfaces reconcile
        Long optionNetPremiumCents,    // option legs ONLY, credit > 0 / debit < 0
        Long stockCashFlowCents,       // signed cash of any stock leg; 0 when the package has none
        Long grossPackageNetCents,     // == optionNetPremiumCents + stockCashFlowCents (enforced)
        Long openingFeesCents,         // commission of THIS order; see feeSide. null = not known
        Long estimatedRoundTripFeesCents, // captured open+close estimate; null = not known
        Long afterFeeNetCents,         // == grossPackageNetCents - openingFeesCents (enforced)
        Long executableNetCents,       // natural two-sided net; null when the book is one-sided
        Long restingLimitNetCents,     // the signed limit, only when the order is a LIMIT
        ValuationBasis valuationBasis, // how grossPackageNetCents itself was priced
        OrderInstruction.Executability executability,
        String source,
        String freshness,
        Long observedAt,               // epoch ms of the quotes this price was struck from
        String fingerprint,            // identity of the priced package; equal ⇒ same package+price
        FeeSide feeSide,
        String unavailableReason
) {

    /**
     * How {@code grossPackageNetCents} was priced. This is deliberately NOT the same question as
     * {@link OrderInstruction.Executability}: a resting limit is a real, stated price on the
     * RESTING_LIMIT basis while its executability is RESTING.
     */
    public enum ValuationBasis {
        EXECUTABLE_BOOK,  // repriced to the natural sides: buys pay the ask, sells receive the bid
        RESTING_LIMIT,    // priced at the customer's own non-marketable limit
        RECORDED_FILL,    // priced at a fill that actually happened
        MID_MARKET,       // midpoint between the two sides
        MODELED,          // model or last-trade evidence; no executable two-sided book
        UNAVAILABLE       // no price at all — grossPackageNetCents is null and the reason is stated
    }

    /** Which side of the trade's life these fees belong to. See the class note on §7.2's naming. */
    public enum FeeSide { OPENING, CLOSING }

    /**
     * THE basis a package priced off current quote MARKS is stated on (§3.1, §3.8). The producer
     * supplies what its own evidence proves — whether every leg was struck on a two-sided book that
     * is executable in this market lane, and whether any leg had to fall back to a midpoint — and
     * this method names the basis. It is deliberately not the caller's assertion: a scan and an
     * order preview reading the same EOD chain must reach the same label, or the rail states a
     * price the order lane refuses.
     *
     * <p>A midpoint is nobody's fill. When the package net contains one, {@link
     * ValuationBasis#MID_MARKET} says exactly that instead of the vaguer MODELED, which is reserved
     * for marks that are not an executable book at all (stale/EOD sides, a last-trade fallback, or
     * a model).</p>
     *
     * @param executableBook every leg priced on a two-sided book executable in the target lane
     * @param anyMidpointLeg at least one leg's price is a midpoint rather than a tradeable side
     */
    public static ValuationBasis markBasis(boolean executableBook, boolean anyMidpointLeg) {
        if (executableBook) return ValuationBasis.EXECUTABLE_BOOK;
        return anyMidpointLeg ? ValuationBasis.MID_MARKET : ValuationBasis.MODELED;
    }

    public PackagePriceReceipt {
        if (quantity < 1) throw new IllegalArgumentException("package price receipt requires quantity >= 1");
        if (valuationBasis == null) throw new IllegalArgumentException("package price receipt requires a valuation basis");
        if (feeSide == null) throw new IllegalArgumentException("package price receipt requires a fee side");
        executability = executability == null ? OrderInstruction.Executability.UNAVAILABLE : executability;

        // A stated basis and a stated price stand or fall together — an UNAVAILABLE basis must not
        // ship a number, and a priced package must name the basis it was priced on (§3.2).
        if ((valuationBasis == ValuationBasis.UNAVAILABLE) != (grossPackageNetCents == null)) {
            throw new IllegalArgumentException(
                    "valuationBasis UNAVAILABLE and a null grossPackageNetCents must occur together");
        }
        if (valuationBasis == ValuationBasis.UNAVAILABLE && (unavailableReason == null || unavailableReason.isBlank())) {
            throw new IllegalArgumentException("an unpriced package must state why it is unavailable");
        }
        if (valuationBasis == ValuationBasis.UNAVAILABLE) {
            if (optionNetPremiumCents != null || stockCashFlowCents != null
                    || openingFeesCents != null || estimatedRoundTripFeesCents != null
                    || afterFeeNetCents != null || executableNetCents != null
                    || restingLimitNetCents != null) {
                throw new IllegalArgumentException(
                        "an unpriced package cannot carry financial amounts");
            }
            if (executability != OrderInstruction.Executability.UNAVAILABLE) {
                throw new IllegalArgumentException(
                        "an unpriced package must have UNAVAILABLE executability");
            }
        } else if (unavailableReason != null && !unavailableReason.isBlank()) {
            throw new IllegalArgumentException(
                    "a priced package cannot carry an unavailable reason");
        }
        if (openingFeesCents != null && openingFeesCents < 0) {
            throw new IllegalArgumentException("fees cannot be negative");
        }
        if (estimatedRoundTripFeesCents != null && estimatedRoundTripFeesCents < 0) {
            throw new IllegalArgumentException("round-trip fees cannot be negative");
        }
        if (feeSide == FeeSide.OPENING
                && (openingFeesCents == null) != (estimatedRoundTripFeesCents == null)) {
            throw new IllegalArgumentException(
                    "an opening price must state both its opening and estimated round-trip commission");
        }
        if (feeSide == FeeSide.OPENING && openingFeesCents != null
                && estimatedRoundTripFeesCents < openingFeesCents) {
            throw new IllegalArgumentException(
                    "estimated round-trip commission cannot be less than the opening commission");
        }

        // The additive invariant: the whole package is exactly its option premium plus its stock
        // cash. This is what lets one screen print the option-only net and another the whole
        // package net without the two reading as a contradiction. It only bites because all three
        // amounts are MEASURED by the producer — see #of — rather than one being restated from the
        // other two; a mismatch here is a real basis mismatch, so it names the amounts.
        if (grossPackageNetCents != null) {
            if (optionNetPremiumCents == null || stockCashFlowCents == null) {
                throw new IllegalArgumentException(
                        "a priced package must state both its option net premium and its stock cash flow");
            }
            if (grossPackageNetCents != optionNetPremiumCents + stockCashFlowCents) {
                throw new IllegalArgumentException("gross package net must reconcile to option net plus"
                        + " stock cash flow: " + grossPackageNetCents + " != " + optionNetPremiumCents
                        + " + " + stockCashFlowCents);
            }
        }

        // The fee invariant, promoted from the held-close lane (PositionLifecycleReceipt.CloseQuote)
        // where it was already proven, to every surface that quotes a package.
        if (afterFeeNetCents != null && (grossPackageNetCents == null || openingFeesCents == null)) {
            throw new IllegalArgumentException("an after-fee net needs both a gross net and a fee");
        }
        if (grossPackageNetCents != null && openingFeesCents != null) {
            if (afterFeeNetCents == null) {
                throw new IllegalArgumentException("a package with a gross net and a fee must state its after-fee net");
            }
            if (afterFeeNetCents != grossPackageNetCents - openingFeesCents) {
                throw new IllegalArgumentException("after-fee net must reconcile to gross package net less fees");
            }
        }
    }

    /** True when this receipt carries a real, stated package price. */
    public boolean priced() {
        return valuationBasis != ValuationBasis.UNAVAILABLE;
    }

    /**
     * The package's gross opening value in the cost convention used by payoff and outcome
     * valuation: money paid is positive and money received is negative.
     *
     * <p>This is the one deliberate sign conversion between the ledger/package-price convention
     * and the valuation-kernel convention. Controllers must not repeat {@code -grossNet} ladders:
     * doing so is how a debit, credit, or legitimate zero entry acquires a different meaning on
     * another outcome surface. Fees are deliberately not folded into this amount; outcome kernels
     * consume the captured round-trip commission separately.</p>
     *
     * @return the gross entry cost, or {@code null} when this receipt is unavailable
     * @throws IllegalStateException when a closing-side receipt is used as an opening entry
     */
    public Long payoffEntryCostCents() {
        if (!priced()) return null;
        if (feeSide != FeeSide.OPENING) {
            throw new IllegalStateException("an outcome entry requires an OPENING package-price receipt");
        }
        return Math.negateExact(grossPackageNetCents);
    }

    /**
     * §3.2 fallthrough guard: no price at all, with the reason attached. Quantity is still stated
     * because the reader still needs to know what size was being priced.
     */
    public static PackagePriceReceipt unavailable(int quantity, FeeSide feeSide, String reason) {
        return new PackagePriceReceipt(Math.max(1, quantity), null, null, null, null, null, null, null, null,
                ValuationBasis.UNAVAILABLE, OrderInstruction.Executability.UNAVAILABLE,
                null, null, null, null, feeSide == null ? FeeSide.OPENING : feeSide,
                reason == null || reason.isBlank() ? "no package price is available" : reason);
    }

    /**
     * THE factory. Only the arithmetic nobody can disagree about is computed here — the after-fee
     * net and the resting limit read off the instruction. The two SIDES of the package are supplied
     * by the producer, each measured from what it actually priced, so the additive identity checked
     * in the compact constructor compares three independently obtained numbers.
     *
     * <p>This factory used to take the option net alone and set the stock side to
     * {@code grossPackageNetCents - optionNetPremiumCents}. That made the identity true by
     * construction for every producer in the product: a package net struck on the natural
     * executable book beside an option net struck on submitted prices reconciled perfectly, with
     * the whole mismatch silently reappearing as a fictional share cash flow. The check is only
     * worth having if the two sides are measured, not restated.</p>
     *
     * @param optionNetPremiumCents the OPTION-ONLY signed net, canonically
     *        {@link ProtocolEvaluator#optionEntryBasisCents} — pass it explicitly because a package
     *        repriced to a customer net cannot be re-derived from leg prices alone.
     * @param stockCashFlowCents the STOCK-ONLY signed cash, canonically
     *        {@link ProtocolEvaluator#stockEntryBasisCents}; exactly 0 for an option-only package.
     */
    public static PackagePriceReceipt of(int quantity,
                                         long grossPackageNetCents,
                                         long optionNetPremiumCents,
                                         long stockCashFlowCents,
                                         Long feesCents,
                                         Long estimatedRoundTripFeesCents,
                                         FeeSide feeSide,
                                         Long executableNetCents,
                                         OrderInstruction instruction,
                                         OrderInstruction.Executability executability,
                                         ValuationBasis valuationBasis,
                                         String source,
                                         String freshness,
                                         Long observedAt,
                                         String fingerprint) {
        if (valuationBasis == null || valuationBasis == ValuationBasis.UNAVAILABLE) {
            throw new IllegalArgumentException("a priced receipt needs a real valuation basis; "
                    + "use unavailable(...) when there is no price");
        }
        // A negative commission is not a price this record is allowed to publish, and silently
        // clamping it to 0 would state "this order is free" on a caller's arithmetic bug — the
        // exact substituted zero §3.2 forbids, and one the compact constructor already refuses.
        // The clamp also outranked that rule, so the invariant could never fire. Let it fire.
        Long fees = feesCents;
        return new PackagePriceReceipt(quantity,
                optionNetPremiumCents,
                stockCashFlowCents,
                grossPackageNetCents,
                fees,
                estimatedRoundTripFeesCents,
                fees == null ? null : grossPackageNetCents - fees,
                executableNetCents,
                instruction == null ? null : instruction.limitNetCents(),
                valuationBasis,
                executability,
                source, freshness, observedAt, fingerprint,
                feeSide == null ? FeeSide.OPENING : feeSide,
                null);
    }

    /**
     * Same factory, measuring BOTH sides from the priced legs through their canonical owners
     * {@link ProtocolEvaluator#optionEntryBasisCents} and
     * {@link ProtocolEvaluator#stockEntryBasisCents}. Use this when the legs carry the price (scan
     * candidates, closes, transformations); use {@link #of} when the package net was overridden to
     * a customer price the legs alone cannot reproduce — there the producer must also say which
     * side of the package absorbed the override.
     */
    public static PackagePriceReceipt ofLegs(List<Leg> legs,
                                             int quantity,
                                             long grossPackageNetCents,
                                             Long feesCents,
                                             Long estimatedRoundTripFeesCents,
                                             FeeSide feeSide,
                                             Long executableNetCents,
                                             OrderInstruction instruction,
                                             OrderInstruction.Executability executability,
                                             ValuationBasis valuationBasis,
                                             String source,
                                             String freshness,
                                             Long observedAt,
                                             String fingerprint) {
        return of(quantity, grossPackageNetCents,
                ProtocolEvaluator.optionEntryBasisCents(legs, quantity, grossPackageNetCents),
                ProtocolEvaluator.stockEntryBasisCents(legs, quantity),
                feesCents, estimatedRoundTripFeesCents, feeSide, executableNetCents,
                instruction, executability, valuationBasis,
                source, freshness, observedAt, fingerprint);
    }

    /** The package's own source: one name when every leg agrees, an explicit mixture otherwise. */
    public static String sourceOf(java.util.Collection<String> legSources) {
        java.util.Set<String> named = new java.util.LinkedHashSet<>();
        for (String source : legSources) if (source != null && !source.isBlank()) named.add(source);
        if (named.isEmpty()) return null;
        return named.size() == 1 ? named.iterator().next() : "multiple inputs";
    }

    /**
     * A package price is only as freshly observed as its STALEST leg — quoting the newest stamp
     * would overstate how current the whole package is.
     */
    public static Long observedAtOf(java.util.Collection<Long> legStamps) {
        Long oldest = null;
        for (Long stamp : legStamps) {
            if (stamp == null) continue;
            if (oldest == null || stamp < oldest) oldest = stamp;
        }
        return oldest;
    }

    /**
     * The same price, now that the commission actually charged for this order is known. Kept as a
     * transformation rather than a second construction so the after-fee arithmetic stays in one
     * place — a producer that learns the fee late cannot accidentally state a different net.
     */
    public PackagePriceReceipt withFees(long feesCents, Long estimatedRoundTripFeesCents) {
        if (!priced()) return this;
        // Same rule as #of: the commission charged is a fact, so a negative one is a caller defect
        // that must surface, not be quietly published as a free order.
        long fees = feesCents;
        return new PackagePriceReceipt(quantity, optionNetPremiumCents, stockCashFlowCents,
                grossPackageNetCents, fees, estimatedRoundTripFeesCents,
                grossPackageNetCents - fees, executableNetCents,
                restingLimitNetCents, valuationBasis, executability, source, freshness, observedAt,
                fingerprint, feeSide, unavailableReason);
    }

    /**
     * The identity of a priced package: same legs, same size, same price, same basis ⇒ same
     * fingerprint. Two surfaces showing the same fingerprint are quoting the same package priced
     * at the same moment; different fingerprints are what lets the UI say the rail was scanned
     * earlier rather than leaving the reader to wonder which number is wrong (§3.3).
     *
     * <p>Because a DIFFERENT fingerprint is a statement to the reader, the identity has to be of
     * the package's FINANCIAL content and nothing else. Serializing the {@link Leg} records
     * directly made it an identity of their Java representation instead: a scan holding a
     * {@code 255.3200} ask and an order preview holding the same price as {@code 255.32} — the two
     * are the same number, and the wire form deliberately strips the zeros — produced two
     * fingerprints for one package with every published amount, basis and observation stamp
     * identical. The UI then had to report a difference that did not exist. Leg ORDER is
     * presentational for the same reason and is normalized here too.</p>
     */
    public static String fingerprintOf(List<Leg> legs, int quantity, Long grossPackageNetCents,
                                       ValuationBasis basis, Long observedAt) {
        if (legs == null || legs.isEmpty()) return null;
        try {
            Map<String, Object> stable = new LinkedHashMap<>();
            stable.put("legs", legs.stream().map(PackagePriceReceipt::stableLeg).sorted().toList());
            stable.put("quantity", quantity);
            stable.put("grossPackageNetCents", grossPackageNetCents);
            stable.put("valuationBasis", basis == null ? null : basis.name());
            stable.put("observedAt", observedAt);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Json.canonical(stable).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("cannot fingerprint a package price", e);
        }
    }

    /** One leg as its financial content, in the ONE canonical decimal form {@link Money} owns. */
    private static String stableLeg(Leg leg) {
        return String.join("|",
                leg.action() == null ? "" : leg.action().name(),
                leg.isStock() ? "STOCK" : leg.type().name(),
                Money.canonicalPrice(leg.strike()),
                leg.expiration() == null ? "" : leg.expiration().toString(),
                Integer.toString(leg.ratio()),
                Integer.toString(leg.multiplier()),
                Money.canonicalPrice(leg.entryPrice()));
    }
}
