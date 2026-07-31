package io.liftandshift.strikebench.paper;

import io.liftandshift.strikebench.market.ExecutablePrice;
import io.liftandshift.strikebench.market.MarketLane;
import io.liftandshift.strikebench.model.DataAge;
import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.model.DataProvenance;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.OptionQuote;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.pricing.PayoffCurve;
import io.liftandshift.strikebench.util.Fees;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The single package-level owner for current-book entry pricing.
 *
 * <p>Callers supply already-captured quote evidence; this class alone selects bid/ask sides,
 * decides whether that evidence is executable in the selected lane, performs the exact
 * quantity/multiplier arithmetic, aggregates source/freshness/time, and constructs the
 * {@link PackagePriceReceipt}. Fetching remains with the caller so a comparison can retain one
 * immutable book, but no recommendation, ticket, or outcome surface can maintain a second fill
 * convention.</p>
 */
public final class ExecutablePackagePricer {
    private ExecutablePackagePricer() {}

    /** Execution refuses anything that is not currently tradeable; analysis may retain labeled marks. */
    public enum Policy { EXECUTABLE_ONLY, ANALYSIS }

    /** One requested leg paired with the exact captured book that may price it. */
    public record LegBook(Leg leg, BigDecimal bid, BigDecimal ask, BigDecimal mid,
                          DataEvidence evidence, Long observedAt) {
        public LegBook {
            Objects.requireNonNull(leg, "leg");
            evidence = evidence == null ? DataEvidence.missing("no quote evidence") : evidence;
        }

        public static LegBook from(Leg leg, Quote quote) {
            return quote == null
                    ? missing(leg, "no underlying quote")
                    : new LegBook(leg, quote.bid(), quote.ask(), quote.mark(),
                            quote.evidence(), quote.asOfEpochMs());
        }

        public static LegBook from(Leg leg, OptionQuote quote) {
            return quote == null
                    ? missing(leg, "no option quote")
                    : new LegBook(leg, quote.bid(), quote.ask(), quote.mid(),
                            quote.evidence(), quote.asOfEpochMs());
        }

        public static LegBook from(Leg leg, MarksSource.LegMark mark) {
            return mark == null
                    ? missing(leg, "no current leg mark")
                    : new LegBook(leg, mark.bid(), mark.ask(), mark.mid(),
                            mark.evidence(), mark.asOfEpochMs());
        }

        public static LegBook missing(Leg leg, String reason) {
            return new LegBook(leg, null, null, null, DataEvidence.missing(reason), null);
        }
    }

    /** Aligned result for one leg; tickets reuse this for their exact per-leg evidence rows. */
    public record LegPrice(Leg requested, Leg selected, Leg executable, Leg midpoint,
                           BigDecimal actionSide, BigDecimal midpointPrice,
                           DataEvidence evidence, Long observedAt, boolean selectedAtMidpoint,
                           String unavailableReason) {}

