package dev.blazebench.jpa;

import dev.blazebench.domain.PurchaseOrder;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

	/** Spring Data's built-in keyset scrolling (available since Spring Data 3.1). */
	Window<PurchaseOrder> findFirst20ByOrderByCreatedAtDescIdDesc(ScrollPosition position);

	/**
	 * The usual Spring Data way to load a collection with a page. Returns a List (no COUNT query),
	 * so only the collection-fetch behaviour is measured.
	 */
	@EntityGraph(attributePaths = "lines")
	List<PurchaseOrder> findByCreatedAtGreaterThanEqual(LocalDateTime since, Pageable pageable);

	/** Interface projection with nested customer, lines and products; see {@link OrderSummaryProjection}. */
	List<OrderSummaryProjection> findProjectedBy(Pageable pageable);

}
