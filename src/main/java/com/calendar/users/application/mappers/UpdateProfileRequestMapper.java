package com.calendar.users.application.mappers;

import com.calendar.users.application.dtos.UpdateProfileRequest;
import com.calendar.users.domain.models.ProfileUpdate;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface UpdateProfileRequestMapper {

    ProfileUpdate toProfileUpdate(UpdateProfileRequest request);
}
