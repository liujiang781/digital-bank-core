package com.digitalbank.transaction.security;

import java.security.Principal;
import java.util.Objects;

/** Server-authenticated identity supplied by S1; never constructed from client identity headers. */
public record Actor(String identityId) implements Principal {
    public Actor {
        Objects.requireNonNull(identityId, "identityId");
        if (identityId.isBlank()) throw new IllegalArgumentException("identityId must not be blank");
    }

    @Override
    public String getName() { return identityId; }
}
