package dev.blazebench.pagination;

import com.blazebit.persistence.CriteriaBuilderFactory;
import com.blazebit.persistence.DefaultKeyset;
import com.blazebit.persistence.DefaultKeysetPage;
import com.blazebit.persistence.Keyset;
import com.blazebit.persistence.KeysetPage;
import dev.blazebench.domain.PurchaseOrder;
import dev.blazebench.jpa.PurchaseOrderRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.hibernate.query.KeyedPage;
import org.hibernate.query.KeyedPage.KeyInterpretation;
import org.hibernate.query.Order;
import org.hibernate.query.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * "Newest orders first", 20 per page, implemented with each pagination strategy.
 * Every call runs in its own read-only transaction, so no persistence context is reused.
 */
@Service
@RequiredArgsConstructor
public class PaginationQueries {

	public static final int PAGE_SIZE = 20;

	private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

	private static final List<Order<? super PurchaseOrder>> HIBERNATE_KEY = List.of(
			Order.desc(PurchaseOrder.class, "createdAt"),
			Order.desc(PurchaseOrder.class, "id"));

	private final EntityManager em;

	private final CriteriaBuilderFactory cbf;

	private final PurchaseOrderRepository repository;

	/**
	 * @param pageIndex zero-based page index
	 * @param previous  last row of the previous page; required by keyset scenarios for every page but the first
	 */
	@Transactional(readOnly = true)
	public List<PurchaseOrder> fetch(PaginationScenario scenario, int pageIndex, PageBoundary previous) {
		if (scenario.isKeyset() && pageIndex > 0 && previous == null) {
			throw new IllegalArgumentException(scenario + " needs the previous page boundary for page " + pageIndex);
		}
		return switch (scenario) {
			case SPRING_DATA_PAGE -> springDataPage(pageIndex);
			case JPA_OFFSET -> jpaOffset(pageIndex);
			case BLAZE_OFFSET -> blazeOffset(pageIndex);
			case SPRING_DATA_KEYSET -> springDataKeyset(previous);
			case HIBERNATE_KEYSET -> hibernateKeyset(pageIndex, previous);
			case HQL_ROW_VALUE_KEYSET -> hqlRowValueKeyset(previous);
			case BLAZE_KEYSET -> blazeKeyset(pageIndex, previous, false);
			case BLAZE_KEYSET_WITH_COUNT -> blazeKeyset(pageIndex, previous, true);
		};
	}

	/**
	 * Benchmark setup helper (not measured): the sort key of the last row on the given page,
	 * i.e. what a keyset client would hold after reading that page.
	 */
	@Transactional(readOnly = true)
	public PageBoundary lastRowOf(int pageIndex) {
		Object[] row = em
			.createQuery("select o.createdAt, o.id from PurchaseOrder o order by o.createdAt desc, o.id desc",
					Object[].class)
			.setFirstResult((pageIndex + 1) * PAGE_SIZE - 1)
			.setMaxResults(1)
			.getSingleResult();
		return new PageBoundary((LocalDateTime) row[0], (Long) row[1]);
	}

	/** The Spring Data default: OFFSET query plus a COUNT(*) query for the total. */
	private List<PurchaseOrder> springDataPage(int pageIndex) {
		return repository.findAll(PageRequest.of(pageIndex, PAGE_SIZE, NEWEST_FIRST)).getContent();
	}

	private List<PurchaseOrder> jpaOffset(int pageIndex) {
		return em.createQuery("select o from PurchaseOrder o order by o.createdAt desc, o.id desc", PurchaseOrder.class)
			.setFirstResult(pageIndex * PAGE_SIZE)
			.setMaxResults(PAGE_SIZE)
			.getResultList();
	}

	private List<PurchaseOrder> blazeOffset(int pageIndex) {
		return cbf.create(em, PurchaseOrder.class)
			.orderByDesc("createdAt")
			.orderByDesc("id")
			.page(pageIndex * PAGE_SIZE, PAGE_SIZE)
			.withCountQuery(false)
			.getResultList();
	}

	private List<PurchaseOrder> springDataKeyset(PageBoundary previous) {
		ScrollPosition position = previous == null
				? ScrollPosition.keyset()
				: ScrollPosition.forward(Map.of("createdAt", previous.createdAt(), "id", previous.id()));
		return repository.findFirst20ByOrderByCreatedAtDescIdDesc(position).getContent();
	}

	private List<PurchaseOrder> hibernateKeyset(int pageIndex, PageBoundary previous) {
		KeyedPage<PurchaseOrder> page = Page.page(PAGE_SIZE, pageIndex).keyedBy(HIBERNATE_KEY);
		if (previous != null) {
			page = page.withKey(List.of(previous.createdAt(), previous.id()),
					KeyInterpretation.KEY_OF_LAST_ON_PREVIOUS_PAGE);
		}
		return em.unwrap(Session.class)
			.createSelectionQuery("from PurchaseOrder", PurchaseOrder.class)
			.getKeyedResultList(page)
			.getResultList();
	}

	/**
	 * Plain Hibernate, no extra library: the keyset predicate written by hand as a row-value comparison,
	 * the same shape Blaze generates. Tells whether Blaze's advantage is the library or the predicate.
	 */
	private List<PurchaseOrder> hqlRowValueKeyset(PageBoundary previous) {
		if (previous == null) {
			return em
				.createQuery("select o from PurchaseOrder o order by o.createdAt desc, o.id desc", PurchaseOrder.class)
				.setMaxResults(PAGE_SIZE)
				.getResultList();
		}
		return em.createQuery("""
				select o from PurchaseOrder o
				where (o.createdAt, o.id) < (:createdAt, :id)
				order by o.createdAt desc, o.id desc""", PurchaseOrder.class)
			.setParameter("createdAt", previous.createdAt())
			.setParameter("id", previous.id())
			.setMaxResults(PAGE_SIZE)
			.getResultList();
	}

	/**
	 * Blaze decides between OFFSET and keyset by itself: when the requested page directly follows
	 * the page described by the {@link KeysetPage}, it uses the keyset predicate.
	 */
	private List<PurchaseOrder> blazeKeyset(int pageIndex, PageBoundary previous, boolean withCount) {
		KeysetPage keysetPage = null;
		if (previous != null) {
			Keyset key = new DefaultKeyset(new Serializable[] { previous.createdAt(), previous.id() });
			keysetPage = new DefaultKeysetPage((pageIndex - 1) * PAGE_SIZE, PAGE_SIZE, key, key);
		}
		return cbf.create(em, PurchaseOrder.class)
			.orderByDesc("createdAt")
			.orderByDesc("id")
			.page(keysetPage, pageIndex * PAGE_SIZE, PAGE_SIZE)
			.withCountQuery(withCount)
			.getResultList();
	}

}
