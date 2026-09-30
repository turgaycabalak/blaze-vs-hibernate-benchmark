package dev.blazebench.bench;

import dev.blazebench.cte.LatestOrderQueries;
import dev.blazebench.cte.LatestOrderScenario;
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
import java.util.Locale;

/**
 * Explains the slow Blaze variants in {@link LatestOrderBenchmark} (100,000 customers: ~12.5 s vs ~0.9 s).
 * <ol>
 * <li>{@link #capture}: the same query 8 times on one connection, with call time and database time
 * (auto_explain). Ruled out plan caching (Blaze is slow from the first execution) and showed that the
 * database time and plan are the same for HQL and Blaze (~1 s): the rest is spent in Java.</li>
 * <li>{@link #profileBlazeResultProcessing}: stack samples of the calling thread. ~88% of samples are in
 * Hibernate's ListResultsConsumer.addUnique, i.e. in-memory de-duplication requested by Blaze
 * (UniqueSemantic.FILTER); see BlazeExtendedQueryDedupTest.</li>
 * </ol>
 * Run with {@code mvn test -Pbenchmark -Dtest=LatestOrderDiagnosticsBenchmark}.
 * Output: results/latest-order/repeated-executions.txt, blaze-profile.txt
 */
@Tag("benchmark")
@SpringBootTest
@Import(BenchmarkContainerConfiguration.class)
class LatestOrderDiagnosticsBenchmark {

	private static final long SCOPE = 100_000;

	private static final int EXECUTIONS = 8;

	private static final String MARKER = "plan-marker";

	private static final Path OUT = Path.of("results", "latest-order");

	@Autowired
	LatestOrderQueries queries;

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

		List<String> report = new ArrayList<>();
		report.add("Latest order per customer, " + SCOPE + " customers, " + EXECUTIONS
				+ " executions of the same query on one connection");
		report.add("");
		int logStart = postgres.getLogs().length();
		for (LatestOrderScenario scenario : List.of(LatestOrderScenario.HQL_CTE, LatestOrderScenario.BLAZE_CTE)) {
			List<String> times = new ArrayList<>();
			transactionTemplate.executeWithoutResult(status -> {
				em.createNativeQuery("load 'auto_explain'").executeUpdate();
				em.createNativeQuery("set local auto_explain.log_min_duration = 0").executeUpdate();
				em.createNativeQuery("select '" + MARKER + " " + scenario.getId() + "'").getSingleResult();
				for (int i = 1; i <= EXECUTIONS; i++) {
					long start = System.nanoTime();
					queries.fetch(scenario, SCOPE);
					times.add(String.format(Locale.ROOT, "#%d %.0f ms", i, (System.nanoTime() - start) / 1e6));
				}
			});
			report.add(scenario.getId() + ": " + String.join(", ", times));
		}
		report.add("");
		report.add("Plans (auto_explain, without ANALYZE), in execution order:");
		String logs = postgres.getLogs().substring(logStart);
		report.add(logs.substring(logs.lastIndexOf('\n', logs.indexOf(MARKER)) + 1));
		Files.write(OUT.resolve("repeated-executions.txt"), report);
		report.stream().limit(4).forEach(System.out::println);
	}

	/**
	 * The database time of the Blaze query equals the HQL one, yet the call takes far longer: sample the
	 * calling thread's stack while it runs to see where the Java-side time goes.
	 */
	@Test
	void profileBlazeResultProcessing() throws Exception {
		java.util.Map<String, Integer> topFrames = new java.util.TreeMap<>();
		Thread worker = new Thread(() -> queries.fetch(LatestOrderScenario.BLAZE_CTE, SCOPE));
		worker.start();
		int samples = 0;
		while (worker.isAlive()) {
			Thread.sleep(50);
			StackTraceElement[] stack = worker.getStackTrace();
			// first frames outside the JDK tell who is burning the time
			java.util.List<String> frames = java.util.Arrays.stream(stack)
				.map(f -> f.getClassName() + "." + f.getMethodName())
				.filter(f -> f.startsWith("com.blazebit") || f.startsWith("org.hibernate")
						|| f.startsWith("org.postgresql"))
				.limit(6)
				.toList();
			if (!frames.isEmpty()) {
				topFrames.merge(String.join(" <- ", frames), 1, Integer::sum);
				samples++;
			}
		}
		List<String> lines = new ArrayList<>();
		lines.add("Stack samples of the calling thread every 50 ms, blaze-cte, " + SCOPE + " customers (" + samples
				+ " samples)");
		topFrames.entrySet()
			.stream()
			.sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
			.limit(10)
			.forEach(e -> lines.add(e.getValue() + "  " + e.getKey()));
		Files.write(OUT.resolve("blaze-profile.txt"), lines);
		lines.forEach(System.out::println);
	}

}
