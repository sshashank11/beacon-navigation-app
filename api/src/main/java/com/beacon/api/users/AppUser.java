package com.beacon.api.users;

import java.util.UUID;

/** A registered account. The password hash never leaves this package. */
public record AppUser(UUID id, String email, String passwordHash) {
}
