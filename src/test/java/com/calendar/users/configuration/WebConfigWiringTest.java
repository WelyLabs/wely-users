package com.calendar.users.configuration;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.models.DeletedUser;
import com.calendar.users.domain.ports.IdentityProvider;
import com.calendar.users.domain.ports.UserRepository;
import com.calendar.users.exception.GlobalErrorHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.codec.multipart.DefaultPartHttpMessageReader;
import org.springframework.http.codec.multipart.MultipartHttpMessageReader;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.unit.DataSize;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/**
 * What {@link WebConfig} leaves to Boot's WebFlux auto-configuration, in the real context.
 *
 * <p>{@code @EnableWebFlux} used to sit on {@link WebConfig}. It switches Boot's WebFlux
 * auto-configuration off entirely, so every {@code spring.webflux.*} property was silently
 * ignored and the codecs were Spring's defaults rather than Boot's. These pin down what the
 * API looks like on the wire, so removing it is proven to change nothing a client sees.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WebConfigWiringTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired private ApplicationContext context;

    @MockitoBean private ReactiveJwtDecoder jwtDecoder;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private IdentityProvider identityProvider;

    private WebTestClient client() {
        return WebTestClient.bindToApplicationContext(context)
                .apply(springSecurity())
                .configureClient()
                .responseTimeout(Duration.ofSeconds(30))
                .build()
                .mutateWith(mockJwt().jwt(jwt -> jwt.claim("businessId", USER_ID.toString())));
    }

    @Test
    @DisplayName("spring.webflux.multipart.max-in-memory-size is applied: a picture part stays in memory")
    void multipartReader_shouldUseTheConfiguredInMemoryLimit() {
        // The root filesystem is read-only: a part above the limit would be spilled to disk and fail.
        ServerCodecConfigurer codecs = context.getBean(ServerCodecConfigurer.class);

        DefaultPartHttpMessageReader partReader = codecs.getReaders().stream()
                .filter(MultipartHttpMessageReader.class::isInstance)
                .map(reader -> (DefaultPartHttpMessageReader) ((MultipartHttpMessageReader) reader).getPartReader())
                .findFirst()
                .orElseThrow();

        assertThat(partReader.getMaxInMemorySize()).isEqualTo((int) DataSize.ofMegabytes(6).toBytes());
    }

    // The expected bodies below were recorded with @EnableWebFlux still in place: removing it
    // changed no byte of them.

    @Test
    @DisplayName("dates are ISO strings, nulls are written, the shape of the profile is unchanged")
    void readProfile_shouldSerializeDatesAndNullsAsBefore() {
        when(userRepository.getBusinessUserByUserId(any(UUID.class))).thenReturn(Mono.just(new BusinessUser(
                USER_ID, "theo", 4271, "Theo", "B", null,
                LocalDateTime.of(2026, 1, 2, 3, 4, 5, 123_456_789), LocalDate.of(1990, 5, 6), true)));

        client().get().uri("/user-service/profile")
                .exchange()
                .expectStatus().isOk()
                .expectBody().json("""
                        {"id":"11111111-1111-1111-1111-111111111111","userName":"theo","hashtag":4271,
                         "firstName":"Theo","lastName":"B","profilePicUrl":null,
                         "joinedDate":"2026-01-02T03:04:05.123456789","birthDate":"1990-05-06",
                         "registrationCompleted":true}""", JsonCompareMode.STRICT);
    }

    @Test
    @DisplayName("an Instant is an ISO string in UTC")
    void deletedUsersListing_shouldSerializeInstantsAsBefore() {
        when(userRepository.findDeleted(null, 501)).thenReturn(Flux.just(
                new DeletedUser(USER_ID, Instant.parse("2026-10-15T14:03:27.123456Z"))));

        client().get().uri("/user-service/internal/deleted-users")
                .header("X-Internal-Secret", "test-reader-secret")
                .exchange()
                .expectStatus().isOk()
                .expectBody().json("""
                        {"items":[{"userId":"11111111-1111-1111-1111-111111111111",
                                   "deletedAt":"2026-10-15T14:03:27.123456Z"}],
                         "next":null}""", JsonCompareMode.STRICT);
    }

    @Test
    @DisplayName("an unknown property in a request body is ignored, not refused")
    void deletedUsers_shouldIgnoreUnknownProperties() {
        when(userRepository.findDeletedIds(anyCollection())).thenReturn(Flux.just(USER_ID));

        client().post().uri("/user-service/users/deleted")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"userIds\":[\"" + USER_ID + "\"],\"unknown\":true}")
                .exchange()
                .expectStatus().isOk()
                .expectBody().json("{\"deletedIds\":[\"" + USER_ID + "\"]}", JsonCompareMode.STRICT);
    }

    @Test
    @DisplayName("a date that is not ISO is refused as unreadable, as before")
    void registration_shouldRefuseANonIsoDate() {
        client().put().uri("/user-service/profile/registration")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"birthDate\":\"06/05/1990\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo(GlobalErrorHandler.REQUEST_REFUSED_CODE);
    }
}
