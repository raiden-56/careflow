package com.careflow.hms.auth;

import com.careflow.hms.auth.AuthDtos.AccountRecoveryRequest;
import com.careflow.hms.auth.AuthDtos.ChangePasswordRequest;
import com.careflow.hms.auth.AuthDtos.ForgotPasswordRequest;
import com.careflow.hms.auth.AuthDtos.LoginRequest;
import com.careflow.hms.auth.AuthDtos.MeResponse;
import com.careflow.hms.auth.AuthDtos.ResetPasswordRequest;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.security.AuthCookies;
import com.careflow.hms.security.CurrentUser;
import com.careflow.hms.security.LoginRateLimiter;
import com.careflow.hms.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "Authentication")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuthCookies cookies;
    private final LoginRateLimiter rateLimiter;

    @Operation(summary = "Sign in. Sets httpOnly session cookies and also returns the access token for API tools.")
    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest body, HttpServletRequest req, HttpServletResponse res) {
        String ip = req.getRemoteAddr();
        if (!rateLimiter.tryAcquire("login-ip:" + ip) || !rateLimiter.tryAcquire("login-acct:" + body.email().toLowerCase())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many attempts. Please wait a minute and try again.");
        }
        AuthService.IssuedTokens t = authService.login(body.email(), body.password(), ip, req.getHeader(HttpHeaders.USER_AGENT));
        cookies.write(res, t.accessToken(), t.refreshToken());
        return Map.of("accessToken", t.accessToken(), "tokenType", "Bearer");
    }

    @Operation(summary = "Rotate the refresh token (cookie) and issue a new access token.")
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh(HttpServletRequest req, HttpServletResponse res) {
        String refresh = AuthCookies.read(req, AuthCookies.REFRESH)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "Your session has expired. Please sign in again."));
        if (!rateLimiter.tryAcquire("refresh-ip:" + req.getRemoteAddr())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests.");
        }
        try {
            AuthService.IssuedTokens t = authService.refresh(refresh, req.getRemoteAddr(), req.getHeader(HttpHeaders.USER_AGENT));
            cookies.write(res, t.accessToken(), t.refreshToken());
            return ResponseEntity.ok(Map.of("accessToken", t.accessToken(), "tokenType", "Bearer"));
        } catch (ApiException e) {
            cookies.clear(res);
            throw e;
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest req, HttpServletResponse res) {
        authService.logout(AuthCookies.read(req, AuthCookies.REFRESH).orElse(null));
        TenantContext.currentUser().ifPresent(u -> authService.logoutSession(u.sessionId()));
        cookies.clear(res);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public MeResponse me() {
        return authService.me(TenantContext.requireUser());
    }

    @Operation(summary = "Request a password reset link. Always returns 202 to prevent account enumeration.")
    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgot(@Valid @RequestBody ForgotPasswordRequest body, HttpServletRequest req) {
        if (!rateLimiter.tryAcquire("forgot-ip:" + req.getRemoteAddr())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests. Please wait a minute.");
        }
        authService.forgotPassword(body.email(), req.getRemoteAddr(), "RESET");
        return ResponseEntity.accepted().body(Map.of("message",
                "If an account exists for that email, a reset link has been sent."));
    }

    @Operation(summary = "Secure account recovery (locked account or lost access). Always returns 202.")
    @PostMapping("/account-recovery")
    public ResponseEntity<Map<String, String>> recovery(@Valid @RequestBody AccountRecoveryRequest body, HttpServletRequest req) {
        if (!rateLimiter.tryAcquire("recovery-ip:" + req.getRemoteAddr())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests. Please wait a minute.");
        }
        authService.forgotPassword(body.email(), req.getRemoteAddr(), "RECOVERY");
        return ResponseEntity.accepted().body(Map.of("message",
                "If an account exists for that email, recovery instructions have been sent to the registered address."));
    }

    @GetMapping("/reset-token/validate")
    public Map<String, Boolean> validateToken(@RequestParam String token) {
        return Map.of("valid", authService.isResetTokenValid(token));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> reset(@Valid @RequestBody ResetPasswordRequest body, HttpServletRequest req) {
        if (!rateLimiter.tryAcquire("reset-ip:" + req.getRemoteAddr())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests. Please wait a minute.");
        }
        authService.resetPassword(body.token(), body.newPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/change-password")
    public ResponseEntity<Void> change(@Valid @RequestBody ChangePasswordRequest body) {
        CurrentUser u = TenantContext.requireUser();
        authService.changePassword(u, body.currentPassword(), body.newPassword());
        return ResponseEntity.noContent().build();
    }
}
