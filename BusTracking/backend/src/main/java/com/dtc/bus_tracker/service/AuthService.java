package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.dto.LoginResponse;
import com.dtc.bus_tracker.entity.AdminUser;
import com.dtc.bus_tracker.repository.AdminUserRepository;
import com.dtc.bus_tracker.security.JwtService;
import com.dtc.bus_tracker.security.LoginAttemptLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final AdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginAttemptLimiter loginAttemptLimiter;

    /** Hashed once, so an unknown username costs the same BCrypt check as a wrong password. */
    private volatile String unknownUserHash;

    public LoginResponse login(String username, String password, String clientAddress) {
        if (loginAttemptLimiter.isBlocked(clientAddress)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many failed login attempts. Try again later.");
        }

        AdminUser user = adminUserRepository.findByUsername(username).orElse(null);
        String hash = user != null ? user.getPasswordHash() : unknownUserHash();
        if (!passwordEncoder.matches(password, hash) || user == null) {
            loginAttemptLimiter.recordFailure(clientAddress);
            log.warn("Failed admin login for '{}' from {}", username, clientAddress);
            throw new BadCredentialsException("Invalid username or password");
        }

        loginAttemptLimiter.recordSuccess(clientAddress);
        return LoginResponse.builder()
                .token(jwtService.generateToken(user.getUsername()))
                .username(user.getUsername())
                .role("ADMIN")
                .build();
    }

    private String unknownUserHash() {
        if (unknownUserHash == null) {
            unknownUserHash = passwordEncoder.encode("unknown-user-placeholder");
        }
        return unknownUserHash;
    }
}
