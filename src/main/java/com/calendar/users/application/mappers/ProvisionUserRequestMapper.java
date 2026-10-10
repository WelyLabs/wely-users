package com.calendar.users.application.mappers;

import com.calendar.users.application.dtos.ProvisionUserRequest;
import com.calendar.users.domain.models.IdentityUser;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ProvisionUserRequestMapper {

    IdentityUser toIdentityUser(ProvisionUserRequest request);
}
