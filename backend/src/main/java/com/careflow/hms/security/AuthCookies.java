package com.careflow.hms.security;

import com.careflow.hms.config.CareFlowProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * httpOnly, SameSite=Strict cookies for browser sessions. The access token is short-lived;
 * the refresh token is scoped to the auth endpoints only.
 */
@Component
@RequiredArgsConstructor
public class AuthCookies {

    public static final String ACCESS = "cf_at";
    public static final String REFRESH = "cf_rt";
    private static final String REFRESH_PATH = "/api/auth";

    private final CareFlowProperties props;

    public void write(HttpServletResponse res, String accessToken, String refreshToken) {
        res.addHeader(HttpHeaders.SET_COOKIE, cookie(ACCESS, accessToken, "/", props.security().accessTokenTtl()).toString());
        res.addHeader(HttpHeaders.SET_COOKIE, cookie(REFRESH, refreshToken, REFRESH_PATH, props.security().refreshTokenTtl()).toString());
    }

    public void clear(HttpServletResponse res) {
        res.addHeader(HttpHeaders.SET_COOKIE, cookie(ACCESS, "", "/", Duration.ZERO).toString());
        res.addHeader(HttpHeaders.SET_COOKIE, cookie(REFRESH, "", REFRESH_PATH, Duration.ZERO).toString());
    }

    public static Optional<String> read(HttpServletRequest req, String name) {
        if (req.getCookies() == null) {
            return Optional.empty();
        }
        for (Cookie c : req.getCookies()) {
            if (name.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                return Optional.of(c.getValue());
            }
        }
        return Optional.empty();
    }

    private ResponseCookie cookie(String name, String value, String path, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(props.security().cookieSecure())
                .sameSite("Strict")
                .path(path)
                .maxAge(maxAge)
                .build();
    }
}
