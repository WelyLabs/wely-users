package com.calendar.users.application.mappers;

import com.calendar.users.application.dtos.BirthdayResponse;
import com.calendar.users.application.dtos.FriendProfileResponse;
import com.calendar.users.domain.models.FriendProfile;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;
import java.time.MonthDay;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FriendProfileRestMapperTest {

    private final FriendProfileRestMapper mapper = Mappers.getMapper(FriendProfileRestMapper.class);

    @Test
    void toResponse_shouldCarryEveryField() {
        UUID id = UUID.randomUUID();
        LocalDateTime joined = LocalDateTime.of(2026, 1, 15, 10, 30);

        assertThat(mapper.toResponse(new FriendProfile(id, "lea", 4242, "Léa", "Martin", joined, "Partante",
                List.of("Rando"), MonthDay.of(3, 12))))
                .isEqualTo(new FriendProfileResponse(id, "lea", 4242, "Léa", "Martin", joined, "Partante",
                        List.of("Rando"), new BirthdayResponse(3, 12)));
    }

    @Test
    void toBirthday_shouldGiveTheMonthAndDay_whenABirthdayIsKnown() {
        assertThat(mapper.toBirthday(MonthDay.of(2, 29))).isEqualTo(new BirthdayResponse(2, 29));
    }

    @Test
    void toBirthday_shouldGiveNull_whenNoBirthdayIsKnown() {
        assertThat(mapper.toBirthday(null)).isNull();
    }
}
