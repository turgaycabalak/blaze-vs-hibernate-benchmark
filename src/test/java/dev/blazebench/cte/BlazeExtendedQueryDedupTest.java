package dev.blazebench.cte;

import com.blazebit.persistence.CriteriaBuilder;
import com.blazebit.persistence.CriteriaBuilderFactory;
import com.blazebit.persistence.FullSelectCTECriteriaBuilder;
import dev.blazebench.TestcontainersConfiguration;
import dev.blazebench.domain.Customer;
import dev.blazebench.domain.PurchaseOrder;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Documents a Blaze-Persistence 1.6.20 behaviour found while benchmarking (Hibernate 7.2 and 7.4 alike):
 * queries with a CTE or a FROM subquery run through Blaze's HibernateExtendedQuerySupport, which asks
 * Hibernate for {@code UniqueSemantic.FILTER}. Hibernate then removes result rows that are equal to an
 * earlier row, comparing each row with all previous ones:
 * <ul>
 * <li>legitimately equal rows are silently dropped (wrong results), and</li>
 * <li>the time grows quadratically with the number of rows (see results/latest-order/blaze-profile.txt).</li>
 * </ul>
 * If this test fails after a Blaze upgrade, the behaviour changed: re-run LatestOrderBenchmark.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("smoke")
@Transactional
class BlazeExtendedQueryDedupTest {

	/** Smoke dataset: 100 customers; their countries cycle through 8 values. */
	private static final int CUSTOMERS = 100;

	private static final int COUNTRIES = 8;

	@Autowired
	EntityManager em;

	@Autowired
	CriteriaBuilderFactory cbf;

	@Test
	void hqlReturnsOneRowPerCustomer() {
		List<String> countries = em.createQuery("""
				select c.country
				from (
				    select o.customer.id as customerId,
				           row_number() over (partition by o.customer.id order by o.createdAt desc, o.id desc) as rn
				    from PurchaseOrder o
				) l
				join Customer c on c.id = l.customerId
				where l.rn = 1
				order by l.customerId""", String.class).getResultList();

		assertThat(countries).hasSize(CUSTOMERS);
	}

	@Test
	void blazeFromSubqueryDropsEqualRows() {
		List<String> countries = countryOfEachCustomer(
				bindLatestOrder(cbf.create(em, String.class).fromSubquery(LatestOrderCte.class, "l")));

		assertThat(countries).as("expected one row per customer, Blaze 1.6.20 de-duplicates")
			.hasSize(COUNTRIES)
			.doesNotHaveDuplicates();
	}

	@Test
	void blazeCteDropsEqualRows() {
		List<String> countries = countryOfEachCustomer(
				bindLatestOrder(cbf.create(em, String.class).with(LatestOrderCte.class, false))
					.from(LatestOrderCte.class, "l"));

		assertThat(countries).as("expected one row per customer, Blaze 1.6.20 de-duplicates")
			.hasSize(COUNTRIES)
			.doesNotHaveDuplicates();
	}

	private static <X> X bindLatestOrder(FullSelectCTECriteriaBuilder<X> body) {
		return body.from(PurchaseOrder.class, "o")
			.bind("orderId").select("o.id")
			.bind("customerId").select("o.customer.id")
			.bind("createdAt").select("o.createdAt")
			.bind("totalAmount").select("o.totalAmount")
			.bind("rowNumber")
			.select("ROW_NUMBER() OVER (PARTITION BY o.customer.id ORDER BY o.createdAt DESC, o.id DESC)")
			.end();
	}

	private static List<String> countryOfEachCustomer(CriteriaBuilder<String> cb) {
		return cb.innerJoinOn(Customer.class, "c").on("c.id").eqExpression("l.customerId").end()
			.where("l.rowNumber").eq(1L)
			.select("c.country")
			.orderByAsc("l.customerId")
			.getResultList();
	}

}
