package com.careflow.hms.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * CSRF defence for cookie-authenticated browsers: state-changing requests must carry the custom
 * header {@code X-CareFlow-Client}. Cross-site forms cannot set custom headers and the API does not
 * allow cross-origin requests, so a forged request is rejected. Combined with SameSite=Strict cookies.
 */
@Component
@RequiredArgsConstructor
public class CsrfHeaderFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-CareFlow-Client";
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean usesCookies = request.getHeader("Authorization") == null
                && (AuthCookies.read(request, AuthCookies.ACCESS).isPresent()
                || AuthCookies.read(request, AuthCookies.REFRESH).isPresent());
        if (!SAFE.contains(request.getMethod()) && usesCookies && request.getHeader(HEADER) == null) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("timestamp", Instant.now().toString());
            body.put("status", 403);
            body.put("code", "CSRF_REJECTED");
            body.put("message", "Missing client header");
            body.put("path", request.getRequestURI());
            objectMapper.writeValue(response.getOutputStream(), body);
            return;
        }
        chain.doFilter(request, response);
    }
}
