package com.skylane.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class AuthDtos {
    private AuthDtos() {
    }

    public record RegisterRequest(@NotBlank @Email @Size(max = 254) String email,
                                  @NotBlank @Size(min = 8, max = 100) String password,
                                  @NotBlank @Size(max = 80) String displayName) {
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    /** role is customer / operator / admin; operatorId is set only for operators (design 8.3, D27). */
    public record UserView(String id, String email, String displayName, boolean hasPassword,
                           boolean googleLinked, Instant createdAt, String role, String operatorId) {
    }

    public record GoogleLoginRequest(@NotBlank @Size(max = 4096) String idToken) {
    }

    public record UpdateProfileRequest(@NotBlank @Size(max = 80) String displayName) {
    }

    /** currentPassword may be omitted only by an account that has no password yet (Google sign-up). */
    public record ChangePasswordRequest(@Size(max = 100) String currentPassword,
                                        @NotBlank @Size(min = 8, max = 100) String newPassword) {
    }

    public record AuthResponse(String accessToken, UserView user) {
    }
}
