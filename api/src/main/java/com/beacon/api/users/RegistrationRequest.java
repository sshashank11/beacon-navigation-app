package com.beacon.api.users;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A minimum length is enforced rather than a composition rule. Length is what
 * actually resists guessing, and character-class rules mostly push people
 * toward predictable substitutions.
 */
public record RegistrationRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 12, max = 200) String password) {
}
