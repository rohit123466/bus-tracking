package com.dtc.bus_tracker.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProductionSafetyCheckTest {

    private static final String STRONG_SECRET = "q8Vx2mN4pL7rT1wZ9kB3cF6hJ0sD5gY8aE2uI4oP";
    private static final String[] ORIGINS = {"https://bus-tracker-web.onrender.com"};

    @Test void acceptsStrongProductionSettings() {
        assertTrue(ProductionSafetyCheck.problems(STRONG_SECRET, "correct-horse-battery", ORIGINS).isEmpty());
        assertDoesNotThrow(() -> new ProductionSafetyCheck(STRONG_SECRET, "correct-horse-battery", ORIGINS));
    }

    @Test void rejectsTheDevelopmentJwtSecret() {
        var problems = ProductionSafetyCheck.problems(
                "dev-only-insecure-secret-change-me-please-32-bytes-minimum", "correct-horse-battery", ORIGINS);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("JWT_SECRET"));
    }

    @Test void rejectsShortSecretsAndDefaultPasswords() {
        assertEquals(2, ProductionSafetyCheck.problems("short", "admin123", ORIGINS).size());
        assertEquals(1, ProductionSafetyCheck.problems(STRONG_SECRET, "Admin123", ORIGINS).size());
    }

    @Test void rejectsWildcardOrMissingCorsOrigins() {
        assertEquals(1, ProductionSafetyCheck.problems(STRONG_SECRET, "correct-horse-battery", new String[]{"*"}).size());
        assertEquals(1, ProductionSafetyCheck.problems(STRONG_SECRET, "correct-horse-battery", new String[]{}).size());
    }

    @Test void startupFailsListingEveryProblem() {
        var ex = assertThrows(IllegalStateException.class,
                () -> new ProductionSafetyCheck("", "", new String[]{""}));
        assertTrue(ex.getMessage().contains("JWT_SECRET"));
        assertTrue(ex.getMessage().contains("ADMIN_PASSWORD"));
        assertTrue(ex.getMessage().contains("CORS_ALLOWED_ORIGINS"));
    }
}
