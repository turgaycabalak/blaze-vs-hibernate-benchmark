package dev.blazebench.projection;

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
 * Every strategy must build the same {@link OrderSummary} list. Smoke dataset: 1000 orders.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("smoke")
class ProjectionScenariosTest {

	@Autowired
	ProjectionQueries queries;

	@ParameterizedTest
	@ValueSource(ints = { 10, 50, 200 })
	void allScenariosBuildTheSameSummaries(int pageSize) {
		List<OrderSummary> expected = queries.fetch(ProjectionScenario.HQL_DTO, pageSize);
		assertThat(expected).hasSize(pageSize);
		assertThat(expected).allSatisfy(o -> {
			assertThat(o.lines()).isNotEmpty();
			assertThat(o.customerName()).startsWith("Customer ");
			assertThat(o.lines()).allSatisfy(l -> assertThat(l.productName()).startsWith("Product "));
		});

		for (ProjectionScenario scenario : ProjectionScenario.values()) {
			assertThat(queries.fetch(scenario, pageSize)).as(scenario.getId()).containsExactlyElementsOf(expected);
		}
	}

	@Test
	void printGeneratedSql() {
		for (ProjectionScenario scenario : ProjectionScenario.values()) {
			List<String> statements = SqlCapture.capture(() -> queries.fetch(scenario, 20));
			System.out.println("== " + scenario.getId() + " (" + statements.size() + " statements)");
			statements.stream().limit(3).forEach(sql -> System.out.println("   " + sql));
		}
	}

}
