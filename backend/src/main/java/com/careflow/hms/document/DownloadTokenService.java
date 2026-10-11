package com.careflow.hms.document;

import com.careflow.hms.config.CareFlowProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Short-lived, HMAC-SHA256 signed document download links. The token carries the document id, the
 * organization, the requesting user and an expiry; it is verified in constant time and never stored.
 */
@Service
public class DownloadTokenService {

    private static final String VERSION = "d1";
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    private final byte[] key;
    private final Duration ttl;

    public DownloadTokenService(CareFlowProperties props) {
        this.key = ("document-download:" + props.security().jwtSecret()).getBytes(StandardCharsets.UTF_8);
        this.ttl = props.storage().downloadTokenTtl() == null ? Duration.ofMinutes(5) : props.storage().downloadTokenTtl();
    }

    public record Claims(UUID documentId, UUID organizationId, UUID userId, boolean patientScope, Instant expiresAt) {
    }

    public record Issued(String token, Instant expiresAt) {
    }

    public sealed interface Verification permits Valid, Invalid, Expired {
    }

    public record Valid(Claims claims) implements Verification {
    }

    public record Invalid() implements Verification {
    }

    public record Expired(Claims claims) implements Verification {
    }

    public Duration ttl() {
        return ttl;
    }

    public Issued issue(UUID documentId, UUID organizationId, UUID userId, boolean patientScope) {
        return issue(documentId, organizationId, userId, patientScope, ttl);
    }

    /** Visible for tests (e.g. issuing an already-expired token). */
    public Issued issue(UUID documentId, UUID organizationId, UUID userId, boolean patientScope, Duration validFor) {
        Instant exp = Instant.now().plus(validFor);
        String payload = String.join("|", VERSION, documentId.toString(), organizationId.toString(), userId.toString(),
                patientScope ? "P" : "S", String.valueOf(exp.getEpochSecond()));
        String p = ENC.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return new Issued(p + "." + ENC.encodeToString(sign(p)), exp);
    }

    public Verification verify(String token) {
        if (token == null || token.length() > 1024) {
            return new Invalid();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.')) {
            return new Invalid();
        }
        String p = token.substring(0, dot);
        byte[] sig;
        try {
            sig = DEC.decode(token.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return new Invalid();
        }
        if (!MessageDigest.isEqual(sign(p), sig)) {
            return new Invalid();
        }
        Optional<Claims> claims = parse(p);
        if (claims.isEmpty()) {
            return new Invalid();
        }
        if (!claims.get().expiresAt().isAfter(Instant.now())) {
            return new Expired(claims.get());
        }
        return new Valid(claims.get());
    }

    private Optional<Claims> parse(String p) {
        try {
            String[] parts = new String(DEC.decode(p), StandardCharsets.UTF_8).split("\\|");
            if (parts.length != 6 || !VERSION.equals(parts[0])) {
                return Optional.empty();
            }
            return Optional.of(new Claims(UUID.fromString(parts[1]), UUID.fromString(parts[2]), UUID.fromString(parts[3]),
                    "P".equals(parts[4]), Instant.ofEpochSecond(Long.parseLong(parts[5]))));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }
}
