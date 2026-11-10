package com.digitalbank.user.repository;

import com.digitalbank.user.domain.LoginCredential;
import java.util.Optional;

public interface CredentialRepository {
    Optional<LoginCredential> findByIdentityId(String identityId);
}
