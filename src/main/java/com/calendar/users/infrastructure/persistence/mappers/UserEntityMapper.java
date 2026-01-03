package com.calendar.users.infrastructure.persistence.mappers;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.infrastructure.persistence.models.entities.UserEntity;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface UserEntityMapper {

    BusinessUser toBusinessUser(UserEntity userEntity);

    UserEntity toUserEntity(BusinessUser businessUser);
}
