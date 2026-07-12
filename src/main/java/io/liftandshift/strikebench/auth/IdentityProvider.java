package io.liftandshift.strikebench.auth;

import java.net.URI;

/**
 * The OIDC identity provider seam. The production implementation ({@link GoogleOidcProvider})
 * delegates the security-critical parts — token exchange and ID-token/JWKS validation — to Nimbus.
 * Tests supply a stub so the auth orchestration can be verified without a live Google round-trip.
 */
public interface IdentityProvider {

    /** The provider's authorization URL to redirect the user to (carries state + nonce). */
    URI authUrl(String state, String nonce);

    /**
     * Exchanges the authorization code for tokens and returns the VERIFIED identity. The ID token's
     * signature, issuer, audience, expiry, and nonce are all validated; a failure throws.
     */
    VerifiedIdentity exchange(String code, String expectedNonce) throws Exception;
}
