package dev.blazebench.projection;

import dev.blazebench.domain.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The response of an "order list" API: order fields, two customer fields and a nested list of lines
 * with the product name. Every strategy produces exactly this, lines sorted by id.
 */
public record OrderSummary(long id, LocalDateTime createdAt, OrderStatus status, BigDecimal totalAmount,
		String customerName, String customerCountry, List<LineSummary> lines) {

	public record LineSummary(long id, String productName, int quantity, BigDecimal unitPrice) {
	}

}
