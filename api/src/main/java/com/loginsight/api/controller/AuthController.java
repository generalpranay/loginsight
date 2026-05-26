package com.loginsight.api.controller;

import com.loginsight.api.security.JwtUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    record LoginRequest(String username, String password) {}

    private record UserInfo(String hashedPassword, String role) {}

    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final Map<String, UserInfo> users;

    public AuthController(JwtUtil jwtUtil, PasswordEncoder passwordEncoder) {
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = passwordEncoder;
        // Passwords are hashed once at startup, not per-request
        this.users = Map.of(
            "admin",    new UserInfo(passwordEncoder.encode("admin123"),  "ADMIN"),
            "operator", new UserInfo(passwordEncoder.encode("ops123"),    "OPERATOR"),
            "viewer",   new UserInfo(passwordEncoder.encode("viewer123"), "VIEWER")
        );
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        UserInfo user = req.username() != null ? users.get(req.username()) : null;
        if (user == null || req.password() == null
                || !passwordEncoder.matches(req.password(), user.hashedPassword())) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials"));
        }
        String token = jwtUtil.generateToken(req.username(), user.role());
        return ResponseEntity.ok(Map.of(
            "token",    token,
            "username", req.username(),
            "role",     user.role()
        ));
    }
}
