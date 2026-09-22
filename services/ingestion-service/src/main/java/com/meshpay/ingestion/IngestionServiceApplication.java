package com.meshpay.ingestion;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class IngestionServiceApplication {

	static {
		DotenvLoader.load();
	}

	public static void main(String[] args) {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(IngestionServiceApplication.class, args);
	}
}
