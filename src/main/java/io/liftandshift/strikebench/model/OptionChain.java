package io.liftandshift.strikebench.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Full chain for one underlying+expiration. */
public record OptionChain(
        String underlying,
        LocalDate expiration,
        BigDecimal underlyingPrice,
        List<OptionQuote> calls,
        List<OptionQuote> puts,
        long asOfEpochMs,
        String source,
        Freshness freshness
) {
    public Optional<OptionQuote> find(OptionType type, BigDecimal strike) {
        List<OptionQuote> side = type == OptionType.CALL ? calls : puts;
        return side.stream().filter(q -> q.strike().compareTo(strike) == 0).findFirst();
    }

    public List<BigDecimal> strikes() {
        return calls.stream().map(OptionQuote::strike).distinct().sorted().toList();
    }

    public boolean isEmpty() { return calls.isEmpty() && puts.isEmpty(); }
}
