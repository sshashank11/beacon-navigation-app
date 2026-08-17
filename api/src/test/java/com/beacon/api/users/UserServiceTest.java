package com.beacon.api.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class UserServiceTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Test
    void theRawPasswordNeverReachesStorage() {
        UserRepository repository = mock(UserRepository.class);
        PasswordEncoder encoder = new BCryptPasswordEncoder();
        UserService service = new UserService(repository, encoder);
        when(repository.create(any(), any())).thenAnswer(invocation ->
                new AppUser(UUID.randomUUID(), invocation.getArgument(0),
                        invocation.getArgument(1)));

        service.register("Person@example.com", PASSWORD);

        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(repository).create(eq("Person@example.com"), stored.capture());
        assertThat(stored.getValue()).isNotEqualTo(PASSWORD);
        assertThat(stored.getValue()).startsWith("$2");
        assertThat(encoder.matches(PASSWORD, stored.getValue())).isTrue();
    }

    @Test
    void surroundingWhitespaceInAnAddressIsIgnored() {
        UserRepository repository = mock(UserRepository.class);
        UserService service = new UserService(repository, new BCryptPasswordEncoder());
        when(repository.create(any(), any())).thenAnswer(invocation ->
                new AppUser(UUID.randomUUID(), invocation.getArgument(0), "hash"));

        service.register("  person@example.com  ", PASSWORD);

        verify(repository).create(eq("person@example.com"), any());
    }

    @Test
    void twoAccountsWithTheSamePasswordGetDifferentHashes() {
        UserRepository repository = mock(UserRepository.class);
        UserService service = new UserService(repository, new BCryptPasswordEncoder());
        when(repository.create(any(), any())).thenAnswer(invocation ->
                new AppUser(UUID.randomUUID(), invocation.getArgument(0),
                        invocation.getArgument(1)));
        ArgumentCaptor<String> hashes = ArgumentCaptor.forClass(String.class);

        service.register("one@example.com", PASSWORD);
        service.register("two@example.com", PASSWORD);

        verify(repository, org.mockito.Mockito.times(2)).create(any(), hashes.capture());
        assertThat(hashes.getAllValues().get(0))
                .as("bcrypt salts each hash, so a leak cannot be matched across accounts")
                .isNotEqualTo(hashes.getAllValues().get(1));
    }

    @Test
    void anUnknownAccountIsRejected() {
        UserRepository repository = mock(UserRepository.class);
        when(repository.findByEmail(any())).thenReturn(Optional.empty());
        UserService service = new UserService(repository, new BCryptPasswordEncoder());

        assertThatThrownBy(() -> service.require("nobody@example.com"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void detailsLoadWithTheStoredHashAndAStandardRole() {
        UserRepository repository = mock(UserRepository.class);
        when(repository.findByEmail("person@example.com")).thenReturn(
                Optional.of(new AppUser(UUID.randomUUID(), "person@example.com", "$2a$hash")));

        var details = new BeaconUserDetailsService(repository)
                .loadUserByUsername("person@example.com");

        assertThat(details.getUsername()).isEqualTo("person@example.com");
        assertThat(details.getPassword()).isEqualTo("$2a$hash");
        assertThat(details.getAuthorities()).extracting("authority")
                .containsExactly("ROLE_USER");
    }
}
