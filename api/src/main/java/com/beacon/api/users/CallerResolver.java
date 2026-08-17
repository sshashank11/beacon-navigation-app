package com.beacon.api.users;

import java.security.Principal;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Turns an authenticated principal into an account id, or null if anonymous. */
@Component
public class CallerResolver {

    private final UserService users;

    public CallerResolver(UserService users) {
        this.users = users;
    }

    public UUID resolve(Principal principal) {
        if (principal == null) {
            return null;
        }
        return users.require(principal.getName()).id();
    }

    /** The account id of an endpoint that requires authentication. */
    public UUID require(Principal principal) {
        UUID id = resolve(principal);
        if (id == null) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Authentication required");
        }
        return id;
    }
}
