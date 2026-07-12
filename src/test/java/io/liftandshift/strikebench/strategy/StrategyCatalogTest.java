package io.liftandshift.strikebench.strategy;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyCatalogTest {
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

        assertThat(StrategyCatalog.templates()).hasSize(30).allSatisfy(template -> {
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
                .hasSize(18)
                .allSatisfy(entry -> assertThat(entry.blockedByDefault()).isFalse());
        assertThat(StrategyCatalog.families().stream().filter(StrategyCatalog.FamilyEntry::backtestEnabled))
                .hasSize(14)
                .allSatisfy(entry -> {
                    assertThat(entry.blockedByDefault()).isFalse();
                    assertThat(entry.multiExpiration()).isFalse();
                });
    }
}
