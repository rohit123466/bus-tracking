package com.dtc.bus_tracker.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Stops the {@code prod} profile from starting with development secrets. The
 * base properties fall back to a known JWT secret and admin password so local
 * runs need no setup; in production those fallbacks would let anyone forge an
 * admin token or log in, so startup fails instead.
 */
@Component
@Profile("prod")
public class ProductionSafetyCheck {

    static final int MIN_SECRET_BYTES = 32;
    static final Set<String> KNOWN_WEAK_PASSWORDS = Set.of("admin", "admin123", "password", "changeme");

    public ProductionSafetyCheck(@Value("${jwt.secret:}") String jwtSecret,
                                 @Value("${admin.default-password:}") String adminPassword,
                                 @Value("${cors.allowed-origins:}") String[] corsOrigins) {
        List<String> problems = problems(jwtSecret, adminPassword, corsOrigins);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start in prod: " + String.join("; ", problems));
        }
    }

    static List<String> problems(String jwtSecret, String adminPassword, String[] corsOrigins) {
        List<String> problems = new ArrayList<>();
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES
                || jwtSecret.startsWith("dev-only")) {
            problems.add("JWT_SECRET must be set to a random value of at least " + MIN_SECRET_BYTES + " bytes");
        }
        if (adminPassword == null || adminPassword.length() < 12
                || KNOWN_WEAK_PASSWORDS.contains(adminPassword.toLowerCase())) {
            problems.add("ADMIN_PASSWORD must be set to a strong value (12+ characters)");
        }
        boolean anyOrigin = corsOrigins == null || corsOrigins.length == 0
                || Arrays.stream(corsOrigins).anyMatch(o -> o.isBlank() || o.contains("*"));
        if (anyOrigin) {
            problems.add("CORS_ALLOWED_ORIGINS must list the frontend's exact origin(s), without wildcards");
        }
        return problems;
    }
}
