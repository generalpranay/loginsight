package com.loginsight.api.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class UserController {

    private static final List<Map<String, String>> USERS = List.of(
        Map.of("username", "admin",    "role", "ADMIN",    "description", "Full administrative access"),
        Map.of("username", "operator", "role", "OPERATOR", "description", "Operational access with alert acknowledgement"),
        Map.of("username", "viewer",   "role", "VIEWER",   "description", "Read-only access to metrics, logs, and alerts")
    );

    private static final List<Map<String, Object>> ROLES = List.of(
        Map.of("role", "ADMIN",    "permissions", List.of(
            "read:metrics", "read:logs", "read:alerts",
            "write:logs", "acknowledge:alerts", "manage:simulation", "admin:users")),
        Map.of("role", "OPERATOR", "permissions", List.of(
            "read:metrics", "read:logs", "read:alerts",
            "write:logs", "acknowledge:alerts", "manage:simulation")),
        Map.of("role", "VIEWER",   "permissions", List.of(
            "read:metrics", "read:logs", "read:alerts"))
    );

    @GetMapping("/users")
    public List<Map<String, String>> getUsers() {
        return USERS;
    }

    @GetMapping("/roles")
    public List<Map<String, Object>> getRoles() {
        return ROLES;
    }
}
