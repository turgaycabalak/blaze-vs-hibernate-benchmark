package dev.blazebench.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads the summary.csv files the benchmarks write (header row; fields may be double-quoted,
 * quoted fields contain no quotes or line breaks).
 */
public final class SummaryCsv {

	private SummaryCsv() {
	}

	public static List<Map<String, String>> read(Path csv) throws IOException {
		List<String> lines = Files.readAllLines(csv);
		List<String> header = split(lines.getFirst());
		List<Map<String, String>> rows = new ArrayList<>();
		for (String line : lines.subList(1, lines.size())) {
			List<String> fields = split(line);
			Map<String, String> row = new LinkedHashMap<>();
			for (int i = 0; i < header.size(); i++) {
				row.put(header.get(i), fields.get(i));
			}
			rows.add(row);
		}
		return rows;
	}

	/** x → y for one scenario, sorted by x. */
	public static TreeMap<Double, Double> series(List<Map<String, String>> rows, String scenario, String xColumn,
			String yColumn) {
		TreeMap<Double, Double> points = new TreeMap<>();
		rows.stream()
			.filter(r -> r.get("scenario").equals(scenario))
			.forEach(r -> points.put(Double.parseDouble(r.get(xColumn)), Double.parseDouble(r.get(yColumn))));
		if (points.isEmpty()) {
			throw new IllegalArgumentException("no rows for scenario " + scenario);
		}
		return points;
	}

	public static String label(List<Map<String, String>> rows, String scenario) {
		return rows.stream()
			.filter(r -> r.get("scenario").equals(scenario))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("no rows for scenario " + scenario))
			.get("label");
	}

	private static List<String> split(String line) {
		List<String> fields = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean quoted = false;
		for (char c : line.toCharArray()) {
			if (c == '"') {
				quoted = !quoted;
			}
			else if (c == ',' && !quoted) {
				fields.add(current.toString());
				current.setLength(0);
			}
			else {
				current.append(c);
			}
		}
		fields.add(current.toString());
		return fields;
	}

}
