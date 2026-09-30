package dev.blazebench.bench;

import java.util.Arrays;
import java.util.List;

/**
 * Latency summary in milliseconds. Percentiles use the nearest-rank method, so with
 * 100 samples p99 is the 99th slowest value — report the sample count next to it.
 */
public record LatencyStats(int samples, double p50, double p95, double p99, double mean, double min, double max) {

	public static LatencyStats of(List<Long> nanos) {
		long[] sorted = nanos.stream().mapToLong(Long::longValue).sorted().toArray();
		if (sorted.length == 0) {
			throw new IllegalArgumentException("no samples");
		}
		return new LatencyStats(sorted.length,
				ms(percentile(sorted, 50)),
				ms(percentile(sorted, 95)),
				ms(percentile(sorted, 99)),
				ms((long) Arrays.stream(sorted).average().orElseThrow()),
				ms(sorted[0]),
				ms(sorted[sorted.length - 1]));
	}

	private static long percentile(long[] sorted, int p) {
		int rank = (int) Math.ceil(p / 100.0 * sorted.length);
		return sorted[Math.max(0, rank - 1)];
	}

	private static double ms(long nanos) {
		return nanos / 1_000_000.0;
	}

}
