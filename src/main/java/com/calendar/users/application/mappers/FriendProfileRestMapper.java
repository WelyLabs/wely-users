package com.calendar.users.application.mappers;

import com.calendar.users.application.dtos.FriendProfileResponse;
import com.calendar.users.domain.models.FriendProfile;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface FriendProfileRestMapper {

    FriendProfileResponse toResponse(FriendProfile profile);
}
