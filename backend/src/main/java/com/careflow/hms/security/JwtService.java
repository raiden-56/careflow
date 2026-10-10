package com.careflow.hms.security;

import com.careflow.hms.config.CareFlowProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/** Issues and validates short-lived HS256 access tokens. */
@Service
public class JwtService {

    private static final String ISSUER = "careflow-hms";

    private final SecretKey key;
    private final CareFlowProperties props;

    public JwtService(CareFlowProperties props) {
        this.props = props;
        byte[] secret = props.security().jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("careflow.security.jwt-secret must be at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret);
    }

    public record AccessClaims(UUID userId, UUID organizationId, Role role, UUID sessionId) {
    }

    public String issueAccessToken(UUID userId, UUID orgId, Role role, UUID sessionId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .claim("org", orgId.toString())
                .claim("role", role.name())
                .claim("sid", sessionId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(props.security().accessTokenTtl())))
                .signWith(key)
                .compact();
    }

    public Optional<AccessClaims> parse(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).requireIssuer(ISSUER).build()
                    .parseSignedClaims(token).getPayload();
            return Optional.of(new AccessClaims(
                    UUID.fromString(c.getSubject()),
                    UUID.fromString(c.get("org", String.class)),
                    Role.valueOf(c.get("role", String.class)),
                    UUID.fromString(c.get("sid", String.class))));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
