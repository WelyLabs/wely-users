package com.calendar.users.infrastructure.persistence.mappers;

import com.calendar.users.domain.models.AccountDeletionJob;
import com.calendar.users.domain.models.DeletedUser;
import com.calendar.users.infrastructure.persistence.models.DeletedUserRow;
import com.calendar.users.infrastructure.persistence.models.entities.AccountDeletionEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.Instant;
import java.time.OffsetDateTime;

@Mapper(componentModel = "spring")
public interface AccountDeletionEntityMapper {

    @Mapping(target = "subjectId", source = "keycloakId")
    @Mapping(target = "identityDeleted", expression = "java(entity.getIdentityDeletedAt() != null)")
    @Mapping(target = "mediaDeleted", expression = "java(entity.getMediaDeletedAt() != null)")
    @Mapping(target = "attempts", source = "attempts", defaultValue = "0")
    AccountDeletionJob toJob(AccountDeletionEntity entity);

    @Mapping(target = "userId", source = "id")
    DeletedUser toDeletedUser(DeletedUserRow row);

    default Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
