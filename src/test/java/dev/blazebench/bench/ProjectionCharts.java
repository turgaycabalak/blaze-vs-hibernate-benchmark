package dev.blazebench.bench;

import org.knowm.xchart.style.markers.Marker;
import org.knowm.xchart.style.markers.SeriesMarkers;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Renders results/projection/summary.csv to PNG charts. Runs at the end of {@link ProjectionBenchmark};
 * can also be re-run on its own: {@code java ... dev.blazebench.bench.ProjectionCharts}.
 */
public final class ProjectionCharts {

	private record Style(Color color, Marker marker) {
	}

	/**
	 * Legend order = this order. Validated with validate_palette.js (light) in exactly this order:
	 * CVD and normal-vision pass (an earlier order put magenta next to aqua: CVD 6.1, rejected).
	 * Aqua, yellow and magenta are below 3:1, so markers differ too and summary.csv is the table view.
	 */
	private static final Map<String, Style> STYLES = Map.of(
			"entity-lazy", new Style(Color.decode("#eb6834"), SeriesMarkers.SQUARE),
			"spring-data-projection", new Style(Color.decode("#1baf7a"), SeriesMarkers.DIAMOND),
			"entity-fetch-join", new Style(Color.decode("#eda100"), SeriesMarkers.TRIANGLE_UP),
			"entity-lazy-batch", new Style(Color.decode("#e87ba4"), SeriesMarkers.TRIANGLE_DOWN),
			"hql-dto", new Style(Color.decode("#008300"), SeriesMarkers.CROSS),
			"blaze-view-multiset", new Style(Color.decode("#4a3aa7"), SeriesMarkers.OVAL),
			"blaze-view-join", new Style(Color.decode("#2a78d6"), SeriesMarkers.CIRCLE));

	private static final List<String> ORDER = List.of("entity-lazy", "spring-data-projection", "entity-fetch-join",
			"entity-lazy-batch", "hql-dto", "blaze-view-multiset", "blaze-view-join");

	private ProjectionCharts() {
	}

	public static void main(String[] args) throws IOException {
		render(Path.of("results", "projection"));
	}

	public static void render(Path dir) throws IOException {
		List<Map<String, String>> rows = SummaryCsv.read(dir.resolve("summary.csv"));
		LineChart.render(dir.resolve("latency.png"), "Order list with nested lines: median latency by page size",
				new LineChart.Axes("Orders per page (log scale)", "#,###", "Median latency, ms (log scale)", "#,##0.#",
						1.0, 10_000.0),
				lines(rows, ORDER, "p50_ms"));
		// Statement counts are not charted: several series coincide exactly (1 and 5,001 statements),
		// which would hide lines. They are in summary.csv and read better as a table.
	}

	private static List<LineChart.Line> lines(List<Map<String, String>> rows, List<String> scenarios, String yColumn) {
		return scenarios.stream().map(scenario -> {
			Style style = STYLES.get(scenario);
			TreeMap<Double, Double> points = SummaryCsv.series(rows, scenario, "page_size", yColumn);
			return new LineChart.Line(SummaryCsv.label(rows, scenario), style.color(), style.marker(),
					points.keySet().stream().mapToDouble(Double::doubleValue).toArray(),
					points.values().stream().mapToDouble(Double::doubleValue).toArray());
		}).toList();
	}

}
