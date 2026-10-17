package com.calendar.users.infrastructure.messaging.mappers;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.infrastructure.messaging.models.UserBirthdayEventDTO;
import com.calendar.users.infrastructure.messaging.models.UserCreatedEventDTO;
import com.calendar.users.infrastructure.messaging.models.UserModifiedEventDTO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface KafkaDataMapper {

    @Mapping(target = "userId", source = "id")
    UserCreatedEventDTO toUserCreatedEventDTO(BusinessUser businessUser);

    @Mapping(target = "userId", source = "id")
    UserModifiedEventDTO toUserModifiedEventDTO(BusinessUser businessUser);

    /** Only for a user with a birth date. */
    @Mapping(target = "userId", source = "id")
    @Mapping(target = "displayName", expression = "java(businessUser.displayName())")
    @Mapping(target = "month", expression = "java(businessUser.birthDate().getMonthValue())")
    @Mapping(target = "day", expression = "java(businessUser.birthDate().getDayOfMonth())")
    UserBirthdayEventDTO toUserBirthdayEventDTO(BusinessUser businessUser);
}
