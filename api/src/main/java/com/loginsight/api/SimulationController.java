package com.loginsight.api;

import com.loginsight.ingestion.LogSimulator;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * REST controller that exposes simulation control and service discovery endpoints.
 *
 * <table border="1">
 *   <tr><th>Method + Path</th><th>Auth</th><th>Description</th></tr>
 *   <tr><td>POST /api/v1/simulation/start</td><td>X-Simulation-Key *</td><td>Start the log simulator</td></tr>
 *   <tr><td>POST /api/v1/simulation/stop</td><td>X-Simulation-Key *</td><td>Stop the log simulator</td></tr>
 *   <tr><td>GET  /api/v1/simulation/status</td><td>open</td><td>Simulation status + stats</td></tr>
 *   <tr><td>GET  /api/v1/services</td><td>open</td><td>List of simulated service names</td></tr>
 * </table>
 *
 * <p>* Requires {@code X-Simulation-Key} header when {@code loginsight.simulation.api-key} is
 * configured. When the property is blank the endpoints are open (suitable for localhost-only
 * deployments). Set {@code SIMULATION_API_KEY} env var to protect them on public-facing hosts.
 */
@RestController
public class SimulationController {

    private final SimulationService simulationService;

    @Value("${loginsight.simulation.api-key:}")
    private String simulationApiKey;

    public SimulationController(SimulationService simulationService) {
        this.simulationService = simulationService;
    }

    @PostMapping("/api/v1/simulation/start")
    public ResponseEntity<?> start(HttpServletRequest request) {
        if (!isAuthorized(request)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "missing or invalid X-Simulation-Key header"));
        }
        try {
            simulationService.start();
            return ResponseEntity.ok(Map.of("message", "Simulation started successfully"));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/api/v1/simulation/stop")
    public ResponseEntity<?> stop(HttpServletRequest request) {
        if (!isAuthorized(request)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "missing or invalid X-Simulation-Key header"));
        }
        simulationService.stop();
        return ResponseEntity.ok(Map.of("message", "Simulation stopped"));
    }

    @GetMapping("/api/v1/simulation/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(simulationService.getStatus());
    }

    @GetMapping("/api/v1/services")
    public ResponseEntity<List<String>> services() {
        return ResponseEntity.ok(Arrays.asList(LogSimulator.getServices()));
    }

    /**
     * Constant-time comparison to prevent timing-based API key enumeration.
     * When no key is configured, all requests are allowed (dev/localhost mode).
     */
    private boolean isAuthorized(HttpServletRequest request) {
        if (simulationApiKey == null || simulationApiKey.isBlank()) return true;
        String provided = request.getHeader("X-Simulation-Key");
        if (provided == null || provided.isBlank()) return false;
        return MessageDigest.isEqual(
                simulationApiKey.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8)
        );
    }
}
