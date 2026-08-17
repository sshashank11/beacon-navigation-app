package com.beacon.api.users;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Signals a taken address without confirming it to an unauthenticated caller
 * beyond what registration inherently reveals.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException() {
        super("That email address is already registered");
    }
}
