package dev.blazebench.bench;

import dev.blazebench.collectionfetch.CollectionFetchQueries;
import dev.blazebench.collectionfetch.CollectionFetchScenario;
import dev.blazebench.collectionfetch.OrderWithLines;
import dev.blazebench.support.SqlCapture;
import org.hibernate.Version;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static dev.blazebench.collectionfetch.CollectionFetchQueries.PAGE_SIZE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Newest 20 orders with their lines" while the number of orders matching the filter grows.
 * In-memory paging scales with the matching set; SQL-side paging does not.
 * <p>
 * Run with {@code mvn test -Pbenchmark -Dtest=CollectionFetchBenchmark}, and again with
 * {@code -Phibernate72} added for the Hibernate 7.2 (Spring Boot 4.0) comparison.
 * <p>
 * Besides latency, it records per request the bytes allocated by the calling thread (in-memory paging
 * shows up here first) and the number of SQL statements (N+1 shows up here).
 * Output: results/collection-fetch[suffix]/raw.csv, summary.csv, sql.txt, environment.txt
 */
@Tag("benchmark")
@SpringBootTest
@Import(BenchmarkContainerConfiguration.class)
class CollectionFetchBenchmark {

	/** Orders matching the filter before paging. The table has 1M orders. */
	private static final int[] MATCHING = { 1_000, 10_000, 50_000, 100_000, 250_000 };

	private static final int JIT_WARMUP_CALLS = 100;

	private static final int WARMUP_ROUNDS = 5;

	private static final int MEASURED_ROUNDS = 50;

	private static final Path OUT = Path.of("results", "collection-fetch" + System.getProperty("results.suffix", ""));

	private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory
		.getThreadMXBean();

	@Autowired
	CollectionFetchQueries queries;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	DataSource dataSource;

	record Sample(CollectionFetchScenario scenario, int matching, long nanos, long allocatedBytes) {
	}

	@Test
	void run() throws Exception {
		Files.createDirectories(OUT);
		jdbc.execute("vacuum analyze");

		Map<Integer, LocalDateTime> since = new LinkedHashMap<>();
		Map<Integer, Long> actualMatching = new LinkedHashMap<>();
		for (int n : MATCHING) {
			since.put(n, queries.sinceForNewest(n));
			actualMatching.put(n, queries.countMatching(since.get(n)));
		}

		verifyAllScenariosAgree(since);
		Map<String, Integer> statements = countStatements(since);
		writeSql(since.get(10_000));

		for (int i = 0; i < JIT_WARMUP_CALLS; i++) {
			for (CollectionFetchScenario scenario : CollectionFetchScenario.values()) {
				queries.fetch(scenario, since.get(MATCHING[0]));
			}
		}
		List<Sample> samples = measure(since);

		writeRaw(samples);
		writeSummary(samples, actualMatching, statements);
		Map<String, Object> settings = new LinkedHashMap<>();
		settings.put("benchmark.page_size", PAGE_SIZE);
		settings.put("benchmark.matching_orders", Arrays.toString(MATCHING));
		settings.put("benchmark.jit_warmup_calls_per_scenario", JIT_WARMUP_CALLS);
		settings.put("benchmark.warmup_rounds", WARMUP_ROUNDS);
		settings.put("benchmark.measured_rounds", MEASURED_ROUNDS);
		settings.put("benchmark.concurrency", "1 thread");
		EnvironmentReport.write(OUT.resolve("environment.txt"), jdbc, dataSource, settings);
		if (CollectionFetchCharts.bothRunsExist()) {
			CollectionFetchCharts.render();
		}
	}

	private void verifyAllScenariosAgree(Map<Integer, LocalDateTime> since) {
		since.forEach((n, s) -> {
			List<OrderWithLines> expected = queries.fetch(CollectionFetchScenario.TWO_STEP_IDS, s);
			assertThat(expected).hasSize(PAGE_SIZE);
			for (CollectionFetchScenario scenario : CollectionFetchScenario.values()) {
				assertThat(queries.fetch(scenario, s)).as(scenario + " matching " + n)
					.containsExactlyElementsOf(expected);
			}
		});
	}

