package io.liftandshift.strikebench.strategy;

import io.liftandshift.strikebench.position.PositionDomain;
import io.liftandshift.strikebench.position.PositionPackage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyCatalogTest {
    private static final LocalDate NEAR = LocalDate.parse("2026-08-21");
    private static final LocalDate FAR = LocalDate.parse("2026-09-18");

    @Test
    void everyFamilyHasCompleteServerOwnedMetadata() {
        assertThat(StrategyCatalog.families()).hasSize(StrategyFamily.values().length);
        assertThat(StrategyCatalog.families()).allSatisfy(entry -> {
            assertThat(entry.name()).isNotBlank();
            assertThat(entry.display()).isNotBlank();
            assertThat(entry.category()).isNotBlank();
            assertThat(entry.summary()).isNotBlank();
            assertThat(entry.payoffShape()).isNotBlank();
            assertThat(entry.structureGroup()).isNotBlank();
            assertThat(entry.intents()).isNotEmpty();
            StrategyFamily family = StrategyFamily.valueOf(entry.name());
            assertThat(entry.definedRisk()).isEqualTo(family.definedRisk());
            assertThat(entry.blockedByDefault()).isEqualTo(family.blockedByDefault());
        });
    }

    @Test
    void concreteTemplatesAreUniqueAndNeverInventAnEngineFamily() {
        var keys = new HashSet<String>();
        Set<String> families = new HashSet<>();
        for (StrategyFamily family : StrategyFamily.values()) families.add(family.name());
        families.add("CUSTOM");

        assertThat(StrategyCatalog.templates()).hasSize(33).allSatisfy(template -> {
            assertThat(keys.add(template.key())).as("unique template key " + template.key()).isTrue();
            assertThat(families).contains(template.family());
            assertThat(template.display()).isNotBlank();
            assertThat(template.summary()).isNotBlank();
            assertThat(template.payoffShape()).isNotBlank();
        });
        assertThat(keys).contains("PMCC", "RISK_REVERSAL", "SYNTHETIC_LONG", "SYNTHETIC_SHORT",
                "CALL_BACKSPREAD", "PUT_BACKSPREAD", "IRON_CONDOR");
    }

    @Test
    void surfaceEligibilityIsHonest() {
        assertThat(StrategyCatalog.families().stream().filter(StrategyCatalog.FamilyEntry::scenarioEnabled))
                .hasSize(21)
                .allSatisfy(entry -> assertThat(entry.blockedByDefault()).isFalse());
        assertThat(StrategyCatalog.families().stream().filter(StrategyCatalog.FamilyEntry::backtestEnabled))
                .hasSize(14)
                .allSatisfy(entry -> {
                    assertThat(entry.blockedByDefault()).isFalse();
                    assertThat(entry.multiExpiration()).isFalse();
                });
    }

    @Test
    void exactLegClassifierRetainsEveryFormerEditorCatalogMatchOnTheServer() {
        List<Shape> shapes = List.of(
                family("covered call", "COVERED_CALL", stock(0, "BUY", 100), call(1, "SELL", "105", NEAR, 1)),
                family("protective put", "PROTECTIVE_PUT", stock(0, "BUY", 100), put(1, "BUY", "95", NEAR, 1)),
                family("protective collar", "PROTECTIVE_COLLAR", stock(0, "BUY", 100),
                        put(1, "BUY", "95", NEAR, 1), call(2, "SELL", "105", NEAR, 1)),
                family("covered strangle", "COVERED_STRANGLE", stock(0, "BUY", 100),
                        call(1, "SELL", "105", NEAR, 1), put(2, "SELL", "95", NEAR, 1)),
                family("covered call with put-spread floor", "COVERED_CALL_PUT_SPREAD", stock(0, "BUY", 100),
                        call(1, "SELL", "110", NEAR, 1), put(2, "BUY", "95", NEAR, 1), put(3, "SELL", "85", NEAR, 1)),
                family("covered call with long-call overlay", "COVERED_CALL_CALL_OVERLAY", stock(0, "BUY", 100),
                        call(1, "SELL", "105", NEAR, 1), call(2, "BUY", "115", NEAR, 1)),
                family("long call", "LONG_CALL", call(0, "BUY", "100", NEAR, 1)),
                family("long put", "LONG_PUT", put(0, "BUY", "100", NEAR, 1)),
                family("naked call", "NAKED_CALL", call(0, "SELL", "100", NEAR, 1)),
                custom("short put needs account context", null, put(0, "SELL", "100", NEAR, 1)),
                family("long straddle", "LONG_STRADDLE", call(0, "BUY", "100", NEAR, 1), put(1, "BUY", "100", NEAR, 1)),
                family("long strangle", "LONG_STRANGLE", call(0, "BUY", "105", NEAR, 1), put(1, "BUY", "95", NEAR, 1)),
                family("short straddle", "SHORT_STRADDLE", call(0, "SELL", "100", NEAR, 1), put(1, "SELL", "100", NEAR, 1)),
                family("short strangle", "SHORT_STRANGLE", call(0, "SELL", "105", NEAR, 1), put(1, "SELL", "95", NEAR, 1)),
                template("synthetic long", "SYNTHETIC_LONG", call(0, "BUY", "100", NEAR, 1), put(1, "SELL", "100", NEAR, 1)),
                template("synthetic short", "SYNTHETIC_SHORT", call(0, "SELL", "100", NEAR, 1), put(1, "BUY", "100", NEAR, 1)),
                template("risk reversal", "RISK_REVERSAL", call(0, "BUY", "105", NEAR, 1), put(1, "SELL", "95", NEAR, 1)),
                family("call calendar", "CALENDAR_CALL", call(0, "SELL", "100", NEAR, 1), call(1, "BUY", "100", FAR, 1)),
                family("put calendar", "CALENDAR_PUT", put(0, "SELL", "100", NEAR, 1), put(1, "BUY", "100", FAR, 1)),
                family("call diagonal", "DIAGONAL_CALL", call(0, "SELL", "105", NEAR, 1), call(1, "BUY", "95", FAR, 1)),
                family("put diagonal", "DIAGONAL_PUT", put(0, "SELL", "95", NEAR, 1), put(1, "BUY", "105", FAR, 1)),
                template("call ratio backspread", "CALL_BACKSPREAD", call(0, "SELL", "100", NEAR, 1), call(1, "BUY", "105", NEAR, 2)),
                template("put ratio backspread", "PUT_BACKSPREAD", put(0, "SELL", "100", NEAR, 1), put(1, "BUY", "95", NEAR, 2)),
                family("call debit spread", "DEBIT_CALL_SPREAD", call(0, "BUY", "95", NEAR, 1), call(1, "SELL", "105", NEAR, 1)),
                family("call credit spread", "CREDIT_CALL_SPREAD", call(0, "SELL", "95", NEAR, 1), call(1, "BUY", "105", NEAR, 1)),
                family("put debit spread", "DEBIT_PUT_SPREAD", put(0, "SELL", "95", NEAR, 1), put(1, "BUY", "105", NEAR, 1)),
                family("put credit spread", "CREDIT_PUT_SPREAD", put(0, "BUY", "95", NEAR, 1), put(1, "SELL", "105", NEAR, 1)),
                family("call butterfly", "LONG_CALL_BUTTERFLY", call(0, "BUY", "95", NEAR, 1),
                        call(1, "SELL", "100", NEAR, 2), call(2, "BUY", "105", NEAR, 1)),
                family("put butterfly", "LONG_PUT_BUTTERFLY", put(0, "BUY", "95", NEAR, 1),
                        put(1, "SELL", "100", NEAR, 2), put(2, "BUY", "105", NEAR, 1)),
                family("iron butterfly", "IRON_BUTTERFLY", put(0, "BUY", "90", NEAR, 1),
                        put(1, "SELL", "100", NEAR, 1), call(2, "SELL", "100", NEAR, 1), call(3, "BUY", "110", NEAR, 1)),
                family("iron condor", "IRON_CONDOR", put(0, "BUY", "90", NEAR, 1),
                        put(1, "SELL", "95", NEAR, 1), call(2, "SELL", "105", NEAR, 1), call(3, "BUY", "110", NEAR, 1))
        );

        for (Shape shape : shapes) {
            StrategyCatalog.PositionIdentity actual = StrategyCatalog.identify(pkg(shape.legs()));
            assertThat(actual.family()).as(shape.name()).isEqualTo(shape.family());
            assertThat(actual.template()).as(shape.name()).isEqualTo(shape.template());
            assertThat(actual.label()).as(shape.name()).isNotBlank();
            assertThat(actual.summary()).as(shape.name()).isNotBlank();
        }
    }

    @Test
    void nonstandardRatiosAndPartialCoverageRemainExactCustomPackages() {
        assertThat(StrategyCatalog.identify(pkg(
                call(0, "BUY", "95", NEAR, 1), call(1, "SELL", "105", NEAR, 3))).custom()).isTrue();
        assertThat(StrategyCatalog.identify(pkg(
                call(0, "BUY", "100", NEAR, 2), put(1, "BUY", "100", NEAR, 1))).custom()).isTrue();
        assertThat(StrategyCatalog.identify(pkg(
                stock(0, "BUY", 200), call(1, "SELL", "105", NEAR, 1))).custom()).isTrue();
        assertThat(StrategyCatalog.identify(pkg(
                put(0, "BUY", "90", NEAR, 2), put(1, "SELL", "95", NEAR, 1),
                call(2, "SELL", "105", NEAR, 1), call(3, "BUY", "110", NEAR, 1))).custom()).isTrue();
    }

    @Test
    void stockOnlyLifecycleSurvivorIsNamedAsSharesRatherThanStockAndOptions() {
        var longShares = StrategyCatalog.identify(pkg(stock(0, "BUY", 100)));
        var shortShares = StrategyCatalog.identify(pkg(stock(0, "SELL", 100)));

        assertThat(longShares.label()).isEqualTo("Long shares");
        assertThat(longShares.summary()).contains("owned shares");
        assertThat(shortShares.label()).isEqualTo("Short shares");
        assertThat(shortShares.summary()).contains("short shares");
    }

    private static Shape family(String name, String family, PositionPackage.Leg... legs) {
        return new Shape(name, family, null, List.of(legs));
    }

    private static Shape template(String name, String template, PositionPackage.Leg... legs) {
        return new Shape(name, null, template, List.of(legs));
    }

    private static Shape custom(String name, String template, PositionPackage.Leg... legs) {
        return new Shape(name, null, template, List.of(legs));
    }

    private static PositionPackage pkg(List<PositionPackage.Leg> legs) {
        return new PositionPackage("shape", PositionDomain.PackageSource.HYPOTHETICAL_DRAFT,
                PositionDomain.ExecutionLane.NONE, "XYZ", 1, null,
                OffsetDateTime.parse("2026-07-15T12:00:00Z"), legs);
    }

    private static PositionPackage pkg(PositionPackage.Leg... legs) {
        return pkg(List.of(legs));
    }

    private static PositionPackage.Leg call(int index, String action, String strike, LocalDate expiry, long quantity) {
        return option(index, action, "CALL", strike, expiry, quantity);
    }

    private static PositionPackage.Leg put(int index, String action, String strike, LocalDate expiry, long quantity) {
        return option(index, action, "PUT", strike, expiry, quantity);
    }

    private static PositionPackage.Leg option(int index, String action, String type, String strike,
                                              LocalDate expiry, long quantity) {
        return new PositionPackage.Leg(index, action, "OPTION", "XYZ", type, new BigDecimal(strike), expiry,
                quantity, 100, null, PositionDomain.PriceAuthority.MODELED);
    }

    private static PositionPackage.Leg stock(int index, String action, long quantity) {
        return new PositionPackage.Leg(index, action, "STOCK", "XYZ", null, null, null,
                quantity, 1, null, PositionDomain.PriceAuthority.MODELED);
    }

    private record Shape(String name, String family, String template, List<PositionPackage.Leg> legs) {}
}
