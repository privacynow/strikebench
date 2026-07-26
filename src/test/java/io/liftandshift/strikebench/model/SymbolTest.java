package io.liftandshift.strikebench.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SymbolTest {

    @Test
    void oneIdentityAcceptsEverySupportedTickerSpelling() {
        assertThat(Symbol.normalize(" brk.b ")).isEqualTo("BRK.B");
        assertThat(Symbol.normalize("^vix")).isEqualTo("^VIX");
        assertThat(Symbol.normalize("brk/b")).isEqualTo("BRK/B");
        assertThat(Symbol.normalize("gc=f")).isEqualTo("GC=F");
        assertThat(Symbol.of("BRK.B").providerAlias("yahoo")).isEqualTo("BRK-B");
        assertThat(Symbol.of("BRK.B").value()).isEqualTo("BRK.B");
    }

    @Test
    void normalizationAndListsPreserveOneCanonicalIdentity() {
        assertThat(Symbol.normalize("intc")).isEqualTo("INTC");
        assertThat(Symbol.list(List.of(" aapl ", "AAPL", "", "msft")))
                .containsExactly("AAPL", "MSFT");
    }

    @Test
    void missingAndMalformedSymbolsStayUnavailable() {
        assertThat(Symbol.optional(" ")).isNull();
        assertThatThrownBy(() -> Symbol.of("$$$")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Symbol.of("ABCDEFGHIJKLMNOPQRSTUVWXY"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
