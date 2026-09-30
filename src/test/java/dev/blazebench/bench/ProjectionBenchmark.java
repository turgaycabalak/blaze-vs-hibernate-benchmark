package dev.blazebench.bench;

import dev.blazebench.projection.OrderSummary;
import dev.blazebench.projection.ProjectionQueries;
import dev.blazebench.projection.ProjectionScenario;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Building an "order list" response (order fields + customer fields + nested lines with product
 * names) while the page size grows. N+1 grows with the page size; single-query strategies don't.
 * <p>
 * Run with {@code mvn test -Pbenchmark -Dtest=ProjectionBenchmark}.
 * Records latency, bytes allocated by the calling thread, and SQL statements per request.
 * Output: results/projection/raw.csv, summary.csv, sql.txt, environment.txt, PNG charts
 */
@Tag("benchmark")
@SpringBootTest
@Import(BenchmarkContainerConfiguration.class)
class ProjectionBenchmark {

	private static final int[] PAGE_SIZES = { 10, 50, 200, 1_000 };

	private static final int JIT_WARMUP_CALLS = 100;

	private static final int WARMUP_ROUNDS = 5;

	private static final int MEASURED_ROUNDS = 50;

	private static final Path OUT = Path.of("results", "projection");

	private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory
		.getThreadMXBean();

	@Autowired
	ProjectionQueries queries;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	DataSource dataSource;

	record Sample(ProjectionScenario scenario, int pageSize, long nanos, long allocatedBytes) {
	}

	@Test
	void run() throws Exception {
		Files.createDirectories(OUT);
		jdbc.execute("vacuum analyze");

		verifyAllScenariosAgree();
		Map<String, Integer> statements = countStatements();
		writeSql();

		for (int i = 0; i < JIT_WARMUP_CALLS; i++) {
			for (ProjectionScenario scenario : ProjectionScenario.values()) {
				queries.fetch(scenario, PAGE_SIZES[0]);
			}
		}
		List<Sample> samples = measure();

		writeRaw(samples);
		writeSummary(samples, statements);
		Map<String, Object> settings = new LinkedHashMap<>();
		settings.put("benchmark.page_sizes", Arrays.toString(PAGE_SIZES));
		settings.put("benchmark.jit_warmup_calls_per_scenario", JIT_WARMUP_CALLS);
		settings.put("benchmark.warmup_rounds", WARMUP_ROUNDS);
		settings.put("benchmark.measured_rounds", MEASURED_ROUNDS);
		settings.put("benchmark.concurrency", "1 thread");
		EnvironmentReport.write(OUT.resolve("environment.txt"), jdbc, dataSource, settings);
		ProjectionCharts.render(OUT);
	}

	private void verifyAllScenariosAgree() {
		for (int pageSize : PAGE_SIZES) {
			List<OrderSummary> expected = queries.fetch(ProjectionScenario.HQL_DTO, pageSize);
			assertThat(expected).hasSize(pageSize);
			for (ProjectionScenario scenario : ProjectionScenario.values()) {
				assertThat(queries.fetch(scenario, pageSize)).as(scenario + " page size " + pageSize)
					.containsExactlyElementsOf(expected);
			}
		}
	}

	private Map<String, Integer> countStatements() {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (int pageSize : PAGE_SIZES) {
			for (ProjectionScenario scenario : ProjectionScenario.values()) {
				counts.put(scenario.getId() + "@" + pageSize,
						SqlCapture.capture(() -> queries.fetch(scenario, pageSize)).size());
			}
		}
		return counts;
	}

	/** Same rotation scheme as the other benchmarks: interleaved scenarios, rotating start. */
	private List<Sample> measure() {
		ProjectionScenario[] scenarios = ProjectionScenario.values();
		List<Sample> samples = new ArrayList<>();
		for (int pageSize : PAGE_SIZES) {
			for (int round = 0; round < WARMUP_ROUNDS + MEASURED_ROUNDS; round++) {
				for (int i = 0; i < scenarios.length; i++) {
					ProjectionScenario scenario = scenarios[(round + i) % scenarios.length];
					long allocatedBefore = THREADS.getCurrentThreadAllocatedBytes();
					long start = System.nanoTime();
					List<OrderSummary> result = queries.fetch(scenario, pageSize);
					long elapsed = System.nanoTime() - start;
					long allocated = THREADS.getCurrentThreadAllocatedBytes() - allocatedBefore;
					if (result.size() != pageSize) {
						throw new IllegalStateException(scenario + " returned " + result.size() + " orders");
					}
					if (round >= WARMUP_ROUNDS) {
						samples.add(new Sample(scenario, pageSize, elapsed, allocated));
					}
				}
			}
			System.out.printf("page size %,d done%n", pageSize);
		}
		return samples;
	}

	private void writeSql() throws IOException {
		List<String> lines = new ArrayList<>();
		lines.add("SQL per strategy, Hibernate " + Version.getVersionString()
				+ " (page size 20; N+1 truncated after 3 statements)");
		lines.add("");
		for (ProjectionScenario scenario : ProjectionScenario.values()) {
			List<String> statements = SqlCapture.capture(() -> queries.fetch(scenario, 20));
			lines.add("== " + scenario.getId() + " (" + statements.size() + " statements)");
			lines.addAll(statements.subList(0, Math.min(3, statements.size())));
			lines.add("");
		}
		Files.write(OUT.resolve("sql.txt"), lines);
	}

	private void writeRaw(List<Sample> samples) throws IOException {
		List<String> lines = new ArrayList<>();
		lines.add("scenario,page_size,latency_ms,allocated_mb");
		for (Sample s : samples) {
			lines.add(String.format(Locale.ROOT, "%s,%d,%.4f,%.3f", s.scenario().getId(), s.pageSize(),
					s.nanos() / 1_000_000.0, s.allocatedBytes() / (1024.0 * 1024.0)));
		}
		Files.write(OUT.resolve("raw.csv"), lines);
	}

	private void writeSummary(List<Sample> samples, Map<String, Integer> statements) throws IOException {
		List<String> lines = new ArrayList<>();
		lines.add("scenario,label,page_size,samples,p50_ms,p95_ms,p99_ms,mean_ms,min_ms,max_ms,allocated_mb_p50,"
				+ "statements");
		for (ProjectionScenario scenario : ProjectionScenario.values()) {
			for (int pageSize : PAGE_SIZES) {
				List<Sample> selected = samples.stream()
					.filter(s -> s.scenario() == scenario && s.pageSize() == pageSize)
					.toList();
				LatencyStats st = LatencyStats.of(selected.stream().map(Sample::nanos).toList());
				long[] allocated = selected.stream().mapToLong(Sample::allocatedBytes).sorted().toArray();
				double allocatedP50 = allocated[(allocated.length - 1) / 2] / (1024.0 * 1024.0);
				lines.add(String.format(Locale.ROOT, "%s,\"%s\",%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%d",
						scenario.getId(), scenario.getLabel(), pageSize, st.samples(), st.p50(), st.p95(), st.p99(),
						st.mean(), st.min(), st.max(), allocatedP50, statements.get(scenario.getId() + "@" + pageSize)));
			}
		}
		Files.write(OUT.resolve("summary.csv"), lines);
		lines.forEach(System.out::println);
	}

}
