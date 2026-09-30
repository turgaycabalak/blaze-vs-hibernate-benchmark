package dev.blazebench.collectionfetch;

import java.util.List;

/**
 * What every strategy hands back: the order and the ids of its lines (sorted), read inside the
 * transaction, so lazy strategies really do load their collections.
 */
public record OrderWithLines(long orderId, List<Long> lineIds) {
}
