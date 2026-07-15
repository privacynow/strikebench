package io.liftandshift.strikebench;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentationContractTest {

    private static final Pattern TRANSCRIBED_TEST_COUNT = Pattern.compile(
            "(?i)\\b\\d[\\d,]*\\s+(?:JUnit|DOM)\\b");

    @Test void everyAppConfigKeyIsNamedInTheConfigurationTable() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/config/AppConfig.java"));
        var matcher = Pattern.compile("(?:get|getInt|getLong|getBool|emailList)\\(\"([A-Z0-9_]+)\"")
                .matcher(source);
        Set<String> keys = new LinkedHashSet<>();
        while (matcher.find()) keys.add(matcher.group(1));

        String guide = Files.readString(Path.of("DEVELOPER.md"));
        String configuration = guide.substring(guide.indexOf("## Configuration"), guide.indexOf("## Deployment"));
        Set<String> missing = new LinkedHashSet<>();
        for (String key : keys) if (!configuration.contains("`" + key + "`")) missing.add(key);

        assertThat(keys).hasSizeGreaterThan(60);
        assertThat(missing).as("AppConfig keys missing from DEVELOPER.md Configuration").isEmpty();
    }

    @Test void architectureAndReleaseEvidenceCannotDriftBackToProse() throws Exception {
        String guide = Files.readString(Path.of("DEVELOPER.md"));
        String ci = Files.readString(Path.of(".github/workflows/ci.yml"));
        String auth = Files.readString(Path.of("dom-tests/dom-auth.test.js"));
        String evidenceSections = section(guide, "## Current product shape", "## Build & run")
                + section(guide, "## Tests", "## Configuration");

        assertThat(guide).contains("├── auth").contains("scripts/release-matrix.mjs");
        assertThat(TRANSCRIBED_TEST_COUNT.matcher("715 JUnit green").find()).isTrue();
        assertThat(TRANSCRIBED_TEST_COUNT.matcher("42 DOM checks").find()).isTrue();
        assertThat(TRANSCRIBED_TEST_COUNT.matcher(evidenceSections).find())
                .as("release evidence must come from scripts/release-matrix.mjs, never prose counts")
                .isFalse();
        assertThat(ci).contains("target/dom-fixture.tap", "target/dom-seeded.tap",
                "target/dom-audit.tap", "target/dom-auth.tap", "scripts/release-matrix.mjs");
        assertThat(auth).contains("verified OIDC sign-in reaches the owner-scoped application")
                .contains("two signed-in identities are isolated and non-admin routes fail with 403")
                .contains("an idle authenticated server session expires and loses protected access")
                .contains("the OIDC allowlist denies a verified but unapproved identity")
                .contains("signedIdToken", "/auth/logout");
    }

    private static String section(String source, String startHeading, String endHeading) {
        int start = source.indexOf(startHeading);
        int end = source.indexOf(endHeading, start + startHeading.length());
        assertThat(start).as("documentation section start: " + startHeading).isGreaterThanOrEqualTo(0);
        assertThat(end).as("documentation section end: " + endHeading).isGreaterThan(start);
        return source.substring(start, end);
    }
}
