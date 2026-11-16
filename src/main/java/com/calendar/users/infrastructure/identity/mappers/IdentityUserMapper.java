package com.calendar.users.infrastructure.identity.mappers;

import com.calendar.users.domain.models.IdentityUser;
import com.calendar.users.infrastructure.identity.models.Auth0UserResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Translates Auth0's Management API payload into the domain's {@code IdentityUser},
 * dropping the fields no one in this service reads.
 */
@Mapper(componentModel = "spring")
public interface IdentityUserMapper {

    @Mapping(target = "firstName", source = "givenName")
    @Mapping(target = "lastName", source = "familyName")
    IdentityUser toIdentityUser(Auth0UserResponse response);
}
