package io.liftandshift.strikebench.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.JWT;
import com.nimbusds.oauth2.sdk.AuthorizationCode;
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant;
import com.nimbusds.oauth2.sdk.AuthorizationGrant;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.TokenRequest;
import com.nimbusds.oauth2.sdk.auth.ClientAuthentication;
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic;
import com.nimbusds.oauth2.sdk.auth.Secret;
import com.nimbusds.oauth2.sdk.http.HTTPResponse;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.Issuer;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.openid.connect.sdk.AuthenticationRequest;
import com.nimbusds.openid.connect.sdk.Nonce;
import com.nimbusds.openid.connect.sdk.OIDCTokenResponse;
import com.nimbusds.openid.connect.sdk.OIDCTokenResponseParser;
import com.nimbusds.openid.connect.sdk.claims.IDTokenClaimsSet;
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata;
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator;

import java.net.URI;

/**
 * Production OIDC identity provider backed by Nimbus. Provider metadata (endpoints + JWKS) is
 * discovered from the issuer's well-known document and cached; the ID token's signature is
 * verified against the provider's JWKS and its issuer/audience/expiry/nonce checked by Nimbus's
 * {@link IDTokenValidator}. No cryptography is implemented here.
 */
public final class GoogleOidcProvider implements IdentityProvider {

    private final Issuer issuer;
    private final ClientID clientID;
    private final Secret clientSecret;
    private final URI callback;

    private volatile OIDCProviderMetadata metadata;
    private volatile IDTokenValidator validator;

    public GoogleOidcProvider(String issuer, String clientId, String clientSecret, String callbackUrl) {
        this.issuer = new Issuer(issuer);
        this.clientID = new ClientID(clientId);
        this.clientSecret = new Secret(clientSecret);
        this.callback = URI.create(callbackUrl);
    }

    private OIDCProviderMetadata metadata() {
        OIDCProviderMetadata m = metadata;
        if (m == null) {
            synchronized (this) {
                if ((m = metadata) == null) {
                    try {
                        metadata = m = OIDCProviderMetadata.resolve(issuer);
                    } catch (Exception e) {
                        throw new RuntimeException("Could not resolve OIDC metadata for " + issuer + ": " + e.getMessage(), e);
                    }
                }
            }
        }
        return m;
    }

    private IDTokenValidator validator() {
        IDTokenValidator v = validator;
        if (v == null) {
            synchronized (this) {
                if ((v = validator) == null) {
                    try {
                        validator = v = new IDTokenValidator(issuer, clientID, JWSAlgorithm.RS256,
                                metadata().getJWKSetURI().toURL());
                    } catch (Exception e) {
                        throw new RuntimeException("Could not build ID-token validator: " + e.getMessage(), e);
                    }
                }
            }
        }
        return v;
    }

    @Override
    public URI authUrl(String state, String nonce) {
        return new AuthenticationRequest.Builder(
                new ResponseType(ResponseType.Value.CODE),
                new Scope("openid", "email", "profile"),
                clientID, callback)
                .endpointURI(metadata().getAuthorizationEndpointURI())
                .state(new State(state))
                .nonce(new Nonce(nonce))
                .build()
                .toURI();
    }

    @Override
    public VerifiedIdentity exchange(String code, String expectedNonce) throws Exception {
        AuthorizationGrant grant = new AuthorizationCodeGrant(new AuthorizationCode(code), callback);
        ClientAuthentication clientAuth = new ClientSecretBasic(clientID, clientSecret);
        TokenRequest tokenRequest = new TokenRequest(metadata().getTokenEndpointURI(), clientAuth, grant);

        HTTPResponse httpResponse = tokenRequest.toHTTPRequest().send();
        OIDCTokenResponse response = OIDCTokenResponse.parse(httpResponse).toSuccessResponse();
        if (!response.indicatesSuccess()) {
            throw new IllegalStateException("token endpoint rejected the code");
        }
        JWT idToken = response.getOIDCTokens().getIDToken();
        IDTokenClaimsSet claims = validator().validate(idToken, new Nonce(expectedNonce));

        String subject = claims.getSubject().getValue();
        String email = claims.getStringClaim("email");
        Boolean emailVerified = claims.getBooleanClaim("email_verified");
        String name = claims.getStringClaim("name");
        return new VerifiedIdentity(subject, email, emailVerified != null && emailVerified, name);
    }
}
