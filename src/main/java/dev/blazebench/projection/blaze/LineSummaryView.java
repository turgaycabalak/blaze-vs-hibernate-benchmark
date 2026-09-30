package dev.blazebench.projection.blaze;

import com.blazebit.persistence.view.EntityView;
import com.blazebit.persistence.view.IdMapping;
import com.blazebit.persistence.view.Mapping;
import dev.blazebench.domain.OrderLine;

import java.math.BigDecimal;

@EntityView(OrderLine.class)
public interface LineSummaryView {

	@IdMapping
	Long getId();

	@Mapping("product.name")
	String getProductName();

	int getQuantity();

	BigDecimal getUnitPrice();

}
