package dev.blazebench.collectionfetch;

import dev.blazebench.TestcontainersConfiguration;
import dev.blazebench.support.SqlCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static dev.blazebench.collectionfetch.CollectionFetchQueries.PAGE_SIZE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every strategy must return the same 20 orders with the same lines. Smoke dataset: 1000 orders.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("smoke")
class CollectionFetchScenariosTest {

	@Autowired
	CollectionFetchQueries queries;

	@ParameterizedTest
	@ValueSource(ints = { 100, 500, 1000 })
	void allScenariosReturnTheSameOrdersWithTheSameLines(int matching) {
		LocalDateTime since = queries.sinceForNewest(matching);
		List<OrderWithLines> expected = queries.fetch(CollectionFetchScenario.TWO_STEP_IDS, since);
		assertThat(expected).hasSize(PAGE_SIZE);
		assertThat(expected).allSatisfy(o -> assertThat(o.lineIds()).isNotEmpty());

		for (CollectionFetchScenario scenario : CollectionFetchScenario.values()) {
			assertThat(queries.fetch(scenario, since)).as(scenario.getId()).containsExactlyElementsOf(expected);
		}
	}

	@Test
	void printGeneratedSql() {
		LocalDateTime since = queries.sinceForNewest(500);
		for (CollectionFetchScenario scenario : CollectionFetchScenario.values()) {
			List<String> statements = SqlCapture.capture(() -> queries.fetch(scenario, since));
			System.out.println("== " + scenario.getId() + " (" + statements.size() + " statements)");
			statements.stream().limit(3).forEach(sql -> System.out.println("   " + sql));
		}
	}

}
