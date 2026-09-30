package dev.blazebench.collectionfetch;

import com.blazebit.persistence.CriteriaBuilderFactory;
import dev.blazebench.domain.OrderLine;
import dev.blazebench.domain.PurchaseOrder;
import dev.blazebench.jpa.PurchaseOrderRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * "Newest 20 orders created since X, with their lines", implemented with each strategy.
 * {@code since} controls how many orders match the filter before paging, which is what
 * in-memory paging scales with.
 */
@Service
@RequiredArgsConstructor
public class CollectionFetchQueries {

	public static final int PAGE_SIZE = 20;

	private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

	private final EntityManager em;

	private final CriteriaBuilderFactory cbf;

	private final PurchaseOrderRepository repository;

	@Transactional(readOnly = true)
	public List<OrderWithLines> fetch(CollectionFetchScenario scenario, LocalDateTime since) {
		List<PurchaseOrder> orders = switch (scenario) {
			case JPA_FETCH_JOIN -> jpaFetchJoin(since);
			case SPRING_DATA_ENTITY_GRAPH ->
				repository.findByCreatedAtGreaterThanEqual(since, PageRequest.of(0, PAGE_SIZE, NEWEST_FIRST));
			case LAZY_N_PLUS_ONE -> pageOfOrders(since);
			case HIBERNATE_BATCH_FETCH -> {
				em.unwrap(Session.class).setFetchBatchSize(PAGE_SIZE);
				yield pageOfOrders(since);
			}
			case HIBERNATE_SUBSELECT_FETCH -> {
				em.unwrap(Session.class).setSubselectFetchingEnabled(true);
				yield pageOfOrders(since);
			}
			case TWO_STEP_IDS -> twoStepIds(since);
			case BLAZE_FETCH -> blazeFetch(since);
		};
		// Touching the collections inside the transaction is what triggers lazy loading
		// for the strategies that don't fetch eagerly.
		return orders.stream()
			.map(o -> new OrderWithLines(o.getId(),
					o.getLines().stream().map(OrderLine::getId).sorted().toList()))
			.toList();
	}

	/**
	 * Benchmark setup helper (not measured): the createdAt of the n-th newest order, so that
	 * {@code createdAt >= since} matches (about) n orders.
	 */
	@Transactional(readOnly = true)
	public LocalDateTime sinceForNewest(int n) {
		return em.createQuery("select o.createdAt from PurchaseOrder o order by o.createdAt desc, o.id desc",
				LocalDateTime.class)
			.setFirstResult(n - 1)
			.setMaxResults(1)
			.getSingleResult();
	}

	@Transactional(readOnly = true)
	public long countMatching(LocalDateTime since) {
		return em.createQuery("select count(o) from PurchaseOrder o where o.createdAt >= :since", Long.class)
			.setParameter("since", since)
			.getSingleResult();
	}

	/**
	 * The classic trap: LIMIT cannot go on a query whose rows are multiplied by the collection.
	 * Up to Hibernate 7.3 it loads every matching order with all its lines and keeps the first 20
	 * in memory (warning HHH90003004). Hibernate 7.4 pages the orders in a subquery instead.
	 */
	private List<PurchaseOrder> jpaFetchJoin(LocalDateTime since) {
		return em.createQuery("""
				select o from PurchaseOrder o join fetch o.lines
				where o.createdAt >= :since
				order by o.createdAt desc, o.id desc""", PurchaseOrder.class)
			.setParameter("since", since)
			.setMaxResults(PAGE_SIZE)
			.getResultList();
	}

	/** Just the page of orders; collections stay lazy until touched. */
	private List<PurchaseOrder> pageOfOrders(LocalDateTime since) {
		return em.createQuery("""
				select o from PurchaseOrder o
				where o.createdAt >= :since
				order by o.createdAt desc, o.id desc""", PurchaseOrder.class)
			.setParameter("since", since)
			.setMaxResults(PAGE_SIZE)
			.getResultList();
	}

	/** The well-known manual fix: LIMIT on an id-only query, then join fetch just those ids. */
	private List<PurchaseOrder> twoStepIds(LocalDateTime since) {
		List<Long> ids = em.createQuery("""
				select o.id from PurchaseOrder o
				where o.createdAt >= :since
				order by o.createdAt desc, o.id desc""", Long.class)
			.setParameter("since", since)
			.setMaxResults(PAGE_SIZE)
			.getResultList();
		return em.createQuery("""
				select o from PurchaseOrder o join fetch o.lines
				where o.id in :ids
				order by o.createdAt desc, o.id desc""", PurchaseOrder.class)
			.setParameter("ids", ids)
			.getResultList();
	}

	/** Blaze detects the collection fetch and does the id-then-fetch split by itself. */
	private List<PurchaseOrder> blazeFetch(LocalDateTime since) {
		return cbf.create(em, PurchaseOrder.class)
			.fetch("lines")
			.where("createdAt").ge(since)
			.orderByDesc("createdAt")
			.orderByDesc("id")
			.page(0, PAGE_SIZE)
			.withCountQuery(false)
			.getResultList();
	}

}
