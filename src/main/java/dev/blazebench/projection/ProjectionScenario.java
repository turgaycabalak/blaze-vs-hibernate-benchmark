package dev.blazebench.projection;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Ways to build the same {@link OrderSummary} page: order fields, customer fields and nested lines
 * with product names.
 */
@Getter
@RequiredArgsConstructor
public enum ProjectionScenario {

	ENTITY_FETCH_JOIN("entity-fetch-join", "Entities with join fetch, mapped in Java"),
	ENTITY_LAZY("entity-lazy", "Entities, lazy associations, mapped in Java (N+1)"),
	ENTITY_LAZY_BATCH("entity-lazy-batch", "Entities, lazy + batch fetch, mapped in Java"),
	SPRING_DATA_PROJECTION("spring-data-projection", "Spring Data interface projection"),
	HQL_DTO("hql-dto", "Hand-written HQL DTO queries (2 queries)"),
	BLAZE_VIEW_JOIN("blaze-view-join", "Blaze Entity View (JOIN)"),
	BLAZE_VIEW_MULTISET("blaze-view-multiset", "Blaze Entity View (MULTISET)");

	private final String id;

	private final String label;

}
