package dev.blazebench.projection.blaze;

import com.blazebit.persistence.view.EntityView;
import com.blazebit.persistence.view.IdMapping;
import com.blazebit.persistence.view.Mapping;
import dev.blazebench.domain.OrderStatus;
import dev.blazebench.domain.PurchaseOrder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Default fetch strategy (JOIN): one SQL query; with pagination Blaze pages the ids in a subquery. */
@EntityView(PurchaseOrder.class)
public interface OrderSummaryJoinView {

	@IdMapping
	Long getId();

	LocalDateTime getCreatedAt();

	OrderStatus getStatus();

	BigDecimal getTotalAmount();

	@Mapping("customer.name")
	String getCustomerName();

	@Mapping("customer.country")
	String getCustomerCountry();

	List<LineSummaryView> getLines();

}
