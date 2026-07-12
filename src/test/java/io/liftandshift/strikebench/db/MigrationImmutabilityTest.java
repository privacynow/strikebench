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
            Map.entry("V19__persist_economic_assessment.sql", "a4099623d9efe12a4a2fe6e6a494894ce1228e5c9ea0999a9e3d0ffc6513e5f0")
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
