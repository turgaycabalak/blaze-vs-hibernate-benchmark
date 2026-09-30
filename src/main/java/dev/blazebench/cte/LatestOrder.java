package dev.blazebench.cte;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One row per customer: that customer's most recent order. */
public record LatestOrder(long customerId, String customerName, long orderId, LocalDateTime createdAt,
		BigDecimal totalAmount) {
}
