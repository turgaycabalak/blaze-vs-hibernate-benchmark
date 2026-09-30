package dev.blazebench.bench;

import dev.blazebench.cte.LatestOrder;
import dev.blazebench.cte.LatestOrderQueries;
import dev.blazebench.cte.LatestOrderScenario;
import dev.blazebench.support.SqlCapture;
import jakarta.persistence.EntityManager;
import org.hibernate.Version;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Latest order of every customer" (window function + CTE / FROM subquery) while the number of
 * customers in scope grows. Every customer has 10 orders, so 100,000 customers = all 1M orders.
 * <p>
 * Run with {@code mvn test -Pbenchmark -Dtest=LatestOrderBenchmark}.
 * Output: results/latest-order/raw.csv, summary.csv, sql.txt, plans-10000.txt, environment.txt, latency.png
 */
@Tag("benchmark")
@SpringBootTest
@Import(BenchmarkContainerConfiguration.class)
class LatestOrderBenchmark {

	private static final long[] CUSTOMERS = { 100, 1_000, 10_000, 100_000 };

	private static final long PLAN_SCOPE = 10_000;

	private static final int JIT_WARMUP_CALLS = 50;

	private static final int WARMUP_ROUNDS = 5;

	private static final int MEASURED_ROUNDS = 30;

	private static final String MARKER = "plan-marker";

	private static final Path OUT = Path.of("results", "latest-order");

	private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory
		.getThreadMXBean();

	@Autowired
	LatestOrderQueries queries;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	DataSource dataSource;

	@Autowired
	TransactionTemplate transactionTemplate;

	@Autowired
	EntityManager em;

	@Autowired
	PostgreSQLContainer postgres;

	record Sample(LatestOrderScenario scenario, long customers, long nanos, long allocatedBytes) {
	}

	@Test
	void run() throws Exception {
		Files.createDirectories(OUT);
		jdbc.execute("vacuum analyze");

		verifyAllScenariosAgree();
		writeSql();

		for (int i = 0; i < JIT_WARMUP_CALLS; i++) {
			for (LatestOrderScenario scenario : LatestOrderScenario.values()) {
				queries.fetch(scenario, CUSTOMERS[0]);
			}
		}
		List<Sample> samples = measure();

		writeRaw(samples);
		writeSummary(samples);
		writePlans();
		Map<String, Object> settings = new LinkedHashMap<>();
		settings.put("benchmark.customers_in_scope", Arrays.toString(CUSTOMERS));
		settings.put("benchmark.jit_warmup_calls_per_scenario", JIT_WARMUP_CALLS);
		settings.put("benchmark.warmup_rounds", WARMUP_ROUNDS);
		settings.put("benchmark.measured_rounds", MEASURED_ROUNDS);
		settings.put("benchmark.concurrency", "1 thread");
		EnvironmentReport.write(OUT.resolve("environment.txt"), jdbc, dataSource, settings);
		LatestOrderCharts.render(OUT);
	}

	private void verifyAllScenariosAgree() {
		for (long customers : CUSTOMERS) {
			List<LatestOrder> expected = queries.fetch(LatestOrderScenario.NATIVE_DISTINCT_ON, customers);
			assertThat(expected).hasSize((int) customers);
			for (LatestOrderScenario scenario : LatestOrderScenario.values()) {
				assertThat(queries.fetch(scenario, customers)).as(scenario + " customers " + customers)
					.containsExactlyElementsOf(expected);
			}
		}
	}

	/** Same rotation scheme as the other benchmarks: interleaved scenarios, rotating start. */
	private List<Sample> measure() {
		LatestOrderScenario[] scenarios = LatestOrderScenario.values();
		List<Sample> samples = new ArrayList<>();
		for (long customers : CUSTOMERS) {
			for (int round = 0; round < WARMUP_ROUNDS + MEASURED_ROUNDS; round++) {
				for (int i = 0; i < scenarios.length; i++) {
					LatestOrderScenario scenario = scenarios[(round + i) % scenarios.length];
					long allocatedBefore = THREADS.getCurrentThreadAllocatedBytes();
					long start = System.nanoTime();
					List<LatestOrder> result = queries.fetch(scenario, customers);
					long elapsed = System.nanoTime() - start;
					long allocated = THREADS.getCurrentThreadAllocatedBytes() - allocatedBefore;
					if (result.size() != customers) {
						throw new IllegalStateException(scenario + " returned " + result.size() + " rows");
					}
					if (round >= WARMUP_ROUNDS) {
						samples.add(new Sample(scenario, customers, elapsed, allocated));
					}
				}
			}
			System.out.printf("customers %,d done%n", customers);
		}
		return samples;
	}

