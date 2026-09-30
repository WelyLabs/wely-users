package com.calendar.users.infrastructure.identity.mappers;

import com.calendar.users.domain.models.IdentityUser;
import com.calendar.users.infrastructure.identity.models.KeycloakUserResponse;
import org.mapstruct.Mapper;

/**
 * Translates Keycloak's Admin API payload into the domain's {@code IdentityUser},
 * dropping the fields no one in this service reads.
 */
@Mapper(componentModel = "spring")
public interface IdentityUserMapper {

    IdentityUser toIdentityUser(KeycloakUserResponse response);
}
