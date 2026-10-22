package com.ainotes.auth;

import com.ainotes.common.UnauthorizedException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final CurrentUser currentUser;

    public AuthController(AuthService authService, CurrentUser currentUser) {
        this.authService = authService;
        this.currentUser = currentUser;
    }

    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return toResponse(authService.register(request.email(), request.password(), request.displayName()));
    }

    @PostMapping("/login")
    public AuthResponse login(@RequestBody LoginRequest request) {
        return toResponse(authService.login(request.email(), request.password()));
    }

    @GetMapping("/me")
    public UserResponse me() {
        AppUser user = currentUser.find()
                .orElseThrow(() -> new UnauthorizedException("未登录"));
        return UserResponse.from(user);
    }

    private static AuthResponse toResponse(AuthService.AuthResult result) {
        return new AuthResponse(result.token(), UserResponse.from(result.user()));
    }

    public record RegisterRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 8, message = "密码至少 8 位") String password,
            String displayName) {
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record UserResponse(UUID id, String email, String displayName) {
        static UserResponse from(AppUser user) {
            return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName());
        }
    }

    public record AuthResponse(String token, UserResponse user) {
    }
}
