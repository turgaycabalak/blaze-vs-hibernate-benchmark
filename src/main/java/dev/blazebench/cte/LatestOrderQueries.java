package dev.blazebench.cte;

import com.blazebit.persistence.CriteriaBuilder;
import com.blazebit.persistence.CriteriaBuilderFactory;
import com.blazebit.persistence.FullSelectCTECriteriaBuilder;
import dev.blazebench.domain.Customer;
import dev.blazebench.domain.PurchaseOrder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The latest order (by createdAt, then id) of every customer with {@code id <= maxCustomerId},
 * ordered by customer id. Each customer has exactly 10 orders in the seed, so the window
 * function runs over 10 x maxCustomerId orders.
 */
@Service
@RequiredArgsConstructor
public class LatestOrderQueries {

	private static final String ROW_NUMBER = "ROW_NUMBER() OVER (PARTITION BY o.customer.id ORDER BY o.createdAt DESC, o.id DESC)";

	private final EntityManager em;

	private final CriteriaBuilderFactory cbf;

	@Transactional(readOnly = true)
	public List<LatestOrder> fetch(LatestOrderScenario scenario, long maxCustomerId) {
		return switch (scenario) {
			case JPQL_NOT_EXISTS -> jpqlNotExists(maxCustomerId);
			case HQL_DERIVED_TABLE -> hqlDerivedTable(maxCustomerId);
			case HQL_CTE -> hqlCte(maxCustomerId);
			case NATIVE_DISTINCT_ON -> nativeDistinctOn(maxCustomerId);
			case BLAZE_CTE -> blazeCte(maxCustomerId);
			case BLAZE_FROM_SUBQUERY -> blazeFromSubquery(maxCustomerId);
		};
	}

	/** What JPA 2 code had to do: an anti-join ("no newer order of the same customer exists"). */
	private List<LatestOrder> jpqlNotExists(long maxCustomerId) {
		return em.createQuery("""
				select new dev.blazebench.cte.LatestOrder(c.id, c.name, o.id, o.createdAt, o.totalAmount)
				from PurchaseOrder o join o.customer c
				where c.id <= :max
				  and not exists (
				      select 1 from PurchaseOrder o2
				      where o2.customer = o.customer
				        and (o2.createdAt > o.createdAt or (o2.createdAt = o.createdAt and o2.id > o.id)))
				order by c.id""", LatestOrder.class)
			.setParameter("max", maxCustomerId)
			.getResultList();
	}

	/** Hibernate 6.1+ subquery in FROM, 6.2+ window functions. */
	private List<LatestOrder> hqlDerivedTable(long maxCustomerId) {
		return em.createQuery("""
				select new dev.blazebench.cte.LatestOrder(l.customerId, c.name, l.orderId, l.createdAt, l.totalAmount)
				from (
				    select o.customer.id as customerId, o.id as orderId, o.createdAt as createdAt,
				           o.totalAmount as totalAmount,
				           row_number() over (partition by o.customer.id order by o.createdAt desc, o.id desc) as rn
				    from PurchaseOrder o
				    where o.customer.id <= :max
				) l
				join Customer c on c.id = l.customerId
				where l.rn = 1
				order by l.customerId""", LatestOrder.class)
			.setParameter("max", maxCustomerId)
			.getResultList();
	}

	/** Hibernate 6.2+ CTE. */
	private List<LatestOrder> hqlCte(long maxCustomerId) {
		return em.createQuery("""
				with latest as (
				    select o.customer.id as customerId, o.id as orderId, o.createdAt as createdAt,
				           o.totalAmount as totalAmount,
				           row_number() over (partition by o.customer.id order by o.createdAt desc, o.id desc) as rn
				    from PurchaseOrder o
				    where o.customer.id <= :max
				)
				select new dev.blazebench.cte.LatestOrder(l.customerId, c.name, l.orderId, l.createdAt, l.totalAmount)
				from latest l
				join Customer c on c.id = l.customerId
				where l.rn = 1
				order by l.customerId""", LatestOrder.class)
			.setParameter("max", maxCustomerId)
			.getResultList();
	}

	/** The PostgreSQL-specific idiom; the reference for "just write SQL". */
	@SuppressWarnings("unchecked")
	private List<LatestOrder> nativeDistinctOn(long maxCustomerId) {
		List<Object[]> rows = em.createNativeQuery("""
				select distinct on (o.customer_id) o.customer_id, c.name, o.id, o.created_at, o.total_amount
				from purchase_order o
				join customer c on c.id = o.customer_id
				where o.customer_id <= ?1
				order by o.customer_id, o.created_at desc, o.id desc""")
			.setParameter(1, maxCustomerId)
			.getResultList();
		return rows.stream()
			.map(r -> new LatestOrder(((Number) r[0]).longValue(), (String) r[1], ((Number) r[2]).longValue(),
					toLocalDateTime(r[3]), (BigDecimal) r[4]))
			.toList();
	}

	/**
	 * {@code with(cte)} alone lets Blaze inline the CTE as a FROM subquery; {@code inline = false}
	 * keeps a real WITH clause, so this scenario differs from {@link #blazeFromSubquery}.
	 */
	private List<LatestOrder> blazeCte(long maxCustomerId) {
		CriteriaBuilder<Tuple> cb = bindLatestOrderColumns(
				cbf.create(em, Tuple.class).with(LatestOrderCte.class, false), maxCustomerId)
			.from(LatestOrderCte.class, "l");
		return selectLatestOrders(cb);
	}

	private List<LatestOrder> blazeFromSubquery(long maxCustomerId) {
		CriteriaBuilder<Tuple> cb = bindLatestOrderColumns(
				cbf.create(em, Tuple.class).fromSubquery(LatestOrderCte.class, "l"), maxCustomerId);
		return selectLatestOrders(cb);
	}

	/** The CTE / subquery body: each LatestOrderCte attribute is bound to an expression. */
	private static <X> X bindLatestOrderColumns(FullSelectCTECriteriaBuilder<X> body, long maxCustomerId) {
		return body.from(PurchaseOrder.class, "o")
			.bind("orderId").select("o.id")
			.bind("customerId").select("o.customer.id")
			.bind("createdAt").select("o.createdAt")
			.bind("totalAmount").select("o.totalAmount")
			.bind("rowNumber").select(ROW_NUMBER)
			.where("o.customer.id").le(maxCustomerId)
			.end();
	}

	private static List<LatestOrder> selectLatestOrders(CriteriaBuilder<Tuple> cb) {
		return cb.innerJoinOn(Customer.class, "c").on("c.id").eqExpression("l.customerId").end()
			.where("l.rowNumber").eq(1L)
			.select("l.customerId")
			.select("c.name")
			.select("l.orderId")
			.select("l.createdAt")
			.select("l.totalAmount")
			.orderByAsc("l.customerId")
			.getResultList()
			.stream()
			.map(t -> new LatestOrder(t.get(0, Long.class), t.get(1, String.class), t.get(2, Long.class),
					t.get(3, LocalDateTime.class), t.get(4, BigDecimal.class)))
			.toList();
	}

	private static LocalDateTime toLocalDateTime(Object value) {
		return value instanceof Timestamp ts ? ts.toLocalDateTime() : (LocalDateTime) value;
	}

}
