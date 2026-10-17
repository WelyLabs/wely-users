package com.calendar.users.application.mappers;

import com.calendar.users.application.dtos.RegistrationRequest;
import com.calendar.users.domain.models.Registration;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface RegistrationRequestMapper {

    Registration toRegistration(RegistrationRequest request);
}
