package com.careflow.hms.common;

import com.careflow.hms.config.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Friendly messages for named database constraints that protect business invariants. */
    private static final Map<String, String[]> CONSTRAINT_MESSAGES = Map.ofEntries(
            Map.entry("ux_bed_assignments_open_bed", new String[]{"BED_ALREADY_ASSIGNED", "This bed is already assigned to another active admission."}),
            Map.entry("ux_bed_assignments_open_admission", new String[]{"ADMISSION_ALREADY_HAS_BED", "This admission already has an active bed assignment."}),
            Map.entry("ux_admissions_one_open_per_patient", new String[]{"PATIENT_ALREADY_ADMITTED", "The patient already has an open admission."}),
            Map.entry("ux_users_email", new String[]{"EMAIL_IN_USE", "An account with this email already exists."}),
            Map.entry("payments_organization_id_idempotency_key_key", new String[]{"DUPLICATE_PAYMENT", "This payment has already been recorded."}),
            Map.entry("ux_invoices_one_active_per_admission", new String[]{"INVOICE_EXISTS", "An active invoice already exists for this admission."}),
            Map.entry("ux_line_items_source", new String[]{"CHARGE_ALREADY_POSTED", "This charge has already been posted."}),
            Map.entry("ux_discharge_open", new String[]{"DISCHARGE_IN_PROGRESS", "A discharge workflow is already in progress for this admission."}),
            Map.entry("ux_treatment_plan_active", new String[]{"PLAN_CONFLICT", "Another active treatment plan was created concurrently. Reload and try again."}),
            Map.entry("ux_grants_active", new String[]{"GRANT_EXISTS", "This person already has access."}),
            Map.entry("ux_staff_ward_active", new String[]{"ALREADY_ASSIGNED", "The staff member is already assigned to this ward."}));

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex, HttpServletRequest req) {
        return build(ex.getStatus(), ex.getCode(), ex.getMessage(), req, null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<ApiError.FieldError> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        List<ApiError.FieldError> global = ex.getBindingResult().getGlobalErrors().stream()
                .map(ge -> new ApiError.FieldError(ge.getObjectName(), ge.getDefaultMessage()))
                .toList();
        List<ApiError.FieldError> all = new java.util.ArrayList<>(fields);
        all.addAll(global);
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are invalid. Please review and try again.", req, all);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraint(ConstraintViolationException ex, HttpServletRequest req) {
        List<ApiError.FieldError> fields = ex.getConstraintViolations().stream()
                .map(v -> new ApiError.FieldError(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are invalid.", req, fields);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> handleBadInput(Exception ex, HttpServletRequest req) {
        String msg = ex instanceof MissingServletRequestParameterException m
                ? "Missing required parameter '" + m.getParameterName() + "'"
                : "The request could not be read. Check the field formats.";
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", msg, req, null);
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public ResponseEntity<ApiError> handleDenied(Exception ex, HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to perform this action.", req, null);
    }

    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, PessimisticLockingFailureException.class})
    public ResponseEntity<ApiError> handleOptimistic(Exception ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, "CONCURRENT_UPDATE",
                "This record was changed by someone else. Reload and try again.", req, null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException ex, HttpServletRequest req) {
        String detail = String.valueOf(ex.getMostSpecificCause().getMessage());
        for (var e : CONSTRAINT_MESSAGES.entrySet()) {
            if (detail.contains(e.getKey())) {
                return build(HttpStatus.CONFLICT, e.getValue()[0], e.getValue()[1], req, null);
            }
        }
        if (detail.contains("append-only")) {
            return build(HttpStatus.CONFLICT, "IMMUTABLE_RECORD", "This record is append-only and cannot be changed.", req, null);
        }
        log.warn("Data integrity violation: {}", detail);
        return build(HttpStatus.CONFLICT, "DATA_CONFLICT", "The change conflicts with existing data.", req, null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleUpload(MaxUploadSizeExceededException ex, HttpServletRequest req) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "The file exceeds the maximum allowed size.", req, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethod(HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "HTTP method not supported for this endpoint.", req, null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "Endpoint not found.", req, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest req) {
        log.error("Unhandled error on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred. Reference: " + MDC.get(TraceIdFilter.MDC_KEY), req, null);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message, HttpServletRequest req,
                                           List<ApiError.FieldError> fields) {
        ApiError body = new ApiError(Instant.now(), status.value(), code, message, req.getRequestURI(),
                MDC.get(TraceIdFilter.MDC_KEY), fields);
        return ResponseEntity.status(status).body(body);
    }
}
