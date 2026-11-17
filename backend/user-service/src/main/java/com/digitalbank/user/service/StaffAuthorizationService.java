package com.digitalbank.user.service;

import com.digitalbank.common.auth.SessionIdentity;
import com.digitalbank.user.config.StaffAuthorizationProperties;
import org.springframework.stereotype.Service;

@Service
public final class StaffAuthorizationService {
    private final StaffAuthorizationProperties properties;

    public StaffAuthorizationService(StaffAuthorizationProperties properties) {
        this.properties = properties;
    }

    public SessionIdentity authorize(SessionIdentity identity) {
        var grant = properties.grants().get(identity.identityId());
        if (grant == null) {
            return new SessionIdentity(identity.customerId(), identity.identityId());
        }
        return new SessionIdentity(identity.customerId(), identity.identityId(),
                grant.roles(), grant.accountScope());
    }
}
