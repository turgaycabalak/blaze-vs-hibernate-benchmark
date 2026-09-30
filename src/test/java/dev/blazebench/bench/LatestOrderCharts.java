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
 * Renders results/latest-order/summary.csv. Runs at the end of {@link LatestOrderBenchmark};
 * can also be re-run on its own: {@code java ... dev.blazebench.bench.LatestOrderCharts}.
 */
public final class LatestOrderCharts {

	private record Style(Color color, Marker marker) {
	}

	/**
	 * Legend order = this order: orange, aqua, yellow, magenta, green, blue, the same six-color
	 * sequence already validated with validate_palette.js (light) for the pagination chart.
	 */
	private static final List<String> ORDER = List.of("jpql-not-exists", "native-distinct-on", "hql-derived-table",
			"hql-cte", "blaze-from-subquery", "blaze-cte");

	private static final Map<String, Style> STYLES = Map.of(
			"jpql-not-exists", new Style(Color.decode("#eb6834"), SeriesMarkers.SQUARE),
			"native-distinct-on", new Style(Color.decode("#1baf7a"), SeriesMarkers.DIAMOND),
			"hql-derived-table", new Style(Color.decode("#eda100"), SeriesMarkers.TRIANGLE_UP),
			"hql-cte", new Style(Color.decode("#e87ba4"), SeriesMarkers.TRIANGLE_DOWN),
			"blaze-from-subquery", new Style(Color.decode("#008300"), SeriesMarkers.CROSS),
			"blaze-cte", new Style(Color.decode("#2a78d6"), SeriesMarkers.CIRCLE));

	private LatestOrderCharts() {
	}

	public static void main(String[] args) throws IOException {
		render(Path.of("results", "latest-order"));
	}

	public static void render(Path dir) throws IOException {
		List<Map<String, String>> rows = SummaryCsv.read(dir.resolve("summary.csv"));
		List<LineChart.Line> lines = ORDER.stream().map(scenario -> {
			Style style = STYLES.get(scenario);
			TreeMap<Double, Double> points = SummaryCsv.series(rows, scenario, "customers", "p50_ms");
			return new LineChart.Line(SummaryCsv.label(rows, scenario), style.color(), style.marker(),
					points.keySet().stream().mapToDouble(Double::doubleValue).toArray(),
					points.values().stream().mapToDouble(Double::doubleValue).toArray());
		}).toList();
		LineChart.render(dir.resolve("latency.png"), "Latest order per customer: median latency",
				new LineChart.Axes("Customers in scope, 10 orders each (log scale)", "#,###",
						"Median latency, ms (log scale)", "#,##0.#", 0.1, 10_000.0),
				lines);
	}

}
