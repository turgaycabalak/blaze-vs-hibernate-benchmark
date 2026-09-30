package dev.blazebench.bench;

import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
import org.knowm.xchart.XYSeries;
import org.knowm.xchart.style.Styler;
import org.knowm.xchart.style.markers.Marker;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Shared look for all article charts: light surface, hairline horizontal grid, log-log axes,
 * 2.5px lines with markers, legend top-left. Rendered at 200 DPI (3333x1875 px) for LinkedIn.
 */
public final class LineChart {

	private static final Color SURFACE = Color.decode("#fcfcfb");

	private static final Color INK = Color.decode("#0b0b0b");

	private static final Color INK_SECONDARY = Color.decode("#52514e");

	private static final Color GRID = Color.decode("#e1e0d9");

	private static final Color AXIS = Color.decode("#c3c2b7");

	private static final String FONT = "Segoe UI";

	private LineChart() {
	}

	public record Line(String label, Color color, Marker marker, double[] x, double[] y) {
	}

	public record Axes(String xTitle, String xPattern, String yTitle, String yPattern, double yMin, double yMax) {
	}

	public static void render(Path file, String title, Axes axes, List<Line> lines) throws IOException {
		XYChart chart = new XYChartBuilder().width(1200)
			.height(675)
			.title(title)
			.xAxisTitle(axes.xTitle())
			.yAxisTitle(axes.yTitle())
			.build();
		var st = chart.getStyler();
		st.setChartBackgroundColor(SURFACE);
		st.setPlotBackgroundColor(SURFACE);
		st.setPlotBorderVisible(false);
		st.setChartFontColor(INK);
		st.setChartTitleFont(new Font(FONT, Font.BOLD, 20));
		st.setChartTitleBoxVisible(false);
		st.setAxisTitleFont(new Font(FONT, Font.PLAIN, 14));
		st.setAxisTickLabelsFont(new Font(FONT, Font.PLAIN, 13));
		st.setAxisTickLabelsColor(INK_SECONDARY);
		st.setAxisTickMarksColor(AXIS);
		st.setPlotGridLinesColor(GRID);
		st.setPlotGridLinesStroke(new BasicStroke(1f));
		st.setPlotGridVerticalLinesVisible(false);
		st.setLegendPosition(Styler.LegendPosition.InsideNW);
		st.setLegendFont(new Font(FONT, Font.PLAIN, 14));
		st.setLegendBorderColor(SURFACE);
		st.setLegendBackgroundColor(SURFACE);
		st.setMarkerSize(9);
		st.setXAxisLogarithmic(true);
		st.setYAxisLogarithmic(true);
		st.setXAxisDecimalPattern(axes.xPattern());
		st.setYAxisDecimalPattern(axes.yPattern());
		st.setYAxisMin(axes.yMin());
		st.setYAxisMax(axes.yMax());
		st.setPlotMargin(12);

		for (Line line : lines) {
			XYSeries series = chart.addSeries(line.label(), line.x(), line.y());
			series.setLineColor(line.color());
			series.setMarkerColor(line.color());
			series.setMarker(line.marker());
			series.setLineStyle(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		}
		BitmapEncoder.saveBitmapWithDPI(chart, file.toString(), BitmapEncoder.BitmapFormat.PNG, 200);
	}

}
