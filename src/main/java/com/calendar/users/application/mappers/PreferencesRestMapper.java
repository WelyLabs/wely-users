package com.calendar.users.application.mappers;

import com.calendar.users.application.dtos.PreferencesRequest;
import com.calendar.users.application.dtos.PreferencesResponse;
import com.calendar.users.domain.models.UserPreferences;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface PreferencesRestMapper {

    UserPreferences toUserPreferences(PreferencesRequest request);

    PreferencesResponse toResponse(UserPreferences preferences);
}
