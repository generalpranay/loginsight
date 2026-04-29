package com.loginsight.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point for the Log-to-Insight REST API.
 *
 * <p>Virtual threads replace the Tomcat thread pool via
 * {@code spring.threads.virtual.enabled=true} in {@code application.properties},
 * eliminating the need for reactive programming while achieving equivalent
 * concurrency under high I/O load.
 */
@SpringBootApplication
public class LogInsightApplication {
    public static void main(String[] args) {
        SpringApplication.run(LogInsightApplication.class, args);
    }
}
