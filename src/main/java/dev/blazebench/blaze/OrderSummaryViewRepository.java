package dev.blazebench.blaze;

import com.blazebit.persistence.spring.data.repository.EntityViewRepository;
import com.blazebit.persistence.spring.data.repository.EntityViewSpecificationExecutor;
import dev.blazebench.domain.OrderStatus;
import dev.blazebench.domain.PurchaseOrder;

import java.util.List;

public interface OrderSummaryViewRepository extends EntityViewRepository<OrderSummaryView, Long>,
		EntityViewSpecificationExecutor<OrderSummaryView, PurchaseOrder> {

	List<OrderSummaryView> findByStatus(OrderStatus status);

}
