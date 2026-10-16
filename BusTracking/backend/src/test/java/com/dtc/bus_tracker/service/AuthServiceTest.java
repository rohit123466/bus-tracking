package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.entity.AdminUser;
import com.dtc.bus_tracker.repository.AdminUserRepository;
import com.dtc.bus_tracker.security.JwtService;
import com.dtc.bus_tracker.security.LoginAttemptLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    private final AdminUserRepository users = mock(AdminUserRepository.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4); // low cost keeps the test fast
    private final JwtService jwt = mock(JwtService.class);
    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(3, 15);
    private final AuthService auth = new AuthService(users, encoder, jwt, limiter);

    AuthServiceTest() {
        when(users.findByUsername("admin")).thenReturn(Optional.of(
                AdminUser.builder().username("admin").passwordHash(encoder.encode("right-password")).build()));
        when(jwt.generateToken("admin")).thenReturn("token");
    }

    @Test void correctPasswordReturnsAToken() {
        assertEquals("token", auth.login("admin", "right-password", "1.2.3.4").getToken());
    }

    @Test void wrongPasswordAndUnknownUserGiveTheSameError() {
        var wrong = assertThrows(BadCredentialsException.class, () -> auth.login("admin", "nope", "1.2.3.4"));
        var unknown = assertThrows(BadCredentialsException.class, () -> auth.login("ghost", "nope", "1.2.3.4"));
        assertEquals(wrong.getMessage(), unknown.getMessage());
    }

    @Test void repeatedFailuresLockTheClientOutEvenWithTheRightPassword() {
        for (int i = 0; i < 3; i++) {
            assertThrows(BadCredentialsException.class, () -> auth.login("admin", "guess", "1.2.3.4"));
        }
        var ex = assertThrows(ResponseStatusException.class, () -> auth.login("admin", "right-password", "1.2.3.4"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());
        assertEquals("token", auth.login("admin", "right-password", "5.6.7.8").getToken());
    }
}
