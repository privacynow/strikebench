package io.liftandshift.strikebench.db;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Released migrations are append-only. Even a comment or whitespace edit changes Flyway's
 * checksum and prevents an existing installation from starting. Add a new migration for schema
 * changes; when introducing that new file, pin its final digest here in the same commit.
 */
class MigrationImmutabilityTest {
    private static final Path DIR = Path.of("src/main/resources/db/migrations");

    private static final Map<String, String> SHA256 = Map.ofEntries(
            Map.entry("V1__init.sql", "d847475f98d5416f9f722c3d7c1f8b9e49a56579717aa94095bf4ea9203fb740"),
            Map.entry("V2__research_notes.sql", "6418b23415cf86edc851b648df2880d672ba3d6d060aeeb723aa9c3a13b258bf"),
            Map.entry("V3__recommendation_trade_link.sql", "c93ab8518fbe00aa97df6e8369fbc7d928a5ab0b7ac37d49c7d0f87ad0ade777"),
            Map.entry("V4__option_bar_iv_history_index.sql", "441666b1ac1812a0d740afd2625c46e1a05fe223a9b3429cddbda560d9a78494"),
            Map.entry("V5__data_jobs.sql", "756f6c7c86181102e559b162de37b931916de0dd969ae138bccf348555d32c9e"),
            Map.entry("V6__datasets.sql", "90860e5b452c14e205091980006cd6786da0d1ebe8593bdf6a3f2db94cec84cf"),
            Map.entry("V7__market_snapshot.sql", "afbbed1d15a75d767d98f006a6f924234d38b05ebfd9ef550d94fc45be16fc65"),
            Map.entry("V8__workspace.sql", "f89703fdfa5a806beada7d045b1569597d36c436b9993b461f1b349fc17a1254"),
            Map.entry("V9__trade_origin.sql", "dd9cab30d7aac2d2bd5d59d6ea81edf841a0dc197ba45288712169fdb0bba2bc"),
            Map.entry("V10__trade_provenance.sql", "34dc4a493b757ff413340e18a72e8a0e6d3021652f7039aa74ce7b69ce9ec2d1"),
            Map.entry("V11__simulation.sql", "da109b9436116d3a7bc0bb589d1fd2342b554fa2c706d482f8f0048daa881640"),
            Map.entry("V12__simulation_hardening.sql", "a4f836b8ef535f78ae769ee197ba62d46fca4cb32c442f7d6bd4f53f12ab0a4a"),
            Map.entry("V13__sim_anchors.sql", "892a6975fe63fef877bb60faf093c14f82008d15fa6faf9e878489ec84b0895e"),
            Map.entry("V14__strict_data_lanes.sql", "cc336d5ddfe2f555fd8cd9cf13f263095cab01dc29c6fcbc90b83663aab35fa8"),
            Map.entry("V15__legacy_trade_provenance.sql", "de143d7cfdd1a5e8da547197c06054948dea95f55afea9484784e8e78cacf3f6"),
            Map.entry("V16__durable_data_acquisition.sql", "651bfcc80f0aa4126546ba552f095930fcad392921bc676ad6df1357e5cb4db9"),
            Map.entry("V17__underlying_import_basis.sql", "057a2faa9588667d96a807279ecef7faed2f7fc1eb2a8c1dc86e94cdda746ed2"),
            Map.entry("V18__scope_data_quarantine.sql", "6872901ee1546600fd90967f2e9060678feb81f6c16032f5cf338f9030d23d77"),
            Map.entry("V19__persist_economic_assessment.sql", "a4099623d9efe12a4a2fe6e6a494894ce1228e5c9ea0999a9e3d0ffc6513e5f0"),
            Map.entry("V20__decision_outcomes.sql", "93874e5fcc621c5454cc7048c1276f8c1a75ce438100bc6a2f5f6539ed3e2a56"),
            Map.entry("V21__remove_unused_sync_adjustment.sql", "185fd6064d706b0d201757d4b2180544a4933da5223e42eaf72ae0bc0ea5d97e"),
            Map.entry("V22__plan_centered_journey.sql", "d6d1d06ba7fb75b0127d1d2753bcdd586c6617b4984a1040754870b31f99920f"),
            Map.entry("V23__plan_evidence_results.sql", "8e9101583f19cfc78e3aa997a245a7b1f2d175101d83743b4986a40516065516"),
            Map.entry("V24__plan_strategy_results.sql", "9f04083e4d192bc2c7dbb6299e371e800f8194e0a1ef820c3247575dcaad06ef"),
            Map.entry("V25__plan_custom_strategy.sql", "14daafec2451186fbb1530dabe6b9df374ebf0105d21f4464ee33fce2e90a890"),
            Map.entry("V26__plan_scout_identity.sql", "e9b1021e62e50062465d005cb5182372ffc44d4f3dfc23a8ecabdae89902d32c"),
            Map.entry("V27__plan_outcome_results.sql", "2759e71c020e8917b75dec6799ced565ca32b228b4482f8b1c74c3a0de3a94c2"),
            Map.entry("V28__honest_plan_review_categories.sql", "1355da3256fadc65d3b8dcdc26ad7d9dc877e9859b5405dc0ed228083493cb2b"),
            Map.entry("V29__sequence_plan_decisions.sql", "5cd0a8094f5e7f9bcbe7b09bdf28124d48cbcd880187f6e79aab629517479327"),
            Map.entry("V30__plan_rehearsal_sources.sql", "7bd4fb4dc9b09c96a0c7b621666760a91f609ee662cc46b0cb0537c1fc337ffe"),
            Map.entry("V31__plan_candidate_evaluation_receipt.sql", "ce7205dfb5d993432f601bc9ef40f9d8a00bfaac09c7f4badbd5b98b58908636"),
            Map.entry("V32__scope_plan_analysis_artifacts.sql", "1a97c7869f74d72b939be35d25c290245b4782bf3c25533626fd73b57b4a4109"),
            Map.entry("V33__plan_outcome_comparisons.sql", "5a3ff3b82298748a7361f25a4c75ce55629680ba48e1360c99f3a04fa141b9eb"),
            Map.entry("V34__plan_create_idempotency.sql", "582e107feaac339c7ad1c087e814a6646ba3eb07a508ac4c4ac64cbaeefd6b2e"),
            Map.entry("V35__exact_plan_candidate_leg_prices.sql", "bcb023a40799a9449b4de7f968840290f27eadddef64a2d6fd98ac29bcf637fb"),
            Map.entry("V36__honest_plan_void_actions.sql", "417f43faf4dc302be8c5353a9c5db67c6c53e9cd31f2f230b849eefb8ec55799")
    );

    @Test
    void releasedMigrationBytesAreAppendOnly() throws Exception {
        Set<String> files;
        try (var stream = Files.list(DIR)) {
            files = stream.filter(p -> p.getFileName().toString().matches("V\\d+__.+\\.sql"))
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toSet());
        }
        assertThat(files).as("every migration has a pinned digest").containsExactlyInAnyOrderElementsOf(SHA256.keySet());

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (var entry : SHA256.entrySet()) {
            String actual = HexFormat.of().formatHex(digest.digest(Files.readAllBytes(DIR.resolve(entry.getKey()))));
            assertThat(actual).as(entry.getKey() + " is immutable").isEqualTo(entry.getValue());
        }
    }
}
