package com.loginsight.api;

import com.loginsight.ingestion.LogSimulator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * REST controller that exposes simulation control and service discovery endpoints.
 *
 * <table border="1">
 *   <tr><th>Method + Path</th><th>Description</th></tr>
 *   <tr><td>POST /api/v1/simulation/start</td><td>Start the log simulator</td></tr>
 *   <tr><td>POST /api/v1/simulation/stop</td><td>Stop the log simulator</td></tr>
 *   <tr><td>GET  /api/v1/simulation/status</td><td>Simulation status + stats</td></tr>
 *   <tr><td>GET  /api/v1/services</td><td>List of simulated service names</td></tr>
 * </table>
 */
@RestController
public class SimulationController {

    private final SimulationService simulationService;

    public SimulationController(SimulationService simulationService) {
        this.simulationService = simulationService;
    }

    @PostMapping("/api/v1/simulation/start")
    public ResponseEntity<?> start() {
        try {
            simulationService.start();
            return ResponseEntity.ok(Map.of("message", "Simulation started successfully"));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/api/v1/simulation/stop")
    public ResponseEntity<?> stop() {
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
}