    /**
     * One captured book. The selected legs and package receipt always share the same basis,
     * source, freshness, observation stamp, and fingerprint.
     */
    public record Book(List<LegPrice> legPrices, List<Leg> pricedLegs,
                       List<Leg> executableLegs, List<Leg> midpointLegs,
                       DataEvidence evidence, Freshness freshness, Long observedAt,
                       boolean executable, boolean usedMidpoint, String unavailableReason) {
        public Book {
            legPrices = legPrices == null ? List.of() : List.copyOf(legPrices);
            pricedLegs = pricedLegs == null ? List.of() : List.copyOf(pricedLegs);
            executableLegs = executableLegs == null ? List.of() : List.copyOf(executableLegs);
            midpointLegs = midpointLegs == null ? List.of() : List.copyOf(midpointLegs);
            evidence = evidence == null ? DataEvidence.missing("no package evidence") : evidence;
            freshness = freshness == null ? Freshness.MISSING : freshness;
        }

        public boolean priced() {
            return unavailableReason == null && !pricedLegs.isEmpty()
                    && pricedLegs.size() == legPrices.size();
        }

        public Long grossNetCents(int quantity) {
            return priced() ? PayoffCurve.of(pricedLegs, quantity).entryNetPremiumCents() : null;
        }

        public Long executableNetCents(int quantity) {
            return executable && executableLegs.size() == legPrices.size()
                    ? PayoffCurve.of(executableLegs, quantity).entryNetPremiumCents() : null;
        }

        public Long midpointNetCents(int quantity) {
            return midpointLegs.size() == legPrices.size()
                    ? PayoffCurve.of(midpointLegs, quantity).entryNetPremiumCents() : null;
        }

        /** Build the one typed receipt from this exact book; no caller repeats its arithmetic. */
        public PackagePriceReceipt receipt(int quantity, Fees.Schedule fees,
                                           PackagePriceReceipt.FeeSide feeSide,
                                           OrderInstruction instruction) {
            if (!priced()) {
                return PackagePriceReceipt.unavailable(quantity, feeSide,
                        unavailableReason == null ? "the complete package has no price" : unavailableReason);
            }
            Objects.requireNonNull(fees, "fees");
            OrderInstruction order = instruction == null ? OrderInstruction.market() : instruction;
            long gross = grossNetCents(quantity);
            Long natural = executableNetCents(quantity);
            PackagePriceReceipt.ValuationBasis basis = executable
                    ? PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK
                    : usedMidpoint ? PackagePriceReceipt.ValuationBasis.MID_MARKET
                    : PackagePriceReceipt.ValuationBasis.MODELED;
            OrderInstruction.Executability executability =
                    order.executability(natural, executable);
            long orderFees = feeSide == PackagePriceReceipt.FeeSide.CLOSING
                    ? fees.closingCents() : fees.openingCents();
            return PackagePriceReceipt.ofLegs(pricedLegs, quantity, gross,
                    orderFees, fees.roundTripCents(), feeSide,
                    natural, order, executability, basis,
                    evidence.source(), freshness.name(), observedAt,
                    PackagePriceReceipt.fingerprintOf(pricedLegs, quantity, gross, basis, observedAt));
        }
    }

