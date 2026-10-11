package com.careflow.hms.admin;

import com.careflow.hms.common.ApiError;
import com.careflow.hms.config.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

/** Renders per-setting validation errors with field details (same body shape as other API errors). */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminExceptionAdvice {

    @ExceptionHandler(HospitalSettingsService.SettingsValidationException.class)
    public ResponseEntity<ApiError> settings(HospitalSettingsService.SettingsValidationException ex, HttpServletRequest req) {
        return ResponseEntity.status(ex.getStatus()).body(new ApiError(Instant.now(), ex.getStatus().value(), ex.getCode(),
                ex.getMessage(), req.getRequestURI(), MDC.get(TraceIdFilter.MDC_KEY), ex.fieldErrors()));
    }
}
