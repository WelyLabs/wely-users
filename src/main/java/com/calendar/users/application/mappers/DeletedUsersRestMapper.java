package com.calendar.users.application.mappers;

import com.calendar.users.application.dtos.DeletedUserItem;
import com.calendar.users.application.dtos.DeletedUsersPageResponse;
import com.calendar.users.domain.models.DeletedUser;
import com.calendar.users.domain.models.DeletedUsersPage;
import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import org.mapstruct.Mapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** Tombstones to the internal listing, with an opaque base64url cursor of "instant|uuid". */
@Mapper(componentModel = "spring")
public interface DeletedUsersRestMapper {

    String CURSOR_SEPARATOR = "|";

    DeletedUserItem toItem(DeletedUser user);

    List<DeletedUserItem> toItems(List<DeletedUser> users);

    default DeletedUsersPageResponse toResponse(DeletedUsersPage page) {
        return new DeletedUsersPageResponse(toItems(page.items()), encodeCursor(page.next()));
    }

    default String encodeCursor(DeletedUser cursor) {
        if (cursor == null) {
            return null;
        }
        String raw = cursor.deletedAt() + CURSOR_SEPARATOR + cursor.userId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** Null or blank is the first page; anything this service did not issue is USR-BUS-013. */
    default DeletedUser decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = raw.indexOf(CURSOR_SEPARATOR);
            if (separator < 0) {
                throw new IllegalArgumentException("No separator in the cursor");
            }
            return new DeletedUser(UUID.fromString(raw.substring(separator + 1)),
                    Instant.parse(raw.substring(0, separator)));
        } catch (IllegalArgumentException | DateTimeParseException invalid) {
            throw new BusinessException(BusinessErrorCode.DELETED_USERS_CURSOR_INVALID);
        }
    }
}
