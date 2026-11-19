package com.digitalbank.account.security;

import java.security.Principal;
import java.util.Objects;
import java.util.Set;

/** Server-authenticated identity supplied by S1; never constructed from client identity headers. */
public record Actor(String identityId, String customerId, Set<String> roles,
                    Set<String> accountScope) implements Principal {
    public Actor {
        Objects.requireNonNull(identityId, "identityId");
        if (identityId.isBlank()) throw new IllegalArgumentException("identityId must not be blank");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        accountScope = Set.copyOf(Objects.requireNonNull(accountScope, "accountScope"));
    }

    @Override
    public String getName() { return identityId; }
}
