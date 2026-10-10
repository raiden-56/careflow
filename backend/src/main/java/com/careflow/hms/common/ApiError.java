package com.careflow.hms.common;

import java.time.Instant;
import java.util.List;

/** Consistent error response body for every API failure. */
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        String traceId,
        List<FieldError> fieldErrors) {

    public record FieldError(String field, String message) {
    }
}
