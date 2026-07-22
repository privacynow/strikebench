package io.liftandshift.strikebench.position;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.liftandshift.strikebench.model.OptionType;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Immutable fact receipt for the position that exists now. It composes the canonical evaluation,
 * campaign, accounting, event, and Book-risk owners by reference; it does not evaluate a second
 * candidate and it does not contain a management verdict.
 *
 * <p>Signed cash follows the ledger convention: money received is positive and money paid is
 * negative. A close quote therefore stays unambiguous for both long and short packages.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PositionLifecycleReceipt(
        String schemaVersion,
        String symbol,
        String positionFingerprint,
        History history,
        CurrentChoice currentChoice,
        CarryCollateral carryCollateral,
        AssignmentExit assignmentExit,
        Evidence evidence
) {
    public static final String SCHEMA_VERSION = "position-lifecycle-v1";
    public static final String FRESH_EYES_ECONOMICS_REF = "evaluation.assessment.economics";
    public static final String STANCE_REF = "evaluation.stance";

    public PositionLifecycleReceipt {
        required(schemaVersion, "schema version");
        required(symbol, "symbol");
        required(positionFingerprint, "position fingerprint");
        if (history == null || currentChoice == null || carryCollateral == null
                || assignmentExit == null || evidence == null) {
            throw new IllegalArgumentException("all four lifecycle lanes and evidence are required");
        }
    }

    /** Opening and campaign history. Missing history stays explicitly unavailable. */
    public record History(
            boolean available,
            Long signedOpeningCashCents,
            Long signedOptionOpeningCashCents,
            Long openingFeesCents,
            Long grossOpeningCreditCents,
            Long netOpeningCreditCents,
            Long grossPremiumCapturedCents,
            Double grossPremiumCapturedPct,
            Long netPnlIfClosedCents,
            Long campaignRealizedCents,
            Long campaignUnrealizedCents,
            String basis,
            String unavailableReason,
            List<String> sourceRefs
    ) {
        public History {
            sourceRefs = copy(sourceRefs);
            required(basis, "history basis");
            if (available && unavailableReason != null) {
                throw new IllegalArgumentException("available history cannot have an unavailable reason");
            }
            if (!available) required(unavailableReason, "history unavailable reason");
            nonNegative(openingFeesCents, "opening fees");
            nonNegative(grossOpeningCreditCents, "gross opening credit");
            nonNegative(netOpeningCreditCents, "net opening credit");
        }

        public static History unavailable(String reason, String basis) {
            return new History(false, null, null, null, null, null, null, null, null,
                    null, null, basis, reason, List.of());
        }
    }

    /** Today's executable choice. The fresh-eyes economics remains owned by the parent analysis. */
    public record CurrentChoice(
            CloseQuote close,
            String freshEyesQuestion,
            String freshEyesEconomicsRef,
            ForwardEconomics holdVsClose,
            Long expectedShortfallCents,
            String expectedShortfallBasis,
            String stanceRef,
            String basis,
            List<String> limitations
    ) {
        public CurrentChoice {
            if (close == null || holdVsClose == null) {
                throw new IllegalArgumentException("current choice requires close and hold-vs-close facts");
            }
            required(freshEyesQuestion, "fresh-eyes question");
            required(freshEyesEconomicsRef, "fresh-eyes economics reference");
            required(stanceRef, "stance reference");
            required(basis, "current-choice basis");
            limitations = copy(limitations);
            nonNegative(expectedShortfallCents, "expected shortfall");
            if (expectedShortfallCents != null) required(expectedShortfallBasis, "expected-shortfall basis");
        }
    }

    public record CloseQuote(
            boolean executable,
            Long signedMidCloseCashCents,
            Long signedExecutableCloseCashCents,
            Long signedOptionExecutableCloseCashCents,
            Long closingFeesCents,
            Long signedNetCloseCashCents,
            PositionDomain.PriceAuthority priceAuthority,
            String basis,
            String unavailableReason
    ) {
        public CloseQuote {
            required(basis, "close-quote basis");
            nonNegative(closingFeesCents, "closing fees");
            if (executable) {
                if (signedExecutableCloseCashCents == null || signedOptionExecutableCloseCashCents == null
                        || closingFeesCents == null
                        || signedNetCloseCashCents == null || priceAuthority == null) {
                    throw new IllegalArgumentException("an executable close needs cash, fees, net cash, and authority");
                }
                if (unavailableReason != null) {
                    throw new IllegalArgumentException("an executable close cannot have an unavailable reason");
                }
                if (signedNetCloseCashCents != signedExecutableCloseCashCents - closingFeesCents) {
                    throw new IllegalArgumentException("net close cash must reconcile to executable cash less fees");
                }
            } else {
                required(unavailableReason, "close unavailable reason");
            }
        }
    }

    /** Hold rather than close today, with opening economics transformed only for the changed cash leg. */
    public record ForwardEconomics(
            boolean available,
            Long marketEvAfterCostsCents,
            Long realizedVolEvAfterCostsCents,
            Long realisticLowAfterCostsCents,
            Long realisticHighAfterCostsCents,
            Long materialityCents,
            boolean observedEvidence,
            String basis,
            String unavailableReason
    ) {
        public ForwardEconomics {
            required(basis, "forward-economics basis");
            nonNegative(materialityCents, "materiality");
            if (!available) required(unavailableReason, "forward-economics unavailable reason");
            if (available && unavailableReason != null) {
                throw new IllegalArgumentException("available forward economics cannot have an unavailable reason");
            }
        }

        public static ForwardEconomics unavailable(String reason, String basis) {
            return new ForwardEconomics(false, null, null, null, null, null,
                    false, basis, reason);
        }
    }

    /** Premium carry, settlement income, and buying-power encumbrance remain separate facts. */
    public record CarryCollateral(
            Long grossRemainingPremiumCents,
            Double grossAnnualizedRemainingPremiumPct,
            Integer calendarDaysRemaining,
            AuthorityFacts.MoneyFact collateral,
            AuthorityFacts.RateFact concurrentCollateralIncome,
            AuthorityFacts.MoneyFact encumbrance,
            AuthorityFacts.MoneyFact capitalReleasedByClosing,
            Long sharesReleasedByClosing,
            String basis,
            List<String> limitations
    ) {
        public CarryCollateral {
            if (collateral == null || concurrentCollateralIncome == null || encumbrance == null
                    || capitalReleasedByClosing == null) {
                throw new IllegalArgumentException("carry/collateral authority facts are required");
            }
            nonNegative(grossRemainingPremiumCents, "gross remaining premium");
            nonNegative(calendarDaysRemaining, "calendar days remaining");
            nonNegative(sharesReleasedByClosing, "shares released");
            required(basis, "carry/collateral basis");
            limitations = copy(limitations);
        }
    }

    /** Assignment/call-away quantities are explicit per short contract geometry. */
    public record AssignmentExit(
            List<AssignmentLeg> legs,
            AuthorityFacts.MoneyFact taxLotBasisPerShare,
            AuthorityFacts.MoneyFact campaignBasisPerShare,
            List<EventCrossing> eventCrossings,
            String eventEvidenceStatus,
            String bookImpactRef,
            String basis,
            List<String> limitations
    ) {
        public AssignmentExit {
            legs = copy(legs);
            eventCrossings = copy(eventCrossings);
            limitations = copy(limitations);
            if (taxLotBasisPerShare == null || campaignBasisPerShare == null) {
                throw new IllegalArgumentException("both basis systems must be present, even when unavailable");
            }
            required(eventEvidenceStatus, "event evidence status");
            required(bookImpactRef, "book-impact reference");
            required(basis, "assignment/exit basis");
        }
    }

    public record AssignmentLeg(
            OptionType optionType,
            LocalDate expiration,
            long strikeCentsPerShare,
            long shares,
            long strikeDollarsCents,
            Long freshEyesEffectivePricePerShareCents,
            String consequence,
            String basis
    ) {
        public AssignmentLeg {
            if (optionType == null || expiration == null) {
                throw new IllegalArgumentException("assignment leg needs option type and expiration");
            }
            if (strikeCentsPerShare <= 0 || shares <= 0 || strikeDollarsCents <= 0) {
                throw new IllegalArgumentException("assignment leg needs positive strike, shares, and dollars");
            }
            if (strikeDollarsCents != Math.multiplyExact(strikeCentsPerShare, shares)) {
                throw new IllegalArgumentException("assignment dollars must equal strike times shares");
            }
            required(consequence, "assignment consequence");
            required(basis, "assignment-leg basis");
        }
    }

    public record EventCrossing(
            String eventType,
            LocalDate eventDate,
            String session,
            String status,
            String source,
            String url,
            OffsetDateTime observedAt,
            String payloadFingerprint
    ) {
        public EventCrossing {
            required(eventType, "event type");
            if (eventDate == null) throw new IllegalArgumentException("event date is required");
            required(status, "event status");
            required(source, "event source");
        }
    }

    public record Evidence(
            OffsetDateTime observedAt,
            String reconciliationStatus,
            String marketSnapshotFingerprint,
            String modelFingerprint,
            String policyFingerprint,
            List<String> sourceRefs,
            List<String> limitations
    ) {
        public Evidence {
            if (observedAt == null) throw new IllegalArgumentException("evidence timestamp is required");
            required(reconciliationStatus, "reconciliation status");
            required(marketSnapshotFingerprint, "market snapshot fingerprint");
            required(modelFingerprint, "model fingerprint");
            required(policyFingerprint, "policy fingerprint");
            sourceRefs = copy(sourceRefs);
            limitations = copy(limitations);
        }
    }

    private static void required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
    }

    private static void nonNegative(Long value, String label) {
        if (value != null && value < 0) throw new IllegalArgumentException(label + " cannot be negative");
    }

    private static void nonNegative(Integer value, String label) {
        if (value != null && value < 0) throw new IllegalArgumentException(label + " cannot be negative");
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
