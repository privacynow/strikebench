package io.liftandshift.strikebench;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentationContractTest {

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

        assertThat(guide).contains("├── auth").contains("scripts/release-matrix.mjs");
        assertThat(guide).doesNotContain("Current counts are");
        assertThat(guide).doesNotContainPattern("Current release evidence is \\*\\*\\d+");
        assertThat(ci).contains("target/dom-fixture.tap", "target/dom-seeded.tap",
                "target/dom-audit.tap", "target/dom-auth.tap", "scripts/release-matrix.mjs");
        assertThat(auth).contains("verified OIDC sign-in reaches the owner-scoped application")
                .contains("signedIdToken");
    }
}
