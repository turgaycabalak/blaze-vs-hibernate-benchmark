package dev.blazebench.cte;

import dev.blazebench.TestcontainersConfiguration;
import dev.blazebench.support.SqlCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every strategy must return the same latest order per customer. Smoke dataset: 100 customers,
 * 10 orders each.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("smoke")
class LatestOrderScenariosTest {

	@Autowired
	LatestOrderQueries queries;

	@ParameterizedTest
	@ValueSource(longs = { 1, 10, 100 })
	void allScenariosReturnTheSameLatestOrders(long maxCustomerId) {
		List<LatestOrder> expected = queries.fetch(LatestOrderScenario.NATIVE_DISTINCT_ON, maxCustomerId);
		assertThat(expected).hasSize((int) maxCustomerId);

		for (LatestOrderScenario scenario : LatestOrderScenario.values()) {
			assertThat(queries.fetch(scenario, maxCustomerId)).as(scenario.getId())
				.containsExactlyElementsOf(expected);
		}
	}

	@Test
	void printGeneratedSql() {
		for (LatestOrderScenario scenario : LatestOrderScenario.values()) {
			List<String> statements = SqlCapture.capture(() -> queries.fetch(scenario, 10));
			System.out.println("== " + scenario.getId() + " (" + statements.size() + " statements)");
			statements.forEach(sql -> System.out.println("   " + sql));
		}
	}

}
