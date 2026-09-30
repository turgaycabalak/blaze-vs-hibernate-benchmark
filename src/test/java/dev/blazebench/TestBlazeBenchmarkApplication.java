package dev.blazebench;

import org.springframework.boot.SpringApplication;

public class TestBlazeBenchmarkApplication {

	public static void main(String[] args) {
		SpringApplication.from(BlazeBenchmarkApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
