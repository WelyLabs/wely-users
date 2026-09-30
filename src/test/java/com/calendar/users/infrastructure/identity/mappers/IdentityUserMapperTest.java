package com.calendar.users.infrastructure.identity.mappers;

import com.calendar.users.domain.models.IdentityUser;
import com.calendar.users.infrastructure.identity.models.KeycloakUserResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link IdentityUserMapper#toIdentityUser}.
 *
 * <p>The generated implementation rather than a mock: what matters here is that the three
 * fields the domain reads actually arrive, and that the two it does not are left behind at the
 * boundary — which is the whole reason {@code IdentityUser} exists.
 */
class IdentityUserMapperTest {

    private final IdentityUserMapper mapper = new IdentityUserMapperImpl();

    @Test
    @DisplayName("carries the three fields the domain reads")
    void toIdentityUser_shouldMapTheFieldsTheDomainUses() {
        KeycloakUserResponse response =
                new KeycloakUserResponse("alice", "Alice", "Martin", "alice@example.com", true);

        IdentityUser user = mapper.toIdentityUser(response);

        assertThat(user.username()).isEqualTo("alice");
        assertThat(user.firstName()).isEqualTo("Alice");
        assertThat(user.lastName()).isEqualTo("Martin");
    }

    @Test
    @DisplayName("leaves the email and its verification flag at the boundary")
    void toIdentityUser_shouldNotCarryFieldsNoOneReads() {
        // Asserted on the record's shape rather than on a value: a port should not promise data
        // no one consumes, and adding a field here would be a decision, not an oversight.
        assertThat(IdentityUser.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("username", "firstName", "lastName");
    }

    @Test
    @DisplayName("a null response maps to null rather than an empty user")
    void toIdentityUser_shouldMapNullToNull() {
        // The adapter treats an absent user as an empty Mono; a non-null placeholder here would
        // turn "no such user" into a user with three null fields.
        assertThat(mapper.toIdentityUser(null)).isNull();
    }

    @Test
    @DisplayName("absent names come through as null, not as empty strings")
    void toIdentityUser_shouldPreserveAbsentNames() {
        KeycloakUserResponse response =
                new KeycloakUserResponse("bob", null, null, null, false);

        IdentityUser user = mapper.toIdentityUser(response);

        assertThat(user.username()).isEqualTo("bob");
        assertThat(user.firstName()).isNull();
        assertThat(user.lastName()).isNull();
    }
}