    public static Book price(List<LegBook> inputs, MarketLane lane, Policy policy) {
        if (inputs == null || inputs.isEmpty()) {
            return unavailable(List.of(), "the package has no legs");
        }
        Objects.requireNonNull(lane, "lane");
        Objects.requireNonNull(policy, "policy");

        List<LegPrice> legPrices = new ArrayList<>(inputs.size());
        List<Leg> selected = new ArrayList<>(inputs.size());
        List<Leg> executable = new ArrayList<>(inputs.size());
        List<Leg> midpoints = new ArrayList<>(inputs.size());
        List<DataEvidence> evidence = new ArrayList<>(inputs.size());
        List<Long> stamps = new ArrayList<>(inputs.size());
        boolean allExecutable = true;
        boolean usedMidpoint = false;
        String packageReason = null;

        for (LegBook input : inputs) {
            Leg leg = input.leg();
            DataEvidence legEvidence = input.evidence();
            evidence.add(legEvidence);
            stamps.add(input.observedAt());

            BigDecimal actionSide = ExecutablePrice.forAction(input.bid(), input.ask(), leg.action());
            // Keep a true two-sided midpoint distinct from a quote's display mark. Quote.mark()
            // and OptionQuote.mid() may lawfully fall back to last/previous-close evidence; naming
            // that fallback MID_MARKET would turn a provenance fact into a false execution claim.
            BigDecimal midpoint = ExecutablePrice.midpoint(input.bid(), input.ask());
            BigDecimal analysisMark = positive(input.mid()) ? input.mid() : midpoint;
            boolean evidenceExecutable = legEvidence.executableIn(lane);
            boolean evidenceUsable = legEvidence.usableIn(lane);
            Leg executableLeg = evidenceExecutable && actionSide != null ? withPrice(leg, actionSide) : null;
            Leg midpointLeg = midpoint == null ? null : withPrice(leg, midpoint);
            Leg selectedLeg = executableLeg;
            boolean selectedAtMidpoint = false;
            String reason = null;

            if (selectedLeg == null) {
                allExecutable = false;
                if (policy == Policy.ANALYSIS && evidenceUsable) {
                    if (actionSide != null) {
                        selectedLeg = withPrice(leg, actionSide);
                    } else if (analysisMark != null) {
                        selectedLeg = withPrice(leg, analysisMark);
                        selectedAtMidpoint = midpoint != null
                                && analysisMark.compareTo(midpoint) == 0;
                        usedMidpoint |= selectedAtMidpoint;
                    }
                }
            }
            if (selectedLeg == null) {
                reason = unavailableReason(leg, legEvidence, lane, actionSide, analysisMark,
                        ExecutablePrice.crossed(input.bid(), input.ask()), policy);
                if (packageReason == null) packageReason = reason;
            }

            if (selectedLeg != null) selected.add(selectedLeg);
            if (executableLeg != null) executable.add(executableLeg);
            if (midpointLeg != null) midpoints.add(midpointLeg);
            legPrices.add(new LegPrice(leg, selectedLeg, executableLeg, midpointLeg,
                    actionSide, midpoint, legEvidence, input.observedAt(),
                    selectedAtMidpoint, reason));
        }

        DataEvidence aggregate = DataEvidence.aggregate(evidence);
        return new Book(legPrices, packageReason == null ? selected : List.of(),
                allExecutable ? executable : List.of(),
                midpoints.size() == inputs.size() ? midpoints : List.of(),
                aggregate, aggregate.freshness(),
                PackagePriceReceipt.observedAtOf(stamps),
                allExecutable, usedMidpoint, packageReason);
    }

    private static Book unavailable(List<LegPrice> legs, String reason) {
        return new Book(legs, List.of(), List.of(), List.of(),
                DataEvidence.missing(reason), Freshness.MISSING, null,
                false, false, reason);
    }

    private static Leg withPrice(Leg leg, BigDecimal price) {
        return new Leg(leg.action(), leg.type(), leg.strike(), leg.expiration(),
                leg.ratio(), price, leg.multiplier());
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static String unavailableReason(Leg leg, DataEvidence evidence, MarketLane lane,
                                            BigDecimal actionSide, BigDecimal midpoint,
                                            boolean crossedBook, Policy policy) {
        String name = describe(leg);
        if (evidence.provenance() == DataProvenance.MISSING || evidence.age() == DataAge.MISSING) {
            return "No current quote evidence is available for " + name + ".";
        }
        if (policy == Policy.EXECUTABLE_ONLY && !evidence.executableIn(lane)) {
            return "The " + name + " quote is " + evidence.provenance() + " / " + evidence.age()
                    + " evidence and is not executable in the " + lane + " market.";
        }
        if (policy == Policy.ANALYSIS && !evidence.usableIn(lane)) {
            return "The " + name + " quote belongs to " + evidence.provenance()
                    + " evidence and cannot be used in the " + lane + " market.";
        }
        if (crossedBook) {
            return "The " + name + " quote is crossed (bid exceeds ask) and is not executable.";
        }
        if (actionSide == null && midpoint == null) {
            return "No executable " + (leg.action() == io.liftandshift.strikebench.model.LegAction.BUY
                    ? "ask" : "bid") + " or usable mark is available for " + name + ".";
        }
        return "No executable " + (leg.action() == io.liftandshift.strikebench.model.LegAction.BUY
                ? "ask" : "bid") + " is available for " + name + ".";
    }

    private static String describe(Leg leg) {
        if (leg.isStock()) return "stock leg";
        return leg.action() + " " + leg.type() + " " + leg.strike() + " " + leg.expiration();
    }

}
