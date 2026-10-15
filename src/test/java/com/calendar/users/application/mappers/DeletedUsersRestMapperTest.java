package com.calendar.users.application.mappers;

import com.calendar.users.application.dtos.DeletedUserItem;
import com.calendar.users.domain.models.DeletedUser;
import com.calendar.users.domain.models.DeletedUsersPage;
import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeletedUsersRestMapperTest {

    private static final DeletedUser DELETED = new DeletedUser(
            UUID.fromString("11111111-1111-1111-1111-111111111111"), Instant.parse("2026-10-15T14:03:27.123456Z"));

    private final DeletedUsersRestMapper mapper = new DeletedUsersRestMapperImpl();

    private static String base64Url(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isCursorInvalid(Throwable error) {
        return error instanceof BusinessException business
                && business.getErrorCode() == BusinessErrorCode.DELETED_USERS_CURSOR_INVALID;
    }

    @Test
    void toItem_shouldCopyTheIdAndTime_whenMapping() {
        assertThat(mapper.toItem(DELETED)).isEqualTo(new DeletedUserItem(DELETED.userId(), DELETED.deletedAt()));
    }

    @Test
    void toResponse_shouldEncodeTheNextCursor_whenThereIsOne() {
        var response = mapper.toResponse(new DeletedUsersPage(List.of(DELETED), DELETED));

        assertThat(response.items()).containsExactly(new DeletedUserItem(DELETED.userId(), DELETED.deletedAt()));
        assertThat(response.next()).isEqualTo(base64Url("2026-10-15T14:03:27.123456Z|" + DELETED.userId()));
    }

    @Test
    void toResponse_shouldLeaveNextNull_whenThePageIsTheLast() {
        assertThat(mapper.toResponse(new DeletedUsersPage(List.of(DELETED), null)).next()).isNull();
    }

    @Test
    void decodeCursor_shouldRoundTripAnEncodedCursor_whenGivenOne() {
        assertThat(mapper.decodeCursor(mapper.encodeCursor(DELETED))).isEqualTo(DELETED);
    }

    @Test
    void decodeCursor_shouldReturnNull_whenTheCursorIsBlank() {
        assertThat(mapper.decodeCursor(null)).isNull();
        assertThat(mapper.decodeCursor(" ")).isNull();
    }

    @Test
    void decodeCursor_shouldFailWithCursorInvalid_whenNotBase64Url() {
        assertThatThrownBy(() -> mapper.decodeCursor("not base64!")).matches(DeletedUsersRestMapperTest::isCursorInvalid);
    }

    @Test
    void decodeCursor_shouldFailWithCursorInvalid_whenTheSeparatorIsMissing() {
        assertThatThrownBy(() -> mapper.decodeCursor(base64Url("2026-10-15T14:03:27Z")))
                .matches(DeletedUsersRestMapperTest::isCursorInvalid);
    }

    @Test
    void decodeCursor_shouldFailWithCursorInvalid_whenTheIdIsNotAUuid() {
        assertThatThrownBy(() -> mapper.decodeCursor(base64Url("2026-10-15T14:03:27Z|nope")))
                .matches(DeletedUsersRestMapperTest::isCursorInvalid);
    }

    @Test
    void decodeCursor_shouldFailWithCursorInvalid_whenTheInstantIsMalformed() {
        assertThatThrownBy(() -> mapper.decodeCursor(base64Url("yesterday|" + DELETED.userId())))
                .matches(DeletedUsersRestMapperTest::isCursorInvalid);
    }
}
