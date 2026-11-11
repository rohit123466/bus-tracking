package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.entity.AdminUser;
import com.dtc.bus_tracker.repository.AdminUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdminUserSeederTest {

    private final AdminUserRepository repository = mock(AdminUserRepository.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private AdminUserSeeder seeder;

    @BeforeEach
    void setUp() {
        seeder = new AdminUserSeeder(repository, encoder);
        ReflectionTestUtils.setField(seeder, "defaultUsername", "admin");
        ReflectionTestUtils.setField(seeder, "defaultPassword", "configured-pass");
    }

    @Test void createsTheAccountWhenMissing() {
        when(repository.findByUsername("admin")).thenReturn(Optional.empty());

        seeder.run();

        ArgumentCaptor<AdminUser> saved = ArgumentCaptor.forClass(AdminUser.class);
        verify(repository).save(saved.capture());
        assertEquals("admin", saved.getValue().getUsername());
        assertTrue(encoder.matches("configured-pass", saved.getValue().getPasswordHash()));
    }

    @Test void resetsAPasswordThatNoLongerMatchesTheConfiguredOne() {
        AdminUser existing = AdminUser.builder().id(1L).username("admin")
                .passwordHash(encoder.encode("old-forgotten-pass")).build();
        when(repository.findByUsername("admin")).thenReturn(Optional.of(existing));

        seeder.run();

        verify(repository).save(existing);
        assertTrue(encoder.matches("configured-pass", existing.getPasswordHash()));
    }

    @Test void leavesAMatchingAccountUntouched() {
        AdminUser existing = AdminUser.builder().id(1L).username("admin")
                .passwordHash(encoder.encode("configured-pass")).build();
        when(repository.findByUsername("admin")).thenReturn(Optional.of(existing));

        seeder.run();

        verify(repository, never()).save(any());
    }
}
