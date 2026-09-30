package dev.blazebench.blaze;

import com.blazebit.persistence.view.EntityView;
import com.blazebit.persistence.view.IdMapping;
import com.blazebit.persistence.view.Mapping;
import dev.blazebench.domain.OrderStatus;
import dev.blazebench.domain.PurchaseOrder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@EntityView(PurchaseOrder.class)
public interface OrderSummaryView {

	@IdMapping
	Long getId();

	LocalDateTime getCreatedAt();

	OrderStatus getStatus();

	BigDecimal getTotalAmount();

	@Mapping("customer.name")
	String getCustomerName();

}
