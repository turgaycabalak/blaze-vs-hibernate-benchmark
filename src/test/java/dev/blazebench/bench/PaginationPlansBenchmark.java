package dev.blazebench.bench;

import dev.blazebench.pagination.PageBoundary;
import dev.blazebench.pagination.PaginationQueries;
import dev.blazebench.pagination.PaginationScenario;
import dev.blazebench.support.SqlCapture;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Explains the numbers of {@link PaginationBenchmark}: the exact SQL every strategy sends, and
 * EXPLAIN ANALYZE of those statements with their real bind values (via PostgreSQL's auto_explain,
 * which writes plans to the server log).
 * <p>
 * Plans are captured for a middle page as well as the last one: keyset predicates written as
 * {@code a < ? OR (a = ? AND b < ?)} look fast on the last page (few rows left), but not in the middle.
 * <p>
 * Run with {@code mvn test -Pbenchmark -Dtest=PaginationPlansBenchmark}.
 * Output: results/pagination/sql.txt, plans-page-N.txt
 */
@Tag("benchmark")
@SpringBootTest
@Import(BenchmarkContainerConfiguration.class)
class PaginationPlansBenchmark {

	private static final int[] PAGES = { 1_000, 25_000, 50_000 };

	private static final String MARKER = "plan-marker";

	private static final Path OUT = Path.of("results", "pagination");

	@Autowired
	PaginationQueries queries;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TransactionTemplate transactionTemplate;

	@Autowired
	EntityManager em;

	@Autowired
	PostgreSQLContainer postgres;

	@Test
	void capture() throws IOException {
		Files.createDirectories(OUT);
		jdbc.execute("vacuum analyze");

		writeSql(25_000, queries.lastRowOf(25_000 - 2));
		for (int page : PAGES) {
			writePlans(page, queries.lastRowOf(page - 2));
		}
	}

	private void writeSql(int page, PageBoundary previous) throws IOException {
		List<String> lines = new ArrayList<>();
		lines.add("SQL sent by each strategy for page " + page + " (bind values shown as ?)");
		lines.add("");
		for (PaginationScenario scenario : PaginationScenario.values()) {
			lines.add("== " + scenario.getId());
			lines.addAll(SqlCapture.capture(() -> queries.fetch(scenario, page - 1, previous)));
			lines.add("");
		}
		Files.write(OUT.resolve("sql.txt"), lines);
	}

	private void writePlans(int page, PageBoundary previous) throws IOException {
		int logStart = postgres.getLogs().length();
		for (PaginationScenario scenario : PaginationScenario.values()) {
			transactionTemplate.executeWithoutResult(status -> {
				em.createNativeQuery("load 'auto_explain'").executeUpdate();
				em.createNativeQuery("set local auto_explain.log_min_duration = 0").executeUpdate();
				em.createNativeQuery("set local auto_explain.log_analyze = on").executeUpdate();
				em.createNativeQuery("set local auto_explain.log_buffers = on").executeUpdate();
				em.createNativeQuery("select '" + MARKER + " " + scenario.getId() + "'").getSingleResult();
				queries.fetch(scenario, page - 1, previous);
			});
		}
		String logs = postgres.getLogs().substring(logStart);
		int start = logs.lastIndexOf('\n', logs.indexOf(MARKER)) + 1;
		Files.writeString(OUT.resolve("plans-page-" + page + ".txt"),
				"EXPLAIN ANALYZE per strategy, page " + page + " (offset " + (page - 1) * PaginationQueries.PAGE_SIZE
						+ " rows)" + System.lineSeparator() + logs.substring(start));
	}

}
