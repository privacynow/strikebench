package io.liftandshift.strikebench.api;

import io.liftandshift.strikebench.model.DataAge;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.Quote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §5.5 — ONE batch-quote authority. The displayed price, its day change and the basis it is quoted
 * on come from {@link Quote} and are published through one {@link ApiResponses.QuoteView}. A symbol
 * the market cannot price produces a row that says why, never a substituted 0 and never a silent
 * previous close.
 */
class QuoteViewAuthorityTest {

    private static Quote quote(String last, String bid, String ask, String prevClose) {
        return new Quote("AAPL", "Apple Inc.",
                last == null ? null : new BigDecimal(last),
                bid == null ? null : new BigDecimal(bid),
                ask == null ? null : new BigDecimal(ask),
                prevClose == null ? null : new BigDecimal(prevClose),
                null, null, null, true, 1_752_000_000_000L, "cboe", Freshness.DELAYED);
    }

    @Test
    void aTwoSidedBookIsQuotedOnTheMidAndSaysSo() {
        ApiResponses.QuoteView view = ApiResponses.QuoteView.of(quote("199.00", "200.00", "202.00", "200.00"), false);

        assertThat(view.priced()).isTrue();
        assertThat(view.markBasis()).isEqualTo("MID");
        assertThat(view.displayPrice()).isEqualByComparingTo("201.00");
        // The change is measured against the SAME price the row displays.
        assertThat(view.displayChangePct()).isEqualTo(0.5);
        assertThat(view.priceIsPreviousClose()).isFalse();
        assertThat(view.quoteUnavailableReason()).isNull();
        assertThat(view.freshness()).isEqualTo("DELAYED");
        assertThat(view.source()).isEqualTo("cboe");
        assertThat(view.asOf()).isEqualTo(1_752_000_000_000L);
    }

    @Test
    void aLastTradeWithoutABookIsQuotedOnLast() {
        ApiResponses.QuoteView view = ApiResponses.QuoteView.of(quote("210.00", null, null, "200.00"), false);

        assertThat(view.markBasis()).isEqualTo("LAST");
        assertThat(view.displayPrice()).isEqualByComparingTo("210.00");
        assertThat(view.displayChangePct()).isEqualTo(5.0);
        assertThat(view.priceIsPreviousClose()).isFalse();
    }

    @Test
    void aPreviousCloseFallbackIsDeclaredAndDemotesTheRowFreshness() {
        ApiResponses.QuoteView view = ApiResponses.QuoteView.of(quote(null, null, null, "200.00"), false);

        assertThat(view.markBasis()).isEqualTo("PREVIOUS_CLOSE");
        assertThat(view.priceIsPreviousClose()).isTrue();
        assertThat(view.displayPrice()).isEqualByComparingTo("200.00");
        assertThat(view.displayChangePct()).isEqualTo(0.0);
        // The row's freshness is the freshness of the value it actually shows, not the feed's.
        assertThat(view.freshness()).isEqualTo("EOD");
        assertThat(view.evidence().age()).isEqualTo(DataAge.EOD);
        assertThat(view.evidence().source()).contains("previous-close fallback");
    }

    @Test
    void aQuoteWithNoUsablePriceIsUnavailableWithAReasonInsteadOfZero() {
        // A feed that returns a placeholder previous close of 0 used to reach mark() and be
        // rendered as $0.00 — a fabricated price with a real symbol attached to it.
        Quote empty = quote(null, null, null, "0.00");

        assertThat(empty.markBasis()).isEqualTo(Quote.MarkBasis.UNAVAILABLE);
        assertThat(empty.mark()).isNull();
        assertThat(empty.markChangePct()).isNull();

        ApiResponses.QuoteView view = ApiResponses.QuoteView.of(empty, false);
        assertThat(view.priced()).isFalse();
        assertThat(view.displayPrice()).isNull();
        assertThat(view.displayChangePct()).isNull();
        assertThat(view.markBasis()).isEqualTo("UNAVAILABLE");
        assertThat(view.quoteUnavailableReason())
                .contains("AAPL")
                .contains("no last trade");
        // Nor may the row publish the junk inputs a surface could re-derive a price from.
        assertThat(view.last()).isNull();
        assertThat(view.prevClose()).isNull();
    }

    @Test
    void aCrossedBookIsNotAMidAndFallsBackWithItsBasisNamed() {
        ApiResponses.QuoteView view = ApiResponses.QuoteView.of(quote(null, "205.00", "199.00", "200.00"), false);

        assertThat(view.markBasis()).isEqualTo("PREVIOUS_CLOSE");
        assertThat(view.displayPrice()).isEqualByComparingTo("200.00");
    }

    @Test
    void aRowCannotCarryBothAPriceAndAnExcuseOrNeither() {
        assertThatThrownBy(() -> new ApiResponses.QuoteView("AAPL", null, null, null, "LAST",
                false, true, null, null, null, null, null, true, "DELAYED", "cboe", null, 1L, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("display price");

        assertThatThrownBy(() -> new ApiResponses.QuoteView("AAPL", null, new BigDecimal("10"), null,
                "LAST", false, true, "unavailable because reasons", null, null, null, null, true,
                "DELAYED", "cboe", null, 1L, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");

        assertThatThrownBy(() -> ApiResponses.QuoteView.unavailable("AAPL", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs a reason");
    }
}
