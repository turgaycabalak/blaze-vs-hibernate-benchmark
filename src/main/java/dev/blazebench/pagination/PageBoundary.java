package dev.blazebench.pagination;

import java.time.LocalDateTime;

/**
 * Sort key of the last row on the previous page. This is what a client sends back
 * ("give me the page after this row") in keyset pagination.
 */
public record PageBoundary(LocalDateTime createdAt, long id) {
}
