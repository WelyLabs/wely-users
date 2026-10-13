package com.calendar.users.application.mappers;

import com.calendar.users.application.dtos.AboutRequest;
import com.calendar.users.application.dtos.AboutResponse;
import com.calendar.users.domain.models.UserAbout;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface AboutRestMapper {

    UserAbout toUserAbout(AboutRequest request);

    AboutResponse toResponse(UserAbout about);
}
