package dev.blazebench.collectionfetch;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Ways to load "the newest 20 matching orders, each with all its order lines".
 * Every scenario must return the same orders with the same lines.
 */
@Getter
@RequiredArgsConstructor
public enum CollectionFetchScenario {

	/** Paged in memory before Hibernate 7.4 (HHH90003004), in SQL from 7.4 on. */
	JPA_FETCH_JOIN("jpa-fetch-join", "JPA join fetch + setMaxResults"),
	SPRING_DATA_ENTITY_GRAPH("spring-data-entity-graph", "Spring Data @EntityGraph + Pageable"),
	LAZY_N_PLUS_ONE("lazy-n-plus-1", "Lazy loading (N+1)"),
	HIBERNATE_BATCH_FETCH("hibernate-batch-fetch", "Hibernate batch fetch (size 20)"),
	HIBERNATE_SUBSELECT_FETCH("hibernate-subselect-fetch", "Hibernate subselect fetch"),
	TWO_STEP_IDS("two-step-ids", "Hand-written: page ids, then join fetch"),
	BLAZE_FETCH("blaze-fetch", "Blaze fetch() + page()");

	private final String id;

	private final String label;

}
