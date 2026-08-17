package com.beacon.api.users;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    public AppUser register(String email, String rawPassword) {
        String normalized = email.strip();
        // The hash is computed here so a raw password never reaches the
        // repository, and never has a chance of being logged with a query.
        return users.create(normalized, passwordEncoder.encode(rawPassword));
    }

    public AppUser require(String email) {
        return users.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown account"));
    }
}
