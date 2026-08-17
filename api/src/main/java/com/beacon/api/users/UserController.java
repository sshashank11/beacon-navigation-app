package com.beacon.api.users;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class UserController {

    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse register(@Valid @RequestBody RegistrationRequest request) {
        AppUser user = users.register(request.email(), request.password());
        return new AccountResponse(user.id(), user.email());
    }

    /**
     * Confirms credentials and identifies the caller. Authentication itself is
     * handled by the security filter chain, so reaching this method is the
     * successful login.
     */
    @GetMapping("/me")
    public AccountResponse me(Principal principal) {
        AppUser user = users.require(principal.getName());
        return new AccountResponse(user.id(), user.email());
    }

    public record AccountResponse(UUID id, String email) {
    }
}
