package com.calendar.users.application.mappers;

import com.calendar.users.application.dtos.PreferencesRequest;
import com.calendar.users.application.dtos.PreferencesResponse;
import com.calendar.users.domain.models.Theme;
import com.calendar.users.domain.models.UserPreferences;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import static org.assertj.core.api.Assertions.assertThat;

class PreferencesRestMapperTest {

    private final PreferencesRestMapper mapper = Mappers.getMapper(PreferencesRestMapper.class);

    @Test
    void toUserPreferences_shouldCarryTheTheme() {
        assertThat(mapper.toUserPreferences(new PreferencesRequest(Theme.LIGHT)))
                .isEqualTo(new UserPreferences(Theme.LIGHT));
    }

    @Test
    void toUserPreferences_shouldReadNoRequestAsNone() {
        assertThat(mapper.toUserPreferences(null)).isNull();
    }

    @Test
    void toResponse_shouldCarryTheTheme() {
        assertThat(mapper.toResponse(new UserPreferences(Theme.DARK))).isEqualTo(new PreferencesResponse(Theme.DARK));
    }

    @Test
    void toResponse_shouldReadNoPreferencesAsNone() {
        assertThat(mapper.toResponse(null)).isNull();
    }
}
