package dev.blazebench.bench;

import org.knowm.xchart.style.markers.SeriesMarkers;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Combines the Hibernate 7.4 run (results/collection-fetch) and the Hibernate 7.2 run
 * (results/collection-fetch-hibernate-7.2) into before/after charts, written to results/collection-fetch.
 * Runs at the end of {@link CollectionFetchBenchmark} once both runs exist, or on its own:
 * {@code java ... dev.blazebench.bench.CollectionFetchCharts}.
 */
public final class CollectionFetchCharts {

	public static final Path HIBERNATE_74 = Path.of("results", "collection-fetch");

	public static final Path HIBERNATE_72 = Path.of("results", "collection-fetch-hibernate-7.2");

	/**
	 * A series is one scenario in one Hibernate version; color and marker are fixed per series.
	 * Legend order orange, aqua, yellow, blue validated with validate_palette.js (light): pass;
	 * aqua and yellow are below 3:1, so markers differ too and summary.csv is the table view.
	 */
	private record SeriesSpec(Path run, String scenario, String label, Color color,
			org.knowm.xchart.style.markers.Marker marker) {
	}

	private static final List<SeriesSpec> SERIES = List.of(
			new SeriesSpec(HIBERNATE_72, "jpa-fetch-join", "join fetch + setMaxResults, Hibernate 7.2 (Boot 4.0)",
					Color.decode("#eb6834"), SeriesMarkers.SQUARE),
			new SeriesSpec(HIBERNATE_74, "jpa-fetch-join", "join fetch + setMaxResults, Hibernate 7.4 (Boot 4.1)",
					Color.decode("#1baf7a"), SeriesMarkers.DIAMOND),
			new SeriesSpec(HIBERNATE_74, "hibernate-subselect-fetch", "Subselect fetch, Hibernate 7.4",
					Color.decode("#eda100"), SeriesMarkers.TRIANGLE_UP),
			new SeriesSpec(HIBERNATE_74, "blaze-fetch", "Blaze fetch() + page(), Hibernate 7.4",
					Color.decode("#2a78d6"), SeriesMarkers.CIRCLE));

	private CollectionFetchCharts() {
	}

	public static void main(String[] args) throws IOException {
		render();
	}

	public static boolean bothRunsExist() {
		return Files.exists(HIBERNATE_74.resolve("summary.csv")) && Files.exists(HIBERNATE_72.resolve("summary.csv"));
	}

	public static void render() throws IOException {
		Map<Path, List<Map<String, String>>> runs = Map.of(
				HIBERNATE_74, SummaryCsv.read(HIBERNATE_74.resolve("summary.csv")),
				HIBERNATE_72, SummaryCsv.read(HIBERNATE_72.resolve("summary.csv")));

		LineChart.render(HIBERNATE_74.resolve("latency.png"),
				"Newest 20 orders with their lines: median latency",
				new LineChart.Axes("Orders matching the filter before paging (log scale)", "#,###",
						"Median latency, ms (log scale)", "#,##0.#", 1.0, 10_000.0),
				lines(runs, "p50_ms"));

		LineChart.render(HIBERNATE_74.resolve("memory.png"),
				"Newest 20 orders with their lines: memory allocated per request",
				new LineChart.Axes("Orders matching the filter before paging (log scale)", "#,###",
						"Allocated per request, MB (log scale)", "#,##0.#", 0.1, 10_000.0),
				lines(runs, "allocated_mb_p50"));
	}

	private static List<LineChart.Line> lines(Map<Path, List<Map<String, String>>> runs, String yColumn) {
		return SERIES.stream().map(s -> {
			TreeMap<Double, Double> points = SummaryCsv.series(runs.get(s.run()), s.scenario(), "matching_orders",
					yColumn);
			return new LineChart.Line(s.label(), s.color(), s.marker(),
					points.keySet().stream().mapToDouble(Double::doubleValue).toArray(),
					points.values().stream().mapToDouble(Double::doubleValue).toArray());
		}).toList();
	}

}
