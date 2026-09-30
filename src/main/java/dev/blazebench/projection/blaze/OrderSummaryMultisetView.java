package dev.blazebench.projection.blaze;

import com.blazebit.persistence.view.EntityView;
import com.blazebit.persistence.view.FetchStrategy;
import com.blazebit.persistence.view.IdMapping;
import com.blazebit.persistence.view.Mapping;
import dev.blazebench.domain.OrderStatus;
import dev.blazebench.domain.PurchaseOrder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Same shape as {@link OrderSummaryJoinView}, but the lines are aggregated per order inside the
 * database (MULTISET), so the result has one row per order instead of one row per line.
 */
@EntityView(PurchaseOrder.class)
public interface OrderSummaryMultisetView {

	@IdMapping
	Long getId();

	LocalDateTime getCreatedAt();

	OrderStatus getStatus();

	BigDecimal getTotalAmount();

	@Mapping("customer.name")
	String getCustomerName();

	@Mapping("customer.country")
	String getCustomerCountry();

	@Mapping(value = "lines", fetch = FetchStrategy.MULTISET)
	List<LineSummaryView> getLines();

}
