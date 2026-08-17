package com.beacon.api.users;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class BeaconUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    public BeaconUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        AppUser user = users.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown account"));
        return User.withUsername(user.email())
                .password(user.passwordHash())
                .authorities("ROLE_USER")
                .build();
    }
}
