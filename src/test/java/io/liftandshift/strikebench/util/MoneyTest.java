package io.liftandshift.strikebench.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyTest {

    @Test
    void bigDecimalToCents() {
        assertThat(Money.toCents(new BigDecimal("1.00"))).isEqualTo(100);
        assertThat(Money.toCents(new BigDecimal("1.005"))).isEqualTo(101);   // HALF_UP
        assertThat(Money.toCents(new BigDecimal("-2.345"))).isEqualTo(-235); // HALF_UP rounds away from zero
    }

    @Test
    void perSharePriceTimesShares() {
        assertThat(Money.centsFromPrice(new BigDecimal("1.2345"), 100)).isEqualTo(12345);
        assertThat(Money.centsFromPrice(new BigDecimal("2.50"), 100)).isEqualTo(25000);
        assertThat(Money.centsFromPrice(new BigDecimal("0.005"), 100)).isEqualTo(50);
    }

    @Test
    void centsToBigDecimal() {
        assertThat(Money.priceFromCents(12345)).isEqualByComparingTo(new BigDecimal("123.45"));
        assertThat(Money.priceFromCents(-50)).isEqualByComparingTo(new BigDecimal("-0.50"));
    }

    @Test
    void formatting() {
        assertThat(Money.fmt(123456)).isEqualTo("$1,234.56");
        assertThat(Money.fmt(-1230)).isEqualTo("-$12.30");
        assertThat(Money.fmt(0)).isEqualTo("$0.00");
    }
}
