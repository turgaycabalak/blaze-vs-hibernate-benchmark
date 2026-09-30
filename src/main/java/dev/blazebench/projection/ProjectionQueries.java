package dev.blazebench.projection;

import com.blazebit.persistence.CriteriaBuilder;
import com.blazebit.persistence.CriteriaBuilderFactory;
import com.blazebit.persistence.view.EntityViewManager;
import com.blazebit.persistence.view.EntityViewSetting;
import dev.blazebench.domain.OrderStatus;
import dev.blazebench.domain.PurchaseOrder;
import dev.blazebench.jpa.OrderSummaryProjection;
import dev.blazebench.jpa.PurchaseOrderRepository;
import dev.blazebench.projection.OrderSummary.LineSummary;
import dev.blazebench.projection.blaze.LineSummaryView;
import dev.blazebench.projection.blaze.OrderSummaryJoinView;
import dev.blazebench.projection.blaze.OrderSummaryMultisetView;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The newest {@code pageSize} orders as {@link OrderSummary}, built with each strategy. Every result
 * is converted to the same record inside the transaction, so lazy strategies really run their queries.
 */
@Service
@RequiredArgsConstructor
public class ProjectionQueries {

	private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

	private final EntityManager em;

	private final CriteriaBuilderFactory cbf;

	private final EntityViewManager evm;

	private final PurchaseOrderRepository repository;

	@Transactional(readOnly = true)
	public List<OrderSummary> fetch(ProjectionScenario scenario, int pageSize) {
		return switch (scenario) {
			case ENTITY_FETCH_JOIN -> fromEntities(entitiesWithFetchJoin(pageSize));
			case ENTITY_LAZY -> fromEntities(entities(pageSize));
			case ENTITY_LAZY_BATCH -> {
				em.unwrap(Session.class).setFetchBatchSize(pageSize);
				yield fromEntities(entities(pageSize));
			}
			case SPRING_DATA_PROJECTION -> fromProjections(
					repository.findProjectedBy(PageRequest.of(0, pageSize, NEWEST_FIRST)));
			case HQL_DTO -> hqlDto(pageSize);
			case BLAZE_VIEW_JOIN -> blazeView(OrderSummaryJoinView.class, pageSize).stream()
				.map(v -> summary(v.getId(), v.getCreatedAt(), v.getStatus(), v.getTotalAmount(), v.getCustomerName(),
						v.getCustomerCountry(), fromLineViews(v.getLines())))
				.toList();
			case BLAZE_VIEW_MULTISET -> blazeView(OrderSummaryMultisetView.class, pageSize).stream()
				.map(v -> summary(v.getId(), v.getCreatedAt(), v.getStatus(), v.getTotalAmount(), v.getCustomerName(),
						v.getCustomerCountry(), fromLineViews(v.getLines())))
				.toList();
		};
	}

	// --- entities -----------------------------------------------------------------------------

	/** Relies on Hibernate 7.4+ to page the orders in SQL despite the collection fetch. */
	private List<PurchaseOrder> entitiesWithFetchJoin(int pageSize) {
		return em.createQuery("""
				select o from PurchaseOrder o
				join fetch o.customer
				join fetch o.lines l
				join fetch l.product
				order by o.createdAt desc, o.id desc""", PurchaseOrder.class)
			.setMaxResults(pageSize)
			.getResultList();
	}

	private List<PurchaseOrder> entities(int pageSize) {
		return em.createQuery("select o from PurchaseOrder o order by o.createdAt desc, o.id desc", PurchaseOrder.class)
			.setMaxResults(pageSize)
			.getResultList();
	}

	/** A typical hand-written (or MapStruct-generated) mapper: it just calls getters. */
	private static List<OrderSummary> fromEntities(List<PurchaseOrder> orders) {
		return orders.stream()
			.map(o -> summary(o.getId(), o.getCreatedAt(), o.getStatus(), o.getTotalAmount(), o.getCustomer().getName(),
					o.getCustomer().getCountry(),
					o.getLines()
						.stream()
						.map(l -> new LineSummary(l.getId(), l.getProduct().getName(), l.getQuantity(),
								l.getUnitPrice()))
						.toList()))
			.toList();
	}

	// --- Spring Data --------------------------------------------------------------------------

	private static List<OrderSummary> fromProjections(List<OrderSummaryProjection> orders) {
		return orders.stream()
			.map(o -> summary(o.getId(), o.getCreatedAt(), o.getStatus(), o.getTotalAmount(), o.getCustomer().getName(),
					o.getCustomer().getCountry(),
					o.getLines()
						.stream()
						.map(l -> new LineSummary(l.getId(), l.getProduct().getName(), l.getQuantity(),
								l.getUnitPrice()))
						.toList()))
			.toList();
	}

	// --- hand-written HQL ---------------------------------------------------------------------

	public record OrderRow(Long id, LocalDateTime createdAt, OrderStatus status, BigDecimal totalAmount,
			String customerName, String customerCountry) {
	}

	public record LineRow(Long orderId, Long id, String productName, int quantity, BigDecimal unitPrice) {
	}

	/** Best practice without extra libraries: one query per level, only the needed columns. */
	private List<OrderSummary> hqlDto(int pageSize) {
		List<OrderRow> orders = em.createQuery("""
				select new dev.blazebench.projection.ProjectionQueries$OrderRow(
				    o.id, o.createdAt, o.status, o.totalAmount, c.name, c.country)
				from PurchaseOrder o join o.customer c
				order by o.createdAt desc, o.id desc""", OrderRow.class)
			.setMaxResults(pageSize)
			.getResultList();
		Map<Long, List<LineRow>> linesByOrder = em.createQuery("""
				select new dev.blazebench.projection.ProjectionQueries$LineRow(
				    l.order.id, l.id, p.name, l.quantity, l.unitPrice)
				from OrderLine l join l.product p
				where l.order.id in :ids""", LineRow.class)
			.setParameter("ids", orders.stream().map(OrderRow::id).toList())
			.getResultList()
			.stream()
			.collect(Collectors.groupingBy(LineRow::orderId));
		return orders.stream()
			.map(o -> summary(o.id(), o.createdAt(), o.status(), o.totalAmount(), o.customerName(),
					o.customerCountry(),
					linesByOrder.getOrDefault(o.id(), List.of())
						.stream()
						.map(l -> new LineSummary(l.id(), l.productName(), l.quantity(), l.unitPrice()))
						.toList()))
			.toList();
	}

	// --- Blaze --------------------------------------------------------------------------------

	private <V> List<V> blazeView(Class<V> view, int pageSize) {
		CriteriaBuilder<PurchaseOrder> cb = cbf.create(em, PurchaseOrder.class)
			.orderByDesc("createdAt")
			.orderByDesc("id");
		return evm.applySetting(EntityViewSetting.create(view, 0, pageSize), cb).withCountQuery(false).getResultList();
	}

	private static List<LineSummary> fromLineViews(List<LineSummaryView> lines) {
		return lines.stream()
			.map(l -> new LineSummary(l.getId(), l.getProductName(), l.getQuantity(), l.getUnitPrice()))
			.toList();
	}

	// --- common -------------------------------------------------------------------------------

	private static OrderSummary summary(Long id, LocalDateTime createdAt, OrderStatus status, BigDecimal totalAmount,
			String customerName, String customerCountry, List<LineSummary> lines) {
		return new OrderSummary(id, createdAt, status, totalAmount, customerName, customerCountry,
				lines.stream().sorted(Comparator.comparingLong(LineSummary::id)).toList());
	}
}
