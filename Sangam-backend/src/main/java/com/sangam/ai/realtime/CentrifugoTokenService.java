package com.sangam.ai.realtime;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Generates Centrifugo connection tokens for frontend clients.
 *
 * This token is different from your app JWT:
 *   App JWT      → proves identity to Spring Boot
 *   Centrifugo token → proves identity to Centrifugo
 *
 * Both are JWTs, but signed with different secrets and
 * consumed by different servers.
 *
 * Centrifugo requires the token to have:
 *   - sub (subject): the user's unique identifier
 *   - exp (expiration): when the token stops being valid
 *
 * It must be signed with the same secret as
 * CENTRIFUGO_TOKEN_HMAC_SECRET_KEY in your Docker config.
 */
@Service
public class CentrifugoTokenService {

    @Value("${centrifugo.token-secret}")
    private String tokenSecret;

    @Value("${centrifugo.token-expiry-ms}")
    private long tokenExpiryMs;

    /**
     * Generates a Centrifugo connection token for a user.
     *
     * @param userId   the user's UUID as a string — used as the subject.
     *                 Centrifugo uses this to identify who is connected.
     * @return signed JWT string the frontend sends to Centrifugo
     */
    public String generateConnectionToken(String userId) {
        return Jwts.builder()
                .subject(userId)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + tokenExpiryMs))
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * Generates a Centrifugo subscription token for a specific channel.
     *
     * This is needed when channels are private (require permission checks).
     * For example, a user should only subscribe to channels of environments
     * they are members of.
     *
     * Centrifugo calls your backend's proxy endpoint to verify subscription
     * permission — but for client-side subscription tokens, you generate
     * them here instead.
     *
     * @param userId  the user's UUID
     * @param channel the full channel name e.g. "node:abc-123:stream"
     */
    public String generateSubscriptionToken(String userId, String channel) {
        return Jwts.builder()
                .subject(userId)
                // "channel" is a Centrifugo-specific claim
                .claim("channel", channel)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + tokenExpiryMs))
                .signWith(getSigningKey())
                .compact();
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(
                tokenSecret.getBytes(StandardCharsets.UTF_8));
    }
}