	private void writeSql() throws IOException {
		List<String> lines = new ArrayList<>();
		lines.add("SQL per strategy, Hibernate " + Version.getVersionString());
		lines.add("");
		for (LatestOrderScenario scenario : LatestOrderScenario.values()) {
			lines.add("== " + scenario.getId());
			lines.addAll(SqlCapture.capture(() -> queries.fetch(scenario, PLAN_SCOPE)));
			lines.add("");
		}
		Files.write(OUT.resolve("sql.txt"), lines);
	}

	/** EXPLAIN ANALYZE of the real statements via auto_explain, as in PaginationPlansBenchmark. */
	private void writePlans() throws IOException {
		int logStart = postgres.getLogs().length();
		for (LatestOrderScenario scenario : LatestOrderScenario.values()) {
			transactionTemplate.executeWithoutResult(status -> {
				em.createNativeQuery("load 'auto_explain'").executeUpdate();
				em.createNativeQuery("set local auto_explain.log_min_duration = 0").executeUpdate();
				em.createNativeQuery("set local auto_explain.log_analyze = on").executeUpdate();
				em.createNativeQuery("set local auto_explain.log_buffers = on").executeUpdate();
				em.createNativeQuery("select '" + MARKER + " " + scenario.getId() + "'").getSingleResult();
				queries.fetch(scenario, PLAN_SCOPE);
			});
		}
		String logs = postgres.getLogs().substring(logStart);
		int start = logs.lastIndexOf('\n', logs.indexOf(MARKER)) + 1;
		Files.writeString(OUT.resolve("plans-" + PLAN_SCOPE + ".txt"),
				"EXPLAIN ANALYZE per strategy, " + PLAN_SCOPE + " customers in scope" + System.lineSeparator()
						+ logs.substring(start));
	}

	private void writeRaw(List<Sample> samples) throws IOException {
		List<String> lines = new ArrayList<>();
		lines.add("scenario,customers,latency_ms,allocated_mb");
		for (Sample s : samples) {
			lines.add(String.format(Locale.ROOT, "%s,%d,%.4f,%.3f", s.scenario().getId(), s.customers(),
					s.nanos() / 1_000_000.0, s.allocatedBytes() / (1024.0 * 1024.0)));
		}
		Files.write(OUT.resolve("raw.csv"), lines);
	}

	private void writeSummary(List<Sample> samples) throws IOException {
		List<String> lines = new ArrayList<>();
		lines.add("scenario,label,customers,orders_in_scope,samples,p50_ms,p95_ms,p99_ms,mean_ms,min_ms,max_ms,"
				+ "allocated_mb_p50");
		for (LatestOrderScenario scenario : LatestOrderScenario.values()) {
			for (long customers : CUSTOMERS) {
				List<Sample> selected = samples.stream()
					.filter(s -> s.scenario() == scenario && s.customers() == customers)
					.toList();
				LatencyStats st = LatencyStats.of(selected.stream().map(Sample::nanos).toList());
				long[] allocated = selected.stream().mapToLong(Sample::allocatedBytes).sorted().toArray();
				double allocatedP50 = allocated[(allocated.length - 1) / 2] / (1024.0 * 1024.0);
				lines.add(String.format(Locale.ROOT, "%s,\"%s\",%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f",
						scenario.getId(), scenario.getLabel(), customers, customers * 10, st.samples(), st.p50(),
						st.p95(), st.p99(), st.mean(), st.min(), st.max(), allocatedP50));
			}
		}
		Files.write(OUT.resolve("summary.csv"), lines);
		lines.forEach(System.out::println);
	}

}