	/** SQL statements per request; the same at every size, recorded per size anyway. */
	private Map<String, Integer> countStatements(Map<Integer, LocalDateTime> since) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		since.forEach((n, s) -> {
			for (CollectionFetchScenario scenario : CollectionFetchScenario.values()) {
				counts.put(scenario.getId() + "@" + n, SqlCapture.capture(() -> queries.fetch(scenario, s)).size());
			}
		});
		return counts;
	}

	/** Same rotation scheme as the pagination benchmark: interleaved scenarios, rotating start. */
	private List<Sample> measure(Map<Integer, LocalDateTime> since) {
		CollectionFetchScenario[] scenarios = CollectionFetchScenario.values();
		List<Sample> samples = new ArrayList<>();
		for (int n : MATCHING) {
			for (int round = 0; round < WARMUP_ROUNDS + MEASURED_ROUNDS; round++) {
				for (int i = 0; i < scenarios.length; i++) {
					CollectionFetchScenario scenario = scenarios[(round + i) % scenarios.length];
					long allocatedBefore = THREADS.getCurrentThreadAllocatedBytes();
					long start = System.nanoTime();
					List<OrderWithLines> result = queries.fetch(scenario, since.get(n));
					long elapsed = System.nanoTime() - start;
					long allocated = THREADS.getCurrentThreadAllocatedBytes() - allocatedBefore;
					if (result.size() != PAGE_SIZE) {
						throw new IllegalStateException(scenario + " returned " + result.size() + " orders");
					}
					if (round >= WARMUP_ROUNDS) {
						samples.add(new Sample(scenario, n, elapsed, allocated));
					}
				}
			}
			System.out.printf("matching %,d done%n", n);
		}
		return samples;
	}

	private void writeSql(LocalDateTime since) throws IOException {
		List<String> lines = new ArrayList<>();
		lines.add("SQL per strategy, Hibernate " + Version.getVersionString()
				+ " (10,000 matching orders; N+1 truncated after 3 statements)");
		lines.add("");
		for (CollectionFetchScenario scenario : CollectionFetchScenario.values()) {
			List<String> statements = SqlCapture.capture(() -> queries.fetch(scenario, since));
			lines.add("== " + scenario.getId() + " (" + statements.size() + " statements)");
			lines.addAll(statements.subList(0, Math.min(3, statements.size())));
			lines.add("");
		}
		Files.write(OUT.resolve("sql.txt"), lines);
	}

	private void writeRaw(List<Sample> samples) throws IOException {
		List<String> lines = new ArrayList<>();
		lines.add("scenario,matching_orders,latency_ms,allocated_mb");
		for (Sample s : samples) {
			lines.add(String.format(Locale.ROOT, "%s,%d,%.4f,%.3f", s.scenario().getId(), s.matching(),
					s.nanos() / 1_000_000.0, s.allocatedBytes() / (1024.0 * 1024.0)));
		}
		Files.write(OUT.resolve("raw.csv"), lines);
	}

	private void writeSummary(List<Sample> samples, Map<Integer, Long> actualMatching,
			Map<String, Integer> statements) throws IOException {
		List<String> lines = new ArrayList<>();
		lines.add("scenario,label,hibernate,matching_orders,actual_matching,samples,p50_ms,p95_ms,p99_ms,mean_ms,"
				+ "min_ms,max_ms,allocated_mb_p50,statements");
		for (CollectionFetchScenario scenario : CollectionFetchScenario.values()) {
			for (int n : MATCHING) {
				List<Sample> selected = samples.stream()
					.filter(s -> s.scenario() == scenario && s.matching() == n)
					.toList();
				LatencyStats st = LatencyStats.of(selected.stream().map(Sample::nanos).toList());
				long[] allocated = selected.stream().mapToLong(Sample::allocatedBytes).sorted().toArray();
				double allocatedP50 = allocated[(allocated.length - 1) / 2] / (1024.0 * 1024.0);
				lines.add(String.format(Locale.ROOT, "%s,\"%s\",%s,%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%d",
						scenario.getId(), scenario.getLabel(), Version.getVersionString(), n, actualMatching.get(n),
						st.samples(), st.p50(), st.p95(), st.p99(), st.mean(), st.min(), st.max(), allocatedP50,
						statements.get(scenario.getId() + "@" + n)));
			}
		}
		Files.write(OUT.resolve("summary.csv"), lines);
		lines.forEach(System.out::println);
	}

}
