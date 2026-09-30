package dev.blazebench.cte;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Ways to answer "the latest order of every customer in scope" (greatest-n-per-group).
 */
@Getter
@RequiredArgsConstructor
public enum LatestOrderScenario {

	JPQL_NOT_EXISTS("jpql-not-exists", "Portable JPQL, NOT EXISTS (no window functions)"),
	HQL_DERIVED_TABLE("hql-derived-table", "HQL: window function in a FROM subquery"),
	HQL_CTE("hql-cte", "HQL: window function in a CTE"),
	NATIVE_DISTINCT_ON("native-distinct-on", "Native SQL: DISTINCT ON (PostgreSQL only)"),
	BLAZE_CTE("blaze-cte", "Blaze: window function in a CTE"),
	BLAZE_FROM_SUBQUERY("blaze-from-subquery", "Blaze: window function in a FROM subquery");

	private final String id;

	private final String label;

}
