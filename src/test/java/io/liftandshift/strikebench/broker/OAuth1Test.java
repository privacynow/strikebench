package io.liftandshift.strikebench.broker;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OAuth1Test {

    /** The canonical OAuth 1.0 HMAC-SHA1 vector (photos.example.net, RFC 5849 / OAuth Core 1.0 A.5.2). */
    @Test
    void canonicalSignatureVector() {
        OAuth1 oauth = new OAuth1(() -> "kllo9940pd9333jh", () -> 1191242096L);
        OAuth1.Creds creds = new OAuth1.Creds("dpf43f3p2l4k3l03", "kd94hf93k423kf44", "nnch734d00sl2jdk", "pfkkdhi9sl3r4s00");

        String header = oauth.authorizationHeader("GET",
                "http://photos.example.net/photos?file=vacation.jpg&size=original", null, creds);

        assertThat(header).startsWith("OAuth ");
        assertThat(header).contains("oauth_consumer_key=\"dpf43f3p2l4k3l03\"");
        assertThat(header).contains("oauth_signature_method=\"HMAC-SHA1\"");
        assertThat(header).contains("oauth_token=\"nnch734d00sl2jdk\"");
        // Expected signature: tR3+Ty81lMeYAr/Fid0kMTYa/WM= (percent-encoded in the header)
        assertThat(header).contains("oauth_signature=\"tR3%2BTy81lMeYAr%2FFid0kMTYa%2FWM%3D\"");
    }

    @Test
    void baseStringSortsAndEncodesQueryParams() {
        String base = OAuth1.baseString("get", "https://api.example.com/v1/x.json?b=2&a=1",
                Map.of("oauth_nonce", "n"));
        assertThat(base).startsWith("GET&https%3A%2F%2Fapi.example.com%2Fv1%2Fx.json&");
        assertThat(base).contains("a%3D1%26b%3D2%26oauth_nonce%3Dn");
    }

    @Test
    void percentEncodingIsRfc3986() {
        assertThat(OAuth1.enc("a b+c~d*e/f")).isEqualTo("a%20b%2Bc~d%2Ae%2Ff");
    }

    @Test
    void parsesFormResponses() {
        Map<String, String> form = OAuth1.parseForm("oauth_token=abc%2F123&oauth_token_secret=xyz");
        assertThat(form).containsEntry("oauth_token", "abc/123").containsEntry("oauth_token_secret", "xyz");
    }
}
