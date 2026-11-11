package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.entity.AdminUser;
import com.dtc.bus_tracker.repository.AdminUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AdminUserSeeder implements CommandLineRunner {

    private final AdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${admin.default-username}")
    private String defaultUsername;

    @Value("${admin.default-password}")
    private String defaultPassword;

    @Override
    public void run(String... args) {
        // ADMIN_USERNAME/ADMIN_PASSWORD are the source of truth: the account is brought in line
        // with them on every start, so changing the env vars and redeploying resets a lost password.
        AdminUser user = adminUserRepository.findByUsername(defaultUsername).orElse(null);
        if (user == null) {
            adminUserRepository.save(AdminUser.builder()
                    .username(defaultUsername)
                    .passwordHash(passwordEncoder.encode(defaultPassword))
                    .build());
            log.warn("Created admin user '{}' from ADMIN_USERNAME/ADMIN_PASSWORD.", defaultUsername);
        } else if (!passwordEncoder.matches(defaultPassword, user.getPasswordHash())) {
            user.setPasswordHash(passwordEncoder.encode(defaultPassword));
            adminUserRepository.save(user);
            log.warn("Reset the password of admin user '{}' to the configured ADMIN_PASSWORD.", defaultUsername);
        }
    }
}
