package dev.blazebench.cte;

import com.blazebit.persistence.CTE;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Blaze-Persistence needs an entity class describing the columns of a CTE (or of a subquery in FROM).
 * Plain HQL does not: that difference is part of the maintenance comparison.
 */
@CTE
@Entity
@Getter
@Setter
@NoArgsConstructor
public class LatestOrderCte {

	@Id
	private Long orderId;

	private Long customerId;

	private LocalDateTime createdAt;

	private BigDecimal totalAmount;

	private Long rowNumber;

}
