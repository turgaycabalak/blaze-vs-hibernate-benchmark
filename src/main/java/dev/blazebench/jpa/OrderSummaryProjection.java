package dev.blazebench.jpa;

import dev.blazebench.domain.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Spring Data interface projection with the same shape as the article's DTO, including the
 * nested collection.
 */
public interface OrderSummaryProjection {

	Long getId();

	LocalDateTime getCreatedAt();

	OrderStatus getStatus();

	BigDecimal getTotalAmount();

	CustomerPart getCustomer();

	List<LinePart> getLines();

	interface CustomerPart {

		String getName();

		String getCountry();

	}

	interface LinePart {

		Long getId();

		ProductPart getProduct();

		int getQuantity();

		BigDecimal getUnitPrice();

	}

	interface ProductPart {

		String getName();

	}

}
