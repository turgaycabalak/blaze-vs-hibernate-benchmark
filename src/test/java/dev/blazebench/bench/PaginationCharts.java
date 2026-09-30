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
 * Renders results/pagination/summary.csv to PNG charts for the article.
 * Runs at the end of {@link PaginationBenchmark}; can also be re-run on its own from the CSV:
 * {@code java ... dev.blazebench.bench.PaginationCharts}.
 */
public final class PaginationCharts {

	/**
	 * Color and marker follow the scenario, never its position in a chart, so "Blaze keyset" looks
	 * the same in every image. Colors are the reference categorical palette in slot order
	 * (blue, orange, aqua, yellow, magenta, green, violet, red), validated with validate_palette.js (light):
	 * CVD and normal-vision separation pass. Aqua, yellow and magenta are below 3:1 on the surface,
	 * so identity also rides on marker shape, and summary.csv is the table view.
	 */
	private static final Map<String, Style> STYLES = Map.of(
			"blaze-keyset", new Style(Color.decode("#2a78d6"), SeriesMarkers.CIRCLE),
			"spring-data-page", new Style(Color.decode("#eb6834"), SeriesMarkers.SQUARE),
			"jpa-offset", new Style(Color.decode("#1baf7a"), SeriesMarkers.DIAMOND),
			"spring-data-keyset", new Style(Color.decode("#eda100"), SeriesMarkers.TRIANGLE_UP),
			"hibernate-keyset", new Style(Color.decode("#e87ba4"), SeriesMarkers.TRIANGLE_DOWN),
			"hql-row-value-keyset", new Style(Color.decode("#008300"), SeriesMarkers.CROSS),
			"blaze-offset", new Style(Color.decode("#e34948"), SeriesMarkers.OVAL),
			"blaze-keyset-with-count", new Style(Color.decode("#4a3aa7"), SeriesMarkers.SQUARE));

	// Same fixed y range in every chart: comparable across images, decade labels 1 / 10 / 100 / 1,000.
	private static final LineChart.Axes AXES = new LineChart.Axes("Page number (log scale)", "#,###",
			"Median latency, ms (log scale)", "#,##0.#", 1.0, 1000.0);

	private record Style(Color color, Marker marker) {
	}

	private PaginationCharts() {
	}

	/** One chart = a title and the scenario ids to plot (legend order). */
	public record ChartSpec(String file, String title, List<String> scenarios) {
	}

	public static void main(String[] args) throws IOException {
		render(Path.of("results", "pagination"), defaultCharts());
	}

	public static List<ChartSpec> defaultCharts() {
		return List.of(
				new ChartSpec("offset-vs-keyset.png", "1M orders, newest first, 20 per page: median latency by page",
						List.of("spring-data-page", "jpa-offset", "spring-data-keyset", "hibernate-keyset",
								"hql-row-value-keyset", "blaze-keyset")),
				new ChartSpec("blaze-count-trap.png", "Blaze keyset: default COUNT vs withCountQuery(false)",
						List.of("blaze-keyset-with-count", "blaze-keyset")));
	}

	public static void render(Path dir, List<ChartSpec> charts) throws IOException {
		List<Map<String, String>> rows = SummaryCsv.read(dir.resolve("summary.csv"));
		for (ChartSpec spec : charts) {
			List<LineChart.Line> lines = spec.scenarios().stream().map(scenario -> {
				Style style = STYLES.get(scenario);
				TreeMap<Double, Double> points = SummaryCsv.series(rows, scenario, "page", "p50_ms");
				return new LineChart.Line(SummaryCsv.label(rows, scenario), style.color(), style.marker(),
						points.keySet().stream().mapToDouble(Double::doubleValue).toArray(),
						points.values().stream().mapToDouble(Double::doubleValue).toArray());
			}).toList();
			LineChart.render(dir.resolve(spec.file()), spec.title(), AXES, lines);
		}
	}

}
