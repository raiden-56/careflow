package com.careflow.hms.common;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/** Stable pagination envelope (Spring's Page JSON shape is not a stable contract). */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    /** Paginates an in-memory list (for small, already-filtered result sets). */
    public static <T> PageResponse<T> ofList(List<T> all, int page, int size) {
        int safeSize = Math.max(size, 1);
        int from = Math.min(Math.max(page, 0) * safeSize, all.size());
        int to = Math.min(from + safeSize, all.size());
        int totalPages = (int) Math.ceil(all.size() / (double) safeSize);
        return new PageResponse<>(all.subList(from, to), page, safeSize, all.size(), totalPages);
    }
}
