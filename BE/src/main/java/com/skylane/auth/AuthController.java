package com.skylane.auth;

import com.skylane.auth.AuthDtos.AuthResponse;
import com.skylane.auth.AuthDtos.LoginRequest;
import com.skylane.auth.AuthDtos.RegisterRequest;
import com.skylane.auth.AuthDtos.UserView;
import com.skylane.quote.CurrentUserProvider;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Profile("!memory")
public class AuthController {
    private final UserService users;
    private final CurrentUserProvider currentUser;

    public AuthController(UserService users, CurrentUserProvider currentUser) {
        this.users = users;
        this.currentUser = currentUser;
    }

    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest req) {
        return users.register(req);
    }

    @PostMapping("/auth/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req) {
        return users.login(req);
    }

    @GetMapping("/me")
    public UserView me() {
        return users.me(currentUser.currentUserId());
    }
}
