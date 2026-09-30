package dev.blazebench.bench;

import dev.blazebench.domain.PurchaseOrder;
import dev.blazebench.pagination.PageBoundary;
import dev.blazebench.pagination.PaginationQueries;
import dev.blazebench.pagination.PaginationScenario;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static dev.blazebench.pagination.PaginationQueries.PAGE_SIZE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deep-page latency of every {@link PaginationScenario} on the full dataset (1M orders).
 * <p>
 * Run with {@code mvn test -Pbenchmark}. Single-threaded on purpose: this measures the cost of one
 * query strategy, not throughput under load. Each sample is the time the application waits for
 * {@link PaginationQueries#fetch}: transaction, SQL, and entity hydration included.
 * <p>
 * Output (results/pagination/): raw.csv, summary.csv, environment.txt and the PNG charts.
 * Why the numbers look the way they do: {@link PaginationPlansBenchmark}.
 */
@Tag("benchmark")
@SpringBootTest
@Import(BenchmarkContainerConfiguration.class)
class PaginationBenchmark {

	/** 1-based page numbers. 1M orders / 20 per page = 50,000 pages. */
	private static final int[] PAGES = { 1, 10, 100, 1_000, 10_000, 25_000, 50_000 };

	private static final int JIT_WARMUP_CALLS = 200;

	private static final int WARMUP_ROUNDS = 10;

	private static final int MEASURED_ROUNDS = 100;

	private static final Path OUT = Path.of("results", "pagination");

	@Autowired
	PaginationQueries queries;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	DataSource dataSource;

	record Sample(PaginationScenario scenario, int page, int round, long nanos) {
	}

	@Test
	void run() throws Exception {
		Files.createDirectories(OUT);
		// A freshly bulk-loaded table has no visibility map and unset hint bits; production tables don't.
		jdbc.execute("vacuum analyze");

		Map<Integer, PageBoundary> previousPage = new LinkedHashMap<>();
		for (int page : PAGES) {
			previousPage.put(page, page == 1 ? null : queries.lastRowOf(page - 2));
		}

		verifyAllScenariosAgree(previousPage);
		jitWarmup(previousPage);
		List<Sample> samples = measure(previousPage);

		writeRaw(samples);
		writeSummary(samples);
		EnvironmentReport.write(OUT.resolve("environment.txt"), jdbc, dataSource, benchmarkSettings());
		PaginationCharts.render(OUT, PaginationCharts.defaultCharts());
	}

	private void verifyAllScenariosAgree(Map<Integer, PageBoundary> previousPage) {
		previousPage.forEach((page, previous) -> {
			List<Long> expected = ids(queries.fetch(PaginationScenario.JPA_OFFSET, page - 1, null));
			assertThat(expected).hasSize(PAGE_SIZE);
			for (PaginationScenario scenario : PaginationScenario.values()) {
				assertThat(ids(queries.fetch(scenario, page - 1, previous))).as(scenario + " page " + page)
					.containsExactlyElementsOf(expected);
			}
		});
	}

	/** Get every code path compiled before anything is recorded. Shallow page, so it's cheap. */
	private void jitWarmup(Map<Integer, PageBoundary> previousPage) {
		int page = 10;
		for (int i = 0; i < JIT_WARMUP_CALLS; i++) {
			for (PaginationScenario scenario : PaginationScenario.values()) {
				queries.fetch(scenario, page - 1, previousPage.get(page));
			}
		}
	}

	/**
	 * Scenarios are interleaved round by round, and the starting scenario rotates each round,
	 * so background noise (GC, OS, Docker) spreads evenly instead of hitting one scenario.
	 */
	private List<Sample> measure(Map<Integer, PageBoundary> previousPage) {
		PaginationScenario[] scenarios = PaginationScenario.values();
		List<Sample> samples = new ArrayList<>();
		for (int page : PAGES) {
			PageBoundary previous = previousPage.get(page);
			for (int round = 0; round < WARMUP_ROUNDS + MEASURED_ROUNDS; round++) {
				for (int i = 0; i < scenarios.length; i++) {
					PaginationScenario scenario = scenarios[(round + i) % scenarios.length];
					long start = System.nanoTime();
					List<PurchaseOrder> result = queries.fetch(scenario, page - 1, previous);
					long elapsed = System.nanoTime() - start;
					if (result.size() != PAGE_SIZE) {
						throw new IllegalStateException(scenario + " returned " + result.size() + " rows");
					}
					if (round >= WARMUP_ROUNDS) {
						samples.add(new Sample(scenario, page, round - WARMUP_ROUNDS, elapsed));
					}
				}
			}
			System.out.printf("page %,d done%n", page);
		}
		return samples;
	}

	private void writeRaw(List<Sample> samples) throws IOException {
		List<String> lines = new ArrayList<>();
		lines.add("scenario,page,round,latency_ms");
		for (Sample s : samples) {
			lines.add(String.format(Locale.ROOT, "%s,%d,%d,%.4f", s.scenario().getId(), s.page(), s.round(),
					s.nanos() / 1_000_000.0));
		}
		Files.write(OUT.resolve("raw.csv"), lines);
	}

	private void writeSummary(List<Sample> samples) throws IOException {
		List<String> lines = new ArrayList<>();
		lines.add("scenario,label,page,offset_rows,samples,p50_ms,p95_ms,p99_ms,mean_ms,min_ms,max_ms");
		for (PaginationScenario scenario : PaginationScenario.values()) {
			for (int page : PAGES) {
				List<Long> nanos = samples.stream()
					.filter(s -> s.scenario() == scenario && s.page() == page)
					.map(Sample::nanos)
					.toList();
				LatencyStats st = LatencyStats.of(nanos);
				lines.add(String.format(Locale.ROOT, "%s,\"%s\",%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f",
						scenario.getId(), scenario.getLabel(), page, (page - 1) * PAGE_SIZE, st.samples(), st.p50(),
						st.p95(), st.p99(), st.mean(), st.min(), st.max()));
			}
		}
		Files.write(OUT.resolve("summary.csv"), lines);
		lines.forEach(System.out::println);
	}

	private Map<String, Object> benchmarkSettings() {
		Map<String, Object> settings = new LinkedHashMap<>();
		settings.put("benchmark.page_size", PAGE_SIZE);
		settings.put("benchmark.pages", java.util.Arrays.toString(PAGES));
		settings.put("benchmark.jit_warmup_calls_per_scenario", JIT_WARMUP_CALLS);
		settings.put("benchmark.warmup_rounds_per_page", WARMUP_ROUNDS);
		settings.put("benchmark.measured_rounds_per_page", MEASURED_ROUNDS);
		settings.put("benchmark.concurrency", "1 thread");
		return settings;
	}

	private static List<Long> ids(List<PurchaseOrder> orders) {
		return orders.stream().map(PurchaseOrder::getId).toList();
	}

}
