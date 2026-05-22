package com.loginsight.api;

import com.loginsight.storage.ElasticsearchWriter;
import com.loginsight.storage.InfluxDbWriter;
import com.loginsight.storage.MetricsAggregator;
import com.loginsight.telemetry.TelemetryConfig;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;

/**
 * Spring {@code @Configuration} that wires non-Spring infrastructure beans and
 * applies security policies (response headers, CORS) to every request.
 *
 * <h2>Why plain-Java storage classes?</h2>
 * <p>{@link com.loginsight.storage.ElasticsearchWriter} and
 * {@link com.loginsight.storage.InfluxDbWriter} carry no Spring annotations so the
 * same JARs can be used in the ingestion process without pulling in the Spring
 * container — a startup-time and footprint saving for the long-running worker.
 *
 * <h2>Graceful degradation</h2>
 * <p>Connection URLs default to empty strings. Each writer treats a blank URL as
 * "disabled" and operates as a no-op, allowing the API to start cleanly in local
 * dev or CI without external services running.
 */
@Configuration
public class AppConfig {

    @Value("${loginsight.elasticsearch.url:}")
    private String elasticsearchUrl;

    @Value("${loginsight.influxdb.url:}")
    private String influxDbUrl;

    @Value("${loginsight.influxdb.token:}")
    private String influxDbToken;

    @Value("${loginsight.influxdb.org:}")
    private String influxDbOrg;

    @Value("${loginsight.influxdb.bucket:}")
    private String influxDbBucket;

    @Value("${loginsight.cors.allowed-origins:}")
    private String corsAllowedOrigins;

    @Value("${loginsight.metrics.flush-interval-seconds:10}")
    private long metricsFlushIntervalSeconds;

    @Bean(destroyMethod = "close")
    public TelemetryConfig telemetryConfig() {
        return TelemetryConfig.initialize();
    }

    @Bean(destroyMethod = "close")
    public ElasticsearchWriter elasticsearchWriter() {
        return new ElasticsearchWriter(elasticsearchUrl);
    }

    @Bean(destroyMethod = "close")
    public InfluxDbWriter influxDbWriter() {
        return new InfluxDbWriter(influxDbUrl, influxDbToken, influxDbOrg, influxDbBucket);
    }

    @Bean
    public MetricsAggregator metricsAggregator() {
        return new MetricsAggregator(influxDbWriter(), metricsFlushIntervalSeconds);
    }

    /**
     * Adds defensive HTTP security headers to every response.
     *
     * <ul>
     *   <li>{@code X-Content-Type-Options: nosniff} — prevents MIME-type sniffing</li>
     *   <li>{@code X-Frame-Options: DENY} — blocks clickjacking in older browsers</li>
     *   <li>{@code X-XSS-Protection: 0} — disables the legacy IE XSS filter (which can
     *       itself be exploited; modern browsers ignore it anyway)</li>
     *   <li>{@code Referrer-Policy} — limits referrer leakage on cross-origin navigations</li>
     *   <li>{@code Content-Security-Policy} — restricts resource loading to same origin;
     *       {@code frame-ancestors 'none'} supersedes X-Frame-Options for modern browsers</li>
     * </ul>
     */
    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> securityHeadersFilter() {
        FilterRegistrationBean<OncePerRequestFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                    throws IOException, ServletException {
                res.setHeader("X-Content-Type-Options", "nosniff");
                res.setHeader("X-Frame-Options", "DENY");
                res.setHeader("X-XSS-Protection", "0");
                res.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
                res.setHeader("Content-Security-Policy",
                        "default-src 'self'; " +
                        "script-src 'self' 'unsafe-inline'; " +
                        "style-src 'self' 'unsafe-inline'; " +
                        "img-src 'self' data:; " +
                        "connect-src 'self'; " +
                        "frame-ancestors 'none'");
                chain.doFilter(req, res);
            }
        });
        reg.addUrlPatterns("/*");
        reg.setOrder(1);
        return reg;
    }

    /**
     * Restricts cross-origin API access. By default (empty {@code CORS_ALLOWED_ORIGINS}),
     * all cross-origin requests are denied. Set the env var to a comma-separated list of
     * trusted origins to enable cross-origin access for those origins only.
     */
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                if (corsAllowedOrigins == null || corsAllowedOrigins.isBlank()) return;
                String[] origins = corsAllowedOrigins.split(",");
                registry.addMapping("/api/**")
                        .allowedOrigins(origins)
                        .allowedMethods("GET", "POST")
                        .allowedHeaders("Content-Type", "Accept", "X-Simulation-Key")
                        .maxAge(3600);
            }
        };
    }
}
