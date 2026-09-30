package dev.blazebench.bench;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PostgreSQL for benchmarks. Settings are deliberately few and are all reported in environment.txt:
 * shared_buffers is large enough that the whole dataset stays in memory, so we measure query
 * strategy rather than disk speed. max_wal_size only speeds up the initial seed.
 */
@TestConfiguration(proxyBeanMethods = false)
public class BenchmarkContainerConfiguration {

	public static final String IMAGE = "postgres:18";

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse(IMAGE))
			.withSharedMemorySize(1024L * 1024 * 1024)
			.withCommand("postgres", "-c", "shared_buffers=1GB", "-c", "max_wal_size=4GB");
	}

}
