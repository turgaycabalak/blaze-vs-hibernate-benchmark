package dev.blazebench.bench;

import com.zaxxer.hikari.HikariDataSource;
import org.hibernate.Version;
import org.springframework.boot.SpringBootVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Everything a reader needs to judge (and reproduce) the numbers: machine, JVM, library versions,
 * database settings and dataset size.
 */
public final class EnvironmentReport {

	private EnvironmentReport() {
	}

	public static void write(Path file, JdbcTemplate jdbc, DataSource dataSource, Map<String, Object> benchmarkSettings)
			throws IOException, SQLException {
		Map<String, Object> env = new LinkedHashMap<>();
		env.put("date", OffsetDateTime.now());
		env.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
				+ System.getProperty("os.arch"));
		env.put("cpu", System.getenv().getOrDefault("PROCESSOR_IDENTIFIER", "unknown"));
		env.put("jvm.available_processors", Runtime.getRuntime().availableProcessors());
		env.put("jvm", System.getProperty("java.vm.name") + " " + Runtime.version());
		env.put("jvm.max_heap_mb", Runtime.getRuntime().maxMemory() / (1024 * 1024));
		env.put("spring_boot", SpringBootVersion.getVersion());
		env.put("spring_data_jpa", JpaRepository.class.getPackage().getImplementationVersion());
		env.put("hibernate", Version.getVersionString());
		env.put("blaze_persistence", System.getProperty("blaze.version", "see pom.xml"));
		env.put("postgres.image", BenchmarkContainerConfiguration.IMAGE);
		env.put("postgres.version", jdbc.queryForObject("select version()", String.class));
		env.put("postgres.shared_buffers", jdbc.queryForObject("show shared_buffers", String.class));
		env.put("postgres.work_mem", jdbc.queryForObject("show work_mem", String.class));
		env.put("hikari.maximum_pool_size", dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize());
		for (String table : new String[] { "customer", "product", "purchase_order", "order_line" }) {
			env.put("rows." + table, jdbc.queryForObject("select count(*) from " + table, Long.class));
		}
		env.putAll(benchmarkSettings);

		Files.writeString(file, env.entrySet()
			.stream()
			.map(e -> e.getKey() + "=" + e.getValue())
			.collect(Collectors.joining(System.lineSeparator(), "", System.lineSeparator())));
	}

}
