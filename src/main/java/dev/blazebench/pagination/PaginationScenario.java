package dev.blazebench.pagination;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Eight ways to load the same "newest orders first" page. Every scenario must return
 * exactly the same rows for the same page; only the query strategy differs.
 */
@Getter
@RequiredArgsConstructor
public enum PaginationScenario {

	SPRING_DATA_PAGE("spring-data-page", "Spring Data Page (OFFSET + COUNT)", false),
	JPA_OFFSET("jpa-offset", "JPA / Hibernate OFFSET", false),
	BLAZE_OFFSET("blaze-offset", "Blaze OFFSET", false),
	SPRING_DATA_KEYSET("spring-data-keyset", "Spring Data keyset (Window)", true),
	HIBERNATE_KEYSET("hibernate-keyset", "Hibernate 7 keyset (KeyedPage)", true),
	HQL_ROW_VALUE_KEYSET("hql-row-value-keyset", "Hand-written HQL keyset (row value)", true),
	BLAZE_KEYSET("blaze-keyset", "Blaze keyset", true),
	BLAZE_KEYSET_WITH_COUNT("blaze-keyset-with-count", "Blaze keyset + COUNT (default)", true);

	private final String id;

	private final String label;

	/** Keyset scenarios need the last row of the previous page instead of an offset. */
	private final boolean keyset;

}
