package io.liftandshift.strikebench.paper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrokerStatementParserTest {
    private final BrokerStatementParser parser = new BrokerStatementParser();

    @Test
    void versionedFixturesCoverEverySupportedOneWayParser() throws Exception {
        List<Fixture> fixtures = List.of(
                new Fixture("ETRADE", "etrade-v1.csv", BrokerStatementParser.GroupKind.EXACT_FILLS),
                new Fixture("VANGUARD", "vanguard-v1.csv", BrokerStatementParser.GroupKind.PACKAGE_NET_PENDING),
                new Fixture("THINKORSWIM", "thinkorswim-v1.csv", BrokerStatementParser.GroupKind.EXACT_FILLS),
                new Fixture("FIDELITY", "fidelity-v1.csv", BrokerStatementParser.GroupKind.EXACT_FILLS),
                new Fixture("SCHWAB", "schwab-v1.csv", BrokerStatementParser.GroupKind.EXACT_FILLS),
                new Fixture("ROBINHOOD", "robinhood-v1.csv", BrokerStatementParser.GroupKind.EXACT_FILLS));

        for (Fixture fixture : fixtures) {
            var parsed = parser.parse(new BrokerStatementParser.Request(
                    fixture.source(), null, resource(fixture.file())));
            assertThat(parsed.parserVersion()).isEqualTo(BrokerStatementParser.VERSION);
            assertThat(parsed.groups()).singleElement().extracting(BrokerStatementParser.Group::kind)
                    .isEqualTo(fixture.kind());
            assertThat(parsed.quarantine()).isEmpty();
            assertThat(parsed.previewFingerprint()).startsWith("preview-");
            assertThat(parsed.groups().getFirst().accountFingerprint()).startsWith("acct-");
            assertThat(parsed.groups().getFirst().accountFingerprint()).doesNotContain("7788", "1144", "3322");
        }
    }

    @Test
    void sanitizedJourneyVPreservesPackagesAccountsAndTheAugustClusterWithoutInventingFacts() throws Exception {
        var parsed = parser.parse(new BrokerStatementParser.Request(
                "VANGUARD", null, resource("vanguard-journey-v-v1.csv")));

        assertThat(parsed.quarantine()).isEmpty();
        assertThat(parsed.groups()).hasSize(5);
        assertThat(parsed.groups()).extracting(BrokerStatementParser.Group::externalRef)
                .containsExactlyInAnyOrder("VG-AMD-SPREAD", "VG-SHARED", "VG-SHARED", "VG-INTC-IN", "VG-INTC-OUT");
        assertThat(parsed.groups().stream().filter(group -> "VG-SHARED".equals(group.externalRef()))
                .map(BrokerStatementParser.Group::accountFingerprint).distinct().count()).isEqualTo(2);
        assertThat(parsed.groups().stream().filter(group -> "VG-SHARED".equals(group.externalRef()))
                .map(BrokerStatementParser.Group::groupKey).distinct().count()).isEqualTo(2);

        var amd = parsed.groups().stream().filter(group -> "VG-AMD-SPREAD".equals(group.externalRef()))
                .findFirst().orElseThrow();
        assertThat(amd.kind()).isEqualTo(BrokerStatementParser.GroupKind.PACKAGE_NET_PENDING);
        assertThat(amd.legs()).hasSize(2).allMatch(leg -> leg.reportedPrice() == null);
        assertThat(amd.packageNetCents()).isEqualTo(24_870L);

        Set<String> symbols = parsed.groups().stream().flatMap(group -> group.legs().stream())
                .map(BrokerStatementParser.Leg::symbol).collect(Collectors.toSet());
        assertThat(symbols).containsExactlyInAnyOrder("AMD", "NVDA", "JEPQ", "INTC");
        assertThat(parsed.groups().stream().flatMap(group -> group.legs().stream())
                .filter(leg -> "OPTION".equals(leg.instrumentType())))
                .allMatch(leg -> java.time.LocalDate.parse("2026-08-07").equals(leg.expiration()));
        assertThat(parsed.toString()).doesNotContain("Roth IRA ending 1100", "Taxable ending 2200");
    }

    @Test
    void accountFingerprintDisambiguatesDuplicateRefsWithoutPersistingRawAccount() {
        String header = "order_id,account,date,symbol,type,action,position,quantity,price,net_amount,leg\n";
        var one = parser.parse(new BrokerStatementParser.Request("SCHWAB", null,
                header + "same-ref,IRA 1111,2026-07-01,MU,stock,buy,open,1,100,-100,0\n"));
        var two = parser.parse(new BrokerStatementParser.Request("SCHWAB", null,
                header + "same-ref,IRA 2222,2026-07-01,MU,stock,buy,open,1,100,-100,0\n"));

        assertThat(one.groups().getFirst().externalRef()).isEqualTo(two.groups().getFirst().externalRef());
        assertThat(one.groups().getFirst().accountFingerprint())
                .isNotEqualTo(two.groups().getFirst().accountFingerprint());
        assertThat(one.toString()).doesNotContain("IRA 1111");
    }

    @Test
    void accountFingerprintIsSecretScopedInsteadOfAnUnsaltedLastFourHash() {
        String text = "order_id,account,date,symbol,type,action,position,quantity,price,net_amount,leg\n"
                + "same-ref,IRA 1111,2026-07-01,MU,stock,buy,open,1,100,-100,0\n";
        var ownerOne = parser.parse(new BrokerStatementParser.Request(
                "SCHWAB", null, text, "owner-one-secret"));
        var ownerTwo = parser.parse(new BrokerStatementParser.Request(
                "SCHWAB", null, text, "owner-two-secret"));

        assertThat(ownerOne.groups().getFirst().accountFingerprint())
                .isNotEqualTo(ownerTwo.groups().getFirst().accountFingerprint());
        assertThat(ownerOne.previewFingerprint()).isNotEqualTo(ownerTwo.previewFingerprint());
        assertThat(ownerOne.toString()).doesNotContain("IRA 1111", "1111");
    }

    @Test
    void mangledGroupsQuarantineEveryMemberAndValidGroupsSurvive() {
        String text = "order_id,account,date,symbol,type,action,position,option_type,strike,expiration,quantity,multiplier,price,net_amount,fees,leg\n"
                + "bad,IRA 1,2026-07-01,MU,option,sell,open,put,98,2026-08-07,1,100,nope,250,0,0\n"
                + "bad,IRA 1,2026-07-01,MU,option,buy,open,put,95,2026-08-07,1,100,1,250,0,1\n"
                + "good,IRA 1,2026-07-02,AAPL,stock,buy,open,,,,1,1,100,-100,0,0\n";

        var out = parser.parse(new BrokerStatementParser.Request("ETRADE", null, text));

        assertThat(out.groups()).extracting(BrokerStatementParser.Group::externalRef).containsExactly("good");
        assertThat(out.quarantine()).hasSize(2).allMatch(row -> "bad".equals(row.externalRef()));
        assertThat(out.quarantine()).allMatch(row -> row.reason().contains("price"));
    }

    @Test
    void packageNetMismatchIsQuarantinedInsteadOfRoundedOrAllocated() {
        String text = "order_id,account,date,symbol,type,action,position,quantity,multiplier,price,net_amount,fees,leg\n"
                + "bad-net,IRA 1,2026-07-01,MU,stock,buy,open,1,1,100,-99.99,0,0\n";
        var out = parser.parse(new BrokerStatementParser.Request("ETRADE", null, text));
        assertThat(out.groups()).isEmpty();
        assertThat(out.quarantine()).singleElement().satisfies(row ->
                assertThat(row.reason()).contains("exact fills produce -10000 cents"));
    }

    @Test
    void missingBothLegPriceAndPackageNetCannotBecomeARecord() {
        String text = "order_id,account,date,symbol,type,action,position,quantity,leg\n"
                + "unknown,IRA 1,2026-07-01,MU,stock,buy,open,1,0\n";
        var out = parser.parse(new BrokerStatementParser.Request("ETRADE", null, text));
        assertThat(out.groups()).isEmpty();
        assertThat(out.quarantine()).singleElement().satisfies(row ->
                assertThat(row.reason()).contains("neither may be invented"));
    }

    @Test
    void packageFactsAndAccountMayAppearOnALaterLegWithoutBeingInvented() {
        String text = "order_id,account,date,symbol,type,action,position,option_type,strike,expiration,quantity,multiplier,price,net_amount,fees,leg\n"
                + "late-total,,2026-07-01,MU,option,sell,open,put,98,2026-08-07,1,100,2.50,,,0\n"
                + "late-total,IRA 7788,2026-07-01,MU,option,buy,open,put,95,2026-08-07,1,100,1.00,149.00,1.00,1\n";

        var out = parser.parse(new BrokerStatementParser.Request("ETRADE", null, text));

        assertThat(out.quarantine()).isEmpty();
        assertThat(out.groups()).singleElement().satisfies(group -> {
            assertThat(group.kind()).isEqualTo(BrokerStatementParser.GroupKind.EXACT_FILLS);
            assertThat(group.packageNetCents()).isEqualTo(14_900L);
            assertThat(group.feesCents()).isEqualTo(100L);
            assertThat(group.accountFingerprint()).startsWith("acct-");
        });
    }

    @Test
    void unsupportedOrUnclosedInputFailsClearly() {
        assertThatThrownBy(() -> parser.parse(new BrokerStatementParser.Request("UNKNOWN", "1", "x")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unsupported broker");
        assertThatThrownBy(() -> parser.parse(new BrokerStatementParser.Request("ETRADE", "1", "\"broken")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unclosed quoted field");
    }

    private static String resource(String name) throws IOException {
        try (var stream = BrokerStatementParserTest.class.getResourceAsStream("/broker-import/" + name)) {
            if (stream == null) throw new IOException("missing fixture " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record Fixture(String source, String file, BrokerStatementParser.GroupKind kind) {}
}
