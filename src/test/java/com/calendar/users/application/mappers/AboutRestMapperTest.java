package com.calendar.users.application.mappers;

import com.calendar.users.application.dtos.AboutRequest;
import com.calendar.users.application.dtos.AboutResponse;
import com.calendar.users.domain.models.UserAbout;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AboutRestMapperTest {

    private final AboutRestMapper mapper = Mappers.getMapper(AboutRestMapper.class);

    @Test
    void toUserAbout_shouldGoThroughTheDomainsTidying() {
        assertThat(mapper.toUserAbout(new AboutRequest(" Partante ", List.of("Rando", " rando "))))
                .isEqualTo(new UserAbout("Partante", List.of("Rando")));
    }

    @Test
    void toUserAbout_shouldAcceptAnEmptyForm() {
        assertThat(mapper.toUserAbout(new AboutRequest(null, null))).isEqualTo(UserAbout.EMPTY);
    }

    @Test
    void toResponse_shouldCarryBothFields() {
        assertThat(mapper.toResponse(new UserAbout("Partante", List.of("Ciné"))))
                .isEqualTo(new AboutResponse("Partante", List.of("Ciné")));
    }
}
