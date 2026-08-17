package com.beacon.api.users;

import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Registers an account, or reports that the address is already taken. */
    public AppUser create(String email, String passwordHash) {
        UUID id = UUID.randomUUID();
        try {
            jdbcTemplate.update(
                    "INSERT INTO app_user (id, email, password_hash) VALUES (?, ?, ?)",
                    id,
                    email,
                    passwordHash);
        } catch (DuplicateKeyException exception) {
            throw new EmailAlreadyRegisteredException();
        }
        return new AppUser(id, email, passwordHash);
    }

    /**
     * Removes the account and everything hanging off it.
     *
     * <p>Routes cascade from the foreign key, and route feedback cascades from
     * routes, so one delete clears the lot. Returns false when the account was
     * already gone, which keeps repeated requests harmless.
     */
    public boolean deleteById(UUID id) {
        return jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", id) > 0;
    }

    public Optional<AppUser> findByEmail(String email) {
        return jdbcTemplate.query(
                "SELECT id, email, password_hash FROM app_user WHERE lower(email) = lower(?)",
                (resultSet, rowNumber) -> new AppUser(
                        UUID.fromString(resultSet.getString("id")),
                        resultSet.getString("email"),
                        resultSet.getString("password_hash")),
                email).stream().findFirst();
    }
}
