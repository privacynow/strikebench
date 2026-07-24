package io.liftandshift.strikebench.paper;

import java.util.List;

/**
 * The deliberately small, reviewed boundary around tax-law assumptions.
 * Accounting facts remain available outside this boundary; automated tax
 * characterization and user-rate scenarios do not.
 */
public final class TaxRules {
    public enum Status { REVIEWED, PROVISIONAL, UNSUPPORTED }

    public record Source(String title, String url) {}

    public record View(String id, int taxYear, Status status, String reviewedThrough,
                       boolean userRateScenarioAvailable,
                       boolean automatedYearEndMarkAvailable,
                       List<Source> sources, String scope) {
        public boolean reviewed() { return status == Status.REVIEWED; }
    }

    public static final String RULESET_ID = "US_FEDERAL_COMMON_CASES_2025";
    public static final int REVIEWED_TAX_YEAR = 2025;
    public static final String REVIEWED_THROUGH = "2026-07-14";

    private static final List<Source> SOURCES = List.of(
            new Source("IRS Publication 550 (2025)", "https://www.irs.gov/publications/p550"),
            new Source("IRS Form 8949 instructions (2025)", "https://www.irs.gov/pub/irs-pdf/i8949.pdf"),
            new Source("IRS Form 6781", "https://www.irs.gov/forms-pubs/about-form-6781"),
            new Source("IRS Form 1099-B instructions (2026)", "https://www.irs.gov/instructions/i1099b")
    );

    private static final String SCOPE = "Common-case federal worksheet rules only: calendar holding period, "
            + "same-account exact-instrument wash-sale candidates, and identified broad-based-index Section 1256 "
            + "60/40 mark-to-market. It is not a filing calculation.";

    private TaxRules() {}

    public static View forYear(int year) {
        Status status = year == REVIEWED_TAX_YEAR ? Status.REVIEWED
                : year == REVIEWED_TAX_YEAR + 1 ? Status.PROVISIONAL : Status.UNSUPPORTED;
        boolean reviewed = status == Status.REVIEWED;
        return new View(RULESET_ID, year, status, REVIEWED_THROUGH, reviewed, reviewed,
                SOURCES, SCOPE);
    }

    public static void requireAutomatedYear(int year, String operation) {
        View rules = forYear(year);
        if (!rules.reviewed()) {
            throw new IllegalStateException(operation + " is unavailable for " + year
                    + " because StrikeBench tax rules are " + rules.status().name().toLowerCase()
                    + ". Recorded accounting facts remain available; update and review the ruleset before "
                    + "applying a legal classification or year-end tax-basis change.");
        }
    }
}
