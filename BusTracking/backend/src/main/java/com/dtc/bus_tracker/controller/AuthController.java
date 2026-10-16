package com.dtc.bus_tracker.controller;

import com.dtc.bus_tracker.dto.LoginRequest;
import com.dtc.bus_tracker.dto.LoginResponse;
import com.dtc.bus_tracker.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                               HttpServletRequest httpRequest) {
        // Behind Render's proxy this is the real client address only because the
        // prod profile sets server.forward-headers-strategy.
        return ResponseEntity.ok(authService.login(
                request.getUsername(), request.getPassword(), httpRequest.getRemoteAddr()));
    }
}
