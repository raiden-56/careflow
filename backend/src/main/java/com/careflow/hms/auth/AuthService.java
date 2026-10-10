package com.careflow.hms.auth;

import com.careflow.hms.audit.AuditEvent;
import com.careflow.hms.audit.AuditService;
import com.careflow.hms.auth.AuthDtos.MeResponse;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.config.CareFlowProperties;
import com.careflow.hms.facility.DepartmentRepository;
import com.careflow.hms.facility.StaffWardAssignmentRepository;
import com.careflow.hms.facility.WardRepository;
import com.careflow.hms.notification.ExternalChannelGateway;
import com.careflow.hms.notification.NotificationDelivery;
import com.careflow.hms.org.Organization;
import com.careflow.hms.org.OrganizationRepository;
import com.careflow.hms.patient.Patient;
import com.careflow.hms.patient.PatientAccessGrantRepository;
import com.careflow.hms.patient.PatientRepository;
import com.careflow.hms.security.CurrentUser;
import com.careflow.hms.security.JwtService;
import com.careflow.hms.security.Role;
import com.careflow.hms.security.RolePermissionService;
import com.careflow.hms.user.StaffProfileRepository;
import com.careflow.hms.user.User;
import com.careflow.hms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** Constant hash used to equalize timing when the account does not exist. */
    private static final String DUMMY_HASH = "$2a$12$C6UzMDM.H6dfI/f/IKcEeO5u8w0x8zHk8e7Qx0Hq0vJm1zj0u3eGa";

    private final UserRepository users;
    private final UserSessionRepository sessions;
    private final PasswordResetTokenRepository resetTokens;
    private final OrganizationRepository organizations;
    private final PatientRepository patients;
    private final PatientAccessGrantRepository grants;
    private final StaffProfileRepository staffProfiles;
    private final DepartmentRepository departments;
    private final StaffWardAssignmentRepository wardAssignments;
    private final WardRepository wards;
    private final RolePermissionService rolePermissions;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService audit;
    private final ExternalChannelGateway mailGateway;
    private final CareFlowProperties props;

    public record IssuedTokens(String accessToken, String refreshToken, UUID userId) {
    }

    @Transactional(noRollbackFor = ApiException.class)
    public IssuedTokens login(String email, String password, String ip, String userAgent) {
        Optional<User> found = users.findByEmailIgnoreCase(email.trim());
        if (found.isEmpty()) {
            passwordEncoder.matches(password, DUMMY_HASH);
            audit.recordFor(null, null, email, null, "LOGIN_FAILED", AuditEvent.Outcome.FAILURE, "Unknown account");
            throw invalidCredentials();
        }
        User user = found.get();
        Instant now = Instant.now();
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
            audit.recordFor(user.getOrganizationId(), user.getId(), user.getFullName(), user.getRole().name(),
                    "LOGIN_BLOCKED", AuditEvent.Outcome.DENIED, "Account temporarily locked");
            throw new ApiException(HttpStatus.LOCKED, "ACCOUNT_LOCKED",
                    "Too many failed sign-in attempts. Try again later or use secure account recovery.");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            int failures = user.getFailedLoginCount() + 1;
            user.setFailedLoginCount(failures);
            if (failures >= props.security().login().maxFailedAttempts()) {
                user.setLockedUntil(now.plus(props.security().login().lockoutDuration()));
                user.setFailedLoginCount(0);
            }
            audit.recordFor(user.getOrganizationId(), user.getId(), user.getFullName(), user.getRole().name(),
                    "LOGIN_FAILED", AuditEvent.Outcome.FAILURE, "Invalid password (" + failures + ")");
            throw invalidCredentials();
        }
        if (user.getStatus() != User.Status.ACTIVE) {
            audit.recordFor(user.getOrganizationId(), user.getId(), user.getFullName(), user.getRole().name(),
                    "LOGIN_BLOCKED", AuditEvent.Outcome.DENIED, "Account " + user.getStatus());
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED",
                    "This account is not active. Contact your hospital administrator.");
        }
        Organization org = organizations.findById(user.getOrganizationId()).orElseThrow();
        if (org.getStatus() != Organization.Status.ACTIVE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ORGANIZATION_SUSPENDED", "This organization is suspended.");
        }
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(now);
        IssuedTokens tokens = newSession(user, ip, userAgent, null);
        audit.recordFor(user.getOrganizationId(), user.getId(), user.getFullName(), user.getRole().name(),
                "LOGIN_SUCCESS", AuditEvent.Outcome.SUCCESS, "Signed in");
        return tokens;
    }

    /** Rotates the refresh token. Reuse of a revoked token revokes every session of the user. */
    @Transactional(noRollbackFor = ApiException.class)
    public IssuedTokens refresh(String refreshToken, String ip, String userAgent) {
        UserSession session = sessions.findByTokenHash(TokenHasher.sha256(refreshToken))
                .orElseThrow(AuthService::sessionExpired);
        Instant now = Instant.now();
        if (session.getRevokedAt() != null) {
            if ("ROTATED".equals(session.getRevokeReason())) {
                sessions.revokeAllForUser(session.getUserId(), now, "REFRESH_REUSE");
                log.warn("Refresh token reuse detected for user {}", session.getUserId());
                audit.recordFor(null, session.getUserId(), null, null, "SESSION_REUSE_DETECTED",
                        AuditEvent.Outcome.DENIED, "Refresh token reuse; all sessions revoked");
            }
            throw sessionExpired();
        }
        if (!session.getExpiresAt().isAfter(now)) {
            throw sessionExpired();
        }
        User user = users.findById(session.getUserId())
                .filter(u -> u.getStatus() == User.Status.ACTIVE)
                .orElseThrow(AuthService::sessionExpired);
        IssuedTokens tokens = newSession(user, ip, userAgent, session);
        return tokens;
    }

    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null) {
            return;
        }
        sessions.findByTokenHash(TokenHasher.sha256(refreshToken)).ifPresent(s -> {
            if (s.getRevokedAt() == null) {
                s.setRevokedAt(Instant.now());
                s.setRevokeReason("LOGOUT");
            }
        });
    }

    @Transactional
    public void logoutSession(UUID sessionId) {
        sessions.findById(sessionId).ifPresent(s -> {
            if (s.getRevokedAt() == null) {
                s.setRevokedAt(Instant.now());
                s.setRevokeReason("LOGOUT");
            }
        });
    }

    /** Always succeeds from the caller's perspective (no account enumeration). */
    @Transactional
    public void forgotPassword(String email, String ip, String purpose) {
        users.findByEmailIgnoreCase(email.trim())
                .filter(u -> u.getStatus() != User.Status.DISABLED)
                .ifPresent(u -> {
                    Instant now = Instant.now();
                    resetTokens.invalidateOutstanding(u.getId(), now);
                    String raw = TokenHasher.newToken();
                    PasswordResetToken t = new PasswordResetToken();
                    t.setUserId(u.getId());
                    t.setTokenHash(TokenHasher.sha256(raw));
                    t.setPurpose(purpose);
                    t.setCreatedAt(now);
                    t.setExpiresAt(now.plusSeconds("RECOVERY".equals(purpose) ? 3600 : 1800));
                    t.setIpAddress(ip);
                    resetTokens.save(t);
                    String link = props.frontendBaseUrl() + "/reset-password?token=" + raw;
                    String subject = "RECOVERY".equals(purpose) ? "CareFlow account recovery" : "Reset your CareFlow password";
                    String body = "Hello " + u.getFullName() + ",\n\nUse this link to set a new password (valid for "
                            + ("RECOVERY".equals(purpose) ? "60" : "30") + " minutes):\n" + link
                            + "\n\nIf you did not request this, you can ignore this message.";
                    mailGateway.send(u.getOrganizationId(), NotificationDelivery.Channel.EMAIL, u.getEmail(), subject, body);
                    audit.recordFor(u.getOrganizationId(), u.getId(), u.getFullName(), u.getRole().name(),
                            "PASSWORD_" + purpose + "_REQUESTED", AuditEvent.Outcome.SUCCESS, "Reset link issued");
                });
    }

    @Transactional(readOnly = true)
    public boolean isResetTokenValid(String token) {
        return resetTokens.findByTokenHash(TokenHasher.sha256(token))
                .filter(t -> t.getUsedAt() == null && t.getExpiresAt().isAfter(Instant.now()))
                .isPresent();
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        PasswordResetToken t = resetTokens.findByTokenHash(TokenHasher.sha256(token))
                .filter(x -> x.getUsedAt() == null && x.getExpiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> ApiException.badRequest("INVALID_TOKEN", "This reset link is invalid or has expired."));
        User u = users.findById(t.getUserId()).orElseThrow();
        PasswordPolicy.validate(newPassword, u.getEmail());
        Instant now = Instant.now();
        u.setPasswordHash(passwordEncoder.encode(newPassword));
        u.setPasswordChangedAt(now);
        u.setMustChangePassword(false);
        u.setFailedLoginCount(0);
        u.setLockedUntil(null);
        if (u.getStatus() == User.Status.LOCKED) {
            u.setStatus(User.Status.ACTIVE);
        }
        t.setUsedAt(now);
        sessions.revokeAllForUser(u.getId(), now, "PASSWORD_RESET");
        audit.recordFor(u.getOrganizationId(), u.getId(), u.getFullName(), u.getRole().name(),
                "PASSWORD_RESET", AuditEvent.Outcome.SUCCESS, "Password reset via " + t.getPurpose().toLowerCase() + " link");
    }

    @Transactional
    public void changePassword(CurrentUser cu, String current, String next) {
        User u = users.findById(cu.userId()).orElseThrow();
        if (!passwordEncoder.matches(current, u.getPasswordHash())) {
            throw ApiException.badRequest("INVALID_PASSWORD", "Current password is incorrect.");
        }
        PasswordPolicy.validate(next, u.getEmail());
        Instant now = Instant.now();
        u.setPasswordHash(passwordEncoder.encode(next));
        u.setPasswordChangedAt(now);
        u.setMustChangePassword(false);
        // Keep the current session; revoke the others.
        sessions.revokeAllForUser(u.getId(), now, "PASSWORD_CHANGED");
        sessions.findById(cu.sessionId()).ifPresent(s -> {
            s.setRevokedAt(null);
            s.setRevokeReason(null);
        });
        audit.record("PASSWORD_CHANGED", "USER", u.getId(), null, "Password changed");
    }

    @Transactional(readOnly = true)
    public MeResponse me(CurrentUser cu) {
        User u = users.findById(cu.userId()).orElseThrow();
        Organization org = organizations.findById(u.getOrganizationId()).orElseThrow();
        AuthDtos.StaffInfo staff = null;
        if (u.getRole().isStaff()) {
            var profile = staffProfiles.findById(u.getId()).orElse(null);
            List<AuthDtos.WardInfo> wardInfos = new ArrayList<>();
            List<UUID> wardIds = wardAssignments.activeWardIds(u.getId());
            if (!wardIds.isEmpty()) {
                wards.findByOrganizationIdAndIdIn(u.getOrganizationId(), wardIds)
                        .forEach(w -> wardInfos.add(new AuthDtos.WardInfo(w.getId(), w.getCode(), w.getName())));
            }
            if (profile != null) {
                String deptName = profile.getDepartmentId() == null ? null
                        : departments.findById(profile.getDepartmentId()).map(d -> d.getName()).orElse(null);
                staff = new AuthDtos.StaffInfo(profile.getDesignation(), profile.getDepartmentId(), deptName,
                        profile.getSpecialty(), profile.getEmployeeCode(), wardInfos);
            } else {
                staff = new AuthDtos.StaffInfo(null, null, null, null, null, wardInfos);
            }
        }
        List<AuthDtos.PatientLink> accessible = new ArrayList<>();
        UUID ownPatientId = null;
        if (u.getRole() == Role.PATIENT) {
            Optional<Patient> own = patients.findByUserId(u.getId());
            if (own.isPresent()) {
                ownPatientId = own.get().getId();
                accessible.add(new AuthDtos.PatientLink(own.get().getId(), own.get().fullName(), own.get().getMrn(),
                        "SELF", "FULL"));
            }
            Instant now = Instant.now();
            grants.findByGranteeUserIdAndRevokedAtIsNull(u.getId()).stream()
                    .filter(g -> g.isActive(now))
                    .forEach(g -> patients.findById(g.getPatientId()).ifPresent(p ->
                            accessible.add(new AuthDtos.PatientLink(p.getId(), p.fullName(), p.getMrn(),
                                    g.getRelationship(), g.getScope().name()))));
        }
        return new MeResponse(u.getId(), u.getEmail(), u.getFullName(), u.getPhone(), u.getRole(),
                rolePermissions.permissionsFor(u.getOrganizationId(), u.getRole()), u.isMustChangePassword(),
                new AuthDtos.OrganizationInfo(org.getId(), org.getCode(), org.getName(), org.getTimezone(), org.getCurrency()),
                staff, ownPatientId, accessible);
    }

    private IssuedTokens newSession(User user, String ip, String userAgent, UserSession previous) {
        Instant now = Instant.now();
        String raw = TokenHasher.newToken();
        UserSession s = new UserSession();
        s.setUserId(user.getId());
        s.setTokenHash(TokenHasher.sha256(raw));
        s.setCreatedAt(now);
        s.setLastUsedAt(now);
        s.setExpiresAt(previous != null ? previous.getExpiresAt() : now.plus(props.security().refreshTokenTtl()));
        s.setIpAddress(ip);
        s.setUserAgent(userAgent == null ? null : userAgent.substring(0, Math.min(500, userAgent.length())));
        sessions.save(s);
        if (previous != null) {
            previous.setRevokedAt(now);
            previous.setRevokeReason("ROTATED");
            previous.setReplacedById(s.getId());
        }
        String access = jwtService.issueAccessToken(user.getId(), user.getOrganizationId(), user.getRole(), s.getId());
        return new IssuedTokens(access, raw, user.getId());
    }

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password.");
    }

    private static ApiException sessionExpired() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "Your session has expired. Please sign in again.");
    }
}
