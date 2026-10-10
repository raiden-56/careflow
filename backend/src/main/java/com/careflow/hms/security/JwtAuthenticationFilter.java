package com.careflow.hms.security;

import com.careflow.hms.auth.UserSessionRepository;
import com.careflow.hms.org.Organization;
import com.careflow.hms.org.OrganizationRepository;
import com.careflow.hms.patient.PatientRepository;
import com.careflow.hms.user.User;
import com.careflow.hms.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Authenticates requests from the access-token cookie (browser) or a Bearer token (API tools).
 * The user's current status is re-checked on every request so disabled/locked accounts lose
 * access immediately, and permissions always reflect the organization's current templates.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTH_SOURCE_ATTR = "careflow.authSource";

    private final JwtService jwtService;
    private final UserRepository users;
    private final PatientRepository patients;
    private final OrganizationRepository organizations;
    private final RolePermissionService rolePermissions;
    private final UserSessionRepository sessions;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = null;
        String source = null;
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            token = header.substring(7);
            source = "bearer";
        } else {
            Optional<String> cookie = AuthCookies.read(request, AuthCookies.ACCESS);
            if (cookie.isPresent()) {
                token = cookie.get();
                source = "cookie";
            }
        }
        if (token != null) {
            String finalSource = source;
            jwtService.parse(token).ifPresent(claims -> authenticate(claims, request, finalSource));
        }
        chain.doFilter(request, response);
    }

    private void authenticate(JwtService.AccessClaims claims, HttpServletRequest request, String source) {
        // A logged-out / revoked session invalidates its access tokens immediately.
        boolean sessionActive = sessions.findById(claims.sessionId())
                .map(s -> s.getRevokedAt() == null)
                .orElse(false);
        if (!sessionActive) {
            return;
        }
        users.findById(claims.userId())
                .filter(u -> u.getStatus() == User.Status.ACTIVE)
                .filter(u -> u.getOrganizationId().equals(claims.organizationId()) && u.getRole() == claims.role())
                .filter(u -> u.getLockedUntil() == null || u.getLockedUntil().isBefore(Instant.now()))
                .ifPresent(u -> {
                    Organization org = organizations.findById(u.getOrganizationId()).orElse(null);
                    if (org == null || org.getStatus() != Organization.Status.ACTIVE) {
                        return;
                    }
                    Set<String> perms = rolePermissions.permissionsFor(u.getOrganizationId(), u.getRole());
                    UUID patientId = u.getRole() == Role.PATIENT
                            ? patients.findByUserId(u.getId()).map(p -> p.getId()).orElse(null)
                            : null;
                    CurrentUser cu = new CurrentUser(u.getId(), u.getOrganizationId(), u.getRole(), u.getFullName(),
                            u.getEmail(), perms, patientId, claims.sessionId(), ZoneId.of(org.getTimezone()));
                    List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + u.getRole().name()));
                    perms.forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
                    var auth = new UsernamePasswordAuthenticationToken(cu, null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(auth);
                    request.setAttribute(AUTH_SOURCE_ATTR, source);
                    MDC.put("userId", u.getId().toString());
                });
    }
}
