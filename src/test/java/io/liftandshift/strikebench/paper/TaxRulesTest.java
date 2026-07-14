package io.liftandshift.strikebench.paper;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaxRulesTest {
    @Test
    void onlyTheReviewedTaxYearEnablesAutomatedLegalTransformations() {
        assertThat(TaxRules.forYear(2025).status()).isEqualTo(TaxRules.Status.REVIEWED);
        assertThat(TaxRules.forYear(2025).userRateScenarioAvailable()).isTrue();
        assertThat(TaxRules.forYear(2025).automatedYearEndMarkAvailable()).isTrue();

        assertThat(TaxRules.forYear(2026).status()).isEqualTo(TaxRules.Status.PROVISIONAL);
        assertThat(TaxRules.forYear(2026).userRateScenarioAvailable()).isFalse();
        assertThat(TaxRules.forYear(2024).status()).isEqualTo(TaxRules.Status.UNSUPPORTED);
        assertThat(TaxRules.forYear(2024).automatedYearEndMarkAvailable()).isFalse();

        assertThatThrownBy(() -> TaxRules.requireAutomatedYear(2026, "Year-end mark"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provisional").hasMessageContaining("Recorded accounting facts remain available");
    }

    @Test
    void reviewedRulesCarryTheirPrimarySourcesAndScopeBoundary() {
        var rules = TaxRules.forYear(2025);
        assertThat(rules.id()).isEqualTo("US_FEDERAL_COMMON_CASES_2025");
        assertThat(rules.reviewedThrough()).isEqualTo("2026-07-14");
        assertThat(rules.sources()).extracting(TaxRules.Source::url)
                .contains("https://www.irs.gov/publications/p550",
                        "https://www.irs.gov/forms-pubs/about-form-6781");
        assertThat(rules.scope()).contains("same-account exact-instrument").contains("not a filing calculation");
    }
}
