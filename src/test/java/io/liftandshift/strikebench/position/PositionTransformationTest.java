package io.liftandshift.strikebench.position;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PositionTransformationTest {
    private static final LocalDate EXPIRY = LocalDate.parse("2026-08-21");

    @Test
    void partialCloseNamesTheSurvivingFifteenLotIdentityAndRiskDelta() {
        var before = pkg("before", 20, option(0, "SELL", "PUT", "980", 20), option(1, "BUY", "PUT", "970", 20));
        var after = pkg("after", 15, option(0, "SELL", "PUT", "980", 15), option(1, "BUY", "PUT", "970", 15));

        var preview = PositionTransformation.preview(new PositionTransformation.Request(
                PositionTransformation.Action.PARTIAL_CLOSE, before, after,
                risk(20_000, 20_000, true), risk(15_000, 15_000, true), 30_000L));

        assertThat(preview.beforeIdentity().family()).isEqualTo("CREDIT_PUT_SPREAD");
        assertThat(preview.afterIdentity().family()).isEqualTo("CREDIT_PUT_SPREAD");
        assertThat(preview.delta().maxLossCents()).isEqualTo(-5_000L);
        assertThat(preview.warnings()).anyMatch(s -> s.contains("5 of 20") && s.contains("15 survive"));
        assertThat(preview.identityChanged()).isTrue();
    }

    @Test
    void removingAProtectivePutNamesTheShortPutAndContinuingDownside() {
        var shortPut = option(0, "SELL", "PUT", "980", 1);
        var hedge = option(1, "BUY", "PUT", "970", 1);
        var before = pkg("before", 1, shortPut, hedge);
        var after = pkg("after", 1, shortPut);

        var preview = PositionTransformation.preview(new PositionTransformation.Request(
                PositionTransformation.Action.REMOVE_LEG, before, after,
                risk(80_000, 100_000, true), risk(9_500_000, 9_800_000, true), null));

        assertThat(preview.afterIdentity().label()).isEqualTo("Short put");
        assertThat(preview.afterObligations().putAssignmentCashCents()).isEqualTo(9_800_000L);
        assertThat(preview.warnings()).anyMatch(s -> s.contains("970 put protects the short 980 put")
                && s.contains("$100 more loss per $1 move below 970"));
    }

    @Test
    void rollStatesTheRealizedLossAndRequiresFreshEyesAfterRisk() {
        var before = pkg("before", 1, option(0, "SELL", "PUT", "980", 1), option(1, "BUY", "PUT", "970", 1));
        var after = new PositionPackage("after", PositionDomain.PackageSource.PRACTICE_TRADE,
                PositionDomain.ExecutionLane.PRACTICE, "MU", 1, null,
                OffsetDateTime.parse("2026-07-15T12:00:00Z"), List.of(
                optionAt(0, "SELL", "PUT", "960", LocalDate.parse("2026-09-18"), 1),
                optionAt(1, "BUY", "PUT", "950", LocalDate.parse("2026-09-18"), 1)));

        var preview = PositionTransformation.preview(new PositionTransformation.Request(
                PositionTransformation.Action.ROLL, before, after,
                risk(80_000, 100_000, true), risk(75_000, 100_000, true), -221_200L));

        assertThat(preview.warnings()).anyMatch(s -> s.contains("realizes -$2,212.00")
                && s.contains("judged fresh-eyes") && s.contains("not carried forward or hidden"));
        assertThat(preview.realizedClosingCents()).isEqualTo(-221_200L);
    }

    @Test
    void assignmentCanLeaveTheOtherHedgeVisibleInsteadOfPretendingTheStructureVanished() {
        var before = pkg("before", 1,
                option(0, "SELL", "PUT", "980", 1), option(1, "BUY", "PUT", "970", 1));
        var after = pkg("after", 1,
                option(1, "BUY", "PUT", "970", 1), stock(2, "BUY", 100));

        var preview = PositionTransformation.preview(new PositionTransformation.Request(
                PositionTransformation.Action.ASSIGNMENT, before, after,
                risk(80_000, 100_000, true), risk(1_000_000, 0, true), -150_000L));

        assertThat(preview.afterIdentity().family()).isEqualTo("PROTECTIVE_PUT");
        assertThat(preview.warnings()).anyMatch(s -> s.contains("leaves another option in place")
                && s.contains("Protective put"));
    }

    @Test
    void blockedAfterIdentityRemainsVisibleButCannotBeApplied() {
        var before = pkg("before", 1, option(0, "SELL", "CALL", "1000", 1), option(1, "BUY", "CALL", "1010", 1));
        var after = pkg("after", 1, option(0, "SELL", "CALL", "1000", 1));

        var preview = PositionTransformation.preview(new PositionTransformation.Request(
                PositionTransformation.Action.REMOVE_LEG, before, after,
                risk(75_000, 100_000, true),
                new PositionTransformation.RiskSnapshot(null, 0, null, false,
                        List.of("Undefined upside risk"), "observed"), null));

        assertThat(preview.afterIdentity().family()).isEqualTo("NAKED_CALL");
        assertThat(preview.applicable()).isFalse();
        assertThat(preview.warnings()).anyMatch(s -> s.contains("Teaching case only"));
        assertThat(preview.warnings()).anyMatch(s -> s.contains("blocked-family teaching case"));
    }

    @Test
    void oneLongCallCannotBeCountedTwiceAgainstTwoShortCalls() {
        var before = pkg("before", 1,
                option(0, "SELL", "CALL", "1000", 1), option(1, "BUY", "CALL", "1020", 1));
        var after = pkg("after", 1,
                option(0, "SELL", "CALL", "1000", 1), option(1, "SELL", "CALL", "1010", 1),
                option(2, "BUY", "CALL", "1020", 1));

        var preview = PositionTransformation.preview(new PositionTransformation.Request(
                PositionTransformation.Action.ADD_LEG, before, after,
                risk(80_000, 100_000, true),
                new PositionTransformation.RiskSnapshot(null, 0, null, false,
                        List.of("One short call remains uncovered"), "observed"), null));

        assertThat(preview.afterObligations().callDeliveryShares()).isEqualTo(200);
        assertThat(preview.afterObligations().uncappedUpside()).isTrue();
        assertThat(preview.applicable()).isFalse();
    }

    @Test
    void partialHedgeRemovalNamesOnlyTheNewlyExposedUnits() {
        var before = pkg("before", 2,
                option(0, "SELL", "PUT", "980", 2), option(1, "BUY", "PUT", "970", 2));
        var after = pkg("after", 2,
                option(0, "SELL", "PUT", "980", 2), option(1, "BUY", "PUT", "970", 1));

        var preview = PositionTransformation.preview(new PositionTransformation.Request(
                PositionTransformation.Action.LEG_CLOSE, before, after,
                risk(160_000, 200_000, true), risk(9_800_000, 9_800_000, true), 5_000L));

        assertThat(preview.warnings()).anyMatch(s -> s.contains("$100 more loss per $1 move below 970"));
    }

    @Test
    void exactRealBrokerFactRemainsRecordableEvenWhenPracticeWouldBlockIt() {
        var before = pkg("before", PositionDomain.ExecutionLane.REAL, PositionDomain.PackageSource.TRACKED_STRUCTURE,
                option(0, "SELL", "CALL", "1000", 1), option(1, "BUY", "CALL", "1010", 1));
        var after = pkg("after", PositionDomain.ExecutionLane.REAL, PositionDomain.PackageSource.TRACKED_STRUCTURE,
                option(0, "SELL", "CALL", "1000", 1));

        var preview = PositionTransformation.preview(new PositionTransformation.Request(
                PositionTransformation.Action.REMOVE_LEG, before, after,
                risk(75_000, 100_000, true),
                new PositionTransformation.RiskSnapshot(null, 0, null, false,
                        List.of("Undefined upside risk"), "broker reported"), null));

        assertThat(preview.applicable()).isTrue();
        assertThat(preview.warnings()).anyMatch(s -> s.contains("fails the Practice placement checks")
                && s.contains("broker-reported fact remains recordable"));
    }

    @Test
    void transformationCannotSilentlySwitchExecutionLane() {
        var before = pkg("before", 1,
                option(0, "SELL", "PUT", "980", 1), option(1, "BUY", "PUT", "970", 1));
        var after = pkg("after", PositionDomain.ExecutionLane.REAL, PositionDomain.PackageSource.TRACKED_STRUCTURE,
                option(0, "SELL", "PUT", "960", 1), option(1, "BUY", "PUT", "950", 1));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> PositionTransformation.preview(
                        new PositionTransformation.Request(PositionTransformation.Action.ROLL, before, after,
                                risk(80_000, 100_000, true), risk(75_000, 100_000, true), -10_000L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot switch symbol, execution lane, or package source");
    }

    private static PositionPackage pkg(String id, long quantity, PositionPackage.Leg... legs) {
        return new PositionPackage(id, PositionDomain.PackageSource.PRACTICE_TRADE,
                PositionDomain.ExecutionLane.PRACTICE, "MU", quantity, null,
                OffsetDateTime.parse("2026-07-15T12:00:00Z"), List.of(legs));
    }

    private static PositionPackage pkg(String id, PositionDomain.ExecutionLane lane,
                                       PositionDomain.PackageSource source, PositionPackage.Leg... legs) {
        return new PositionPackage(id, source, lane, "MU", 1, null,
                OffsetDateTime.parse("2026-07-15T12:00:00Z"), List.of(legs));
    }

    private static PositionPackage.Leg option(int index, String action, String type, String strike, long quantity) {
        return optionAt(index, action, type, strike, EXPIRY, quantity);
    }

    private static PositionPackage.Leg optionAt(int index, String action, String type, String strike,
                                                 LocalDate expiration, long quantity) {
        return new PositionPackage.Leg(index, action, "OPTION", "MU", type, new BigDecimal(strike), expiration,
                quantity, 100, new BigDecimal("10.00"), PositionDomain.PriceAuthority.OBSERVED);
    }

    private static PositionPackage.Leg stock(int index, String action, long quantity) {
        return new PositionPackage.Leg(index, action, "STOCK", "MU", null, null, null,
                quantity, 1, new BigDecimal("980.00"), PositionDomain.PriceAuthority.OBSERVED);
    }

    private static PositionTransformation.RiskSnapshot risk(long maxLoss, long reserve, boolean eligible) {
        return new PositionTransformation.RiskSnapshot(maxLoss, reserve, 50_000L, eligible, List.of(), "observed");
    }
}
