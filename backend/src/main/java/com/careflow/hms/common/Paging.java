package com.careflow.hms.common;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Map;

/** Builds safe Pageable objects from request parameters, with a whitelist of sortable fields. */
public final class Paging {

    public static final int MAX_SIZE = 100;

    private Paging() {
    }

    /**
     * @param sort              "field,asc|desc" from the client
     * @param sortFieldMapping  client field name -> entity property path (whitelist)
     */
    public static Pageable of(int page, int size, String sort, Map<String, String> sortFieldMapping, Sort defaultSort) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);
        Sort s = defaultSort;
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",");
            String field = parts[0].trim();
            String property = sortFieldMapping.get(field);
            if (property == null) {
                throw ApiException.badRequest("INVALID_SORT", "Sorting by '" + field + "' is not supported");
            }
            Sort.Direction dir = parts.length > 1 && parts[1].trim().equalsIgnoreCase("asc")
                    ? Sort.Direction.ASC : Sort.Direction.DESC;
            s = Sort.by(dir, property);
        }
        return PageRequest.of(safePage, safeSize, s);
    }

    /**
     * Lower-cases and wraps a search term for JPQL LIKE queries; returns null when blank.
     * Hibernate 6 renders JPQL {@code like} on PostgreSQL with {@code escape ''}, so backslash escapes would be
     * matched literally (a search for "LAB_CRP" found nothing). '_' and '%' typed by a user therefore act as
     * wildcards, which only ever widens a search box result.
     */
    public static String likePattern(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        return "%" + q.trim().toLowerCase() + "%";
    }
}
