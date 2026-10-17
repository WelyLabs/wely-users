package com.calendar.users.application.mappers;

import com.calendar.users.application.dtos.BirthdayResponse;
import com.calendar.users.application.dtos.FriendProfileResponse;
import com.calendar.users.domain.models.FriendProfile;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.MonthDay;

@Mapper(componentModel = "spring")
public interface FriendProfileRestMapper {

    FriendProfileResponse toResponse(FriendProfile profile);

    @Mapping(target = "month", source = "monthValue")
    @Mapping(target = "day", source = "dayOfMonth")
    BirthdayResponse toBirthday(MonthDay birthday);
}
