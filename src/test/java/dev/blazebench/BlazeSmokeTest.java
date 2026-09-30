package dev.blazebench;

import com.blazebit.persistence.CriteriaBuilder;
import com.blazebit.persistence.CriteriaBuilderFactory;
import com.blazebit.persistence.PagedList;
import com.blazebit.persistence.PaginatedCriteriaBuilder;
import com.blazebit.persistence.view.EntityViewManager;
import com.blazebit.persistence.view.EntityViewSetting;
import dev.blazebench.blaze.OrderSummaryView;
import dev.blazebench.blaze.OrderSummaryViewRepository;
import dev.blazebench.domain.OrderStatus;
import dev.blazebench.domain.PurchaseOrder;
import dev.blazebench.jpa.PurchaseOrderRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that Blaze-Persistence 1.6.20 works with Spring Boot 4.1 / Hibernate 7.4 /
 * Spring Data 4.1 before any benchmark is built on top of it.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("smoke")
@Transactional
class BlazeSmokeTest {

	@Autowired
	CriteriaBuilderFactory cbf;

	@Autowired
	EntityViewManager evm;

	@Autowired
	EntityManager em;

	@Autowired
	PurchaseOrderRepository jpaRepository;

	@Autowired
	OrderSummaryViewRepository blazeRepository;

	@Test
	void seedIsDeterministic() {
		assertThat(jpaRepository.count()).isEqualTo(1000);
		Long lines = em.createQuery("select count(l) from OrderLine l", Long.class).getSingleResult();
		assertThat(lines).isEqualTo(3000);
	}

	@Test
	void keysetPageMatchesOffsetPage() {
		PagedList<PurchaseOrder> first = newestFirst().page(0, 20).withKeysetExtraction(true).getResultList();

		PaginatedCriteriaBuilder<PurchaseOrder> keysetQuery = newestFirst().page(first.getKeysetPage(), 20, 20);
		System.out.println("Keyset JPQL: " + keysetQuery.getQueryString());
		PagedList<PurchaseOrder> secondByKeyset = keysetQuery.getResultList();

		List<PurchaseOrder> secondByOffset = em
			.createQuery("select o from PurchaseOrder o order by o.createdAt desc, o.id desc", PurchaseOrder.class)
			.setFirstResult(20)
			.setMaxResults(20)
			.getResultList();

		assertThat(secondByKeyset).extracting(PurchaseOrder::getId)
			.containsExactlyElementsOf(secondByOffset.stream().map(PurchaseOrder::getId).toList());
	}

	@Test
	void entityViewProjectsPagedResult() {
		PagedList<OrderSummaryView> views = evm
			.applySetting(EntityViewSetting.create(OrderSummaryView.class, 0, 10), newestFirst())
			.getResultList();

		assertThat(views).hasSize(10);
		assertThat(views.getTotalSize()).isEqualTo(1000);
		assertThat(views.getFirst().getCustomerName()).startsWith("Customer ");
	}

	@Test
	void blazeSpringDataRepositoryWorks() {
		assertThat(blazeRepository.count()).isEqualTo(1000);
		assertThat(blazeRepository.findByStatus(OrderStatus.PAID))
			.isNotEmpty()
			.allMatch(v -> v.getStatus() == OrderStatus.PAID);

		Page<OrderSummaryView> page = blazeRepository.findAll(
				(root, query, cb) -> cb.equal(root.get("status"), OrderStatus.SHIPPED),
				PageRequest.of(1, 5, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
		assertThat(page.getContent()).hasSize(5).allMatch(v -> v.getStatus() == OrderStatus.SHIPPED);
		assertThat(page.getTotalElements()).isEqualTo(200);
	}

	private CriteriaBuilder<PurchaseOrder> newestFirst() {
		return cbf.create(em, PurchaseOrder.class).orderByDesc("createdAt").orderByDesc("id");
	}

}
