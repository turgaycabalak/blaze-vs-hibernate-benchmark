package dev.blazebench.pagination;

import dev.blazebench.TestcontainersConfiguration;
import dev.blazebench.domain.PurchaseOrder;
import dev.blazebench.support.SqlCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static dev.blazebench.pagination.PaginationQueries.PAGE_SIZE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A benchmark is only meaningful if every strategy returns the same rows.
 * Smoke dataset: 1000 orders = 50 pages.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("smoke")
class PaginationScenariosTest {

	@Autowired
	PaginationQueries queries;

	@ParameterizedTest
	@ValueSource(ints = { 0, 1, 2, 25, 49 })
	void allScenariosReturnTheSamePage(int pageIndex) {
		PageBoundary previous = pageIndex == 0 ? null : queries.lastRowOf(pageIndex - 1);
		List<Long> expected = ids(queries.fetch(PaginationScenario.JPA_OFFSET, pageIndex, null));
		assertThat(expected).hasSize(PAGE_SIZE);

		for (PaginationScenario scenario : PaginationScenario.values()) {
			assertThat(ids(queries.fetch(scenario, pageIndex, previous)))
				.as(scenario.getId())
				.containsExactlyElementsOf(expected);
		}
	}

	@Test
	void printGeneratedSql() {
		PageBoundary previous = queries.lastRowOf(24);
		for (PaginationScenario scenario : PaginationScenario.values()) {
			List<String> statements = SqlCapture.capture(() -> queries.fetch(scenario, 25, previous));
			System.out.println("== " + scenario.getId());
			statements.forEach(sql -> System.out.println("   " + sql));
		}
	}

	private static List<Long> ids(List<PurchaseOrder> orders) {
		return orders.stream().map(PurchaseOrder::getId).toList();
	}

}
