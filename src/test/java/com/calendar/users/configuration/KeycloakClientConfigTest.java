package com.calendar.users.configuration;

import com.calendar.users.exception.RequestIds;
import com.calendar.users.infrastructure.identity.api.KeycloakAdminApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers the four beans of {@link KeycloakClientConfig}, which assemble the declarative
 * client used to read users from Keycloak's Admin API.
 *
 * <p>No bean here may contact Keycloak while being built: this configuration is created
 * at startup, and a Keycloak that is momentarily unreachable must not stop the service
 * from booting.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KeycloakClientConfigTest {

    private static final String BASE_URL = "http://wely-auth-service:8080/admin/realms/wely-realm";

    private final KeycloakClientConfig config = new KeycloakClientConfig();

    @Mock private ReactiveClientRegistrationRepository clientRegistrationRepository;
    @Mock private ReactiveOAuth2AuthorizedClientService authorizedClientService;

    @Test
    @DisplayName("the client manager uses the service-side client_credentials flow")
    void authorizedClientManager_shouldBeTheServiceVariant() {
        ReactiveOAuth2AuthorizedClientManager manager =
                config.authorizedClientManager(clientRegistrationRepository, authorizedClientService);

        // The AuthorizedClientService variant is the one that fits outside a user
        // request: the service authenticates on its own behalf.
        assertThat(manager)
                .isInstanceOf(AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager.class);
        verifyNoInteractions(clientRegistrationRepository, authorizedClientService);
    }

    @Test
    void keycloakAdminWebClient_shouldBeBuiltWithoutContactingKeycloak() {
        WebClient client = config.keycloakAdminWebClient(
                BASE_URL,
                config.authorizedClientManager(clientRegistrationRepository, authorizedClientService));

        assertThat(client).isNotNull();
        verifyNoInteractions(clientRegistrationRepository, authorizedClientService);
    }

    @Test
    void httpServiceProxyFactory_shouldWrapTheProvidedWebClient() {
        WebClient client = config.keycloakAdminWebClient(
                BASE_URL,
                config.authorizedClientManager(clientRegistrationRepository, authorizedClientService));

        HttpServiceProxyFactory factory = config.httpServiceProxyFactory(client);

        assertThat(factory).isNotNull();
    }

    @Test
    @DisplayName("the declarative client implements the annotated interface")
    void keycloakAdminApi_shouldBeAProxyOfTheDeclaredInterface() {
        WebClient client = config.keycloakAdminWebClient(
                BASE_URL,
                config.authorizedClientManager(clientRegistrationRepository, authorizedClientService));

        KeycloakAdminApi api = config.keycloakAdminApi(config.httpServiceProxyFactory(client));

        assertThat(api).isNotNull();
        assertThat(api).isInstanceOf(KeycloakAdminApi.class);
    }

    @Test
    @DisplayName("Keycloak is an external client: it never receives our request id")
    void keycloakAdminWebClient_shouldNotCarryTheRequestId_evenWhileServingARequest() {
        ReactiveOAuth2AuthorizedClientManager noToken = mock(ReactiveOAuth2AuthorizedClientManager.class);
        when(noToken.authorize(any())).thenReturn(Mono.empty());
        List<ClientRequest> sent = new ArrayList<>();
        WebClient client = config.keycloakAdminWebClient(BASE_URL, noToken).mutate()
                .exchangeFunction(request -> {
                    sent.add(request);
                    return Mono.just(ClientResponse.create(HttpStatus.OK).build());
                })
                .build();

        client.get().uri("/users").retrieve().toBodilessEntity()
                .contextWrite(Context.of(RequestIds.REQUEST_ID_ATTRIBUTE, "abcd1234-req"))
                .block(Duration.ofSeconds(10));

        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().headers().containsHeader(RequestIds.REQUEST_ID_HEADER)).isFalse();
    }
}
