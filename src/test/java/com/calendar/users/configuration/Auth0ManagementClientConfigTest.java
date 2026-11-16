package com.calendar.users.configuration;

import com.calendar.users.exception.RequestIds;
import com.calendar.users.infrastructure.identity.api.Auth0ManagementApi;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers the beans of {@link Auth0ManagementClientConfig}. No bean may contact Auth0 while being
 * built: an Auth0 momentarily unreachable must not stop the service from booting.
 */
class Auth0ManagementClientConfigTest {

    private static final String DOMAIN = "tenant.eu.auth0.com";

    private final Auth0ManagementClientConfig config = new Auth0ManagementClientConfig();

    private final ReactiveClientRegistrationRepository registrations = mock(ReactiveClientRegistrationRepository.class);
    private final InMemoryReactiveOAuth2AuthorizedClientService clients =
            new InMemoryReactiveOAuth2AuthorizedClientService(registrations);

    @Test
    @DisplayName("the client manager uses the service-side client_credentials flow")
    void authorizedClientManager_shouldBeTheServiceVariant() {
        ReactiveOAuth2AuthorizedClientManager manager = config.authorizedClientManager(DOMAIN, registrations, clients);

        assertThat(manager).isInstanceOf(AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager.class);
        verifyNoInteractions(registrations);
    }

    @Test
    @DisplayName("the token request names the Management API audience, and the token is reused until it expires")
    void authorizedClientManager_shouldAskForTheManagementAudienceOnce() throws Exception {
        List<String> tokenRequests = new ArrayList<>();
        HttpServer auth0 = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        auth0.createContext("/oauth/token", exchange -> {
            tokenRequests.add(URLDecoder.decode(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), StandardCharsets.UTF_8));
            byte[] body = "{\"access_token\":\"t\",\"token_type\":\"Bearer\",\"expires_in\":86400}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        auth0.start();
        try {
            var registrationRepository = new InMemoryReactiveClientRegistrationRepository(
                    ClientRegistration.withRegistrationId(Auth0ManagementClientConfig.CLIENT_REGISTRATION_ID)
                            .clientId("wely-users-test")
                            .clientSecret("test-secret")
                            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                            .tokenUri("http://127.0.0.1:" + auth0.getAddress().getPort() + "/oauth/token")
                            .build());
            ReactiveOAuth2AuthorizedClientManager manager = config.authorizedClientManager(DOMAIN,
                    registrationRepository, new InMemoryReactiveOAuth2AuthorizedClientService(registrationRepository));
            OAuth2AuthorizeRequest request = OAuth2AuthorizeRequest
                    .withClientRegistrationId(Auth0ManagementClientConfig.CLIENT_REGISTRATION_ID)
                    .principal("wely-users")
                    .build();

            OAuth2AuthorizedClient first = manager.authorize(request).block(Duration.ofSeconds(10));
            OAuth2AuthorizedClient second = manager.authorize(request).block(Duration.ofSeconds(10));

            assertThat(first.getAccessToken().getTokenValue()).isEqualTo("t");
            assertThat(second.getAccessToken().getTokenValue()).isEqualTo("t");
            assertThat(tokenRequests).hasSize(1);
            assertThat(tokenRequests.getFirst())
                    .contains("grant_type=client_credentials")
                    .contains("audience=https://" + DOMAIN + "/api/v2/");
        } finally {
            auth0.stop(0);
        }
    }

    @Test
    void auth0ManagementWebClient_shouldBeBuiltWithoutContactingAuth0() {
        WebClient client = config.auth0ManagementWebClient(DOMAIN,
                config.authorizedClientManager(DOMAIN, registrations, clients));

        assertThat(client).isNotNull();
        verifyNoInteractions(registrations);
    }

    @Test
    void httpServiceProxyFactory_shouldWrapTheProvidedWebClient() {
        HttpServiceProxyFactory factory = config.httpServiceProxyFactory(WebClient.create());

        assertThat(factory).isNotNull();
    }

    @Test
    @DisplayName("the declarative client calls /api/v2/users with the auth0|... id encoded")
    void auth0ManagementApi_shouldCallTheManagementApiWithAnEncodedId() {
        List<ClientRequest> sent = new ArrayList<>();
        WebClient client = config.auth0ManagementWebClient(DOMAIN, noToken()).mutate()
                .exchangeFunction(request -> {
                    sent.add(request);
                    return Mono.just(ClientResponse.create(HttpStatus.NO_CONTENT).build());
                })
                .build();
        Auth0ManagementApi api = config.auth0ManagementApi(config.httpServiceProxyFactory(client));

        api.deleteSessions("auth0|abc").block(Duration.ofSeconds(10));

        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().url().toString())
                .isEqualTo("https://" + DOMAIN + "/api/v2/users/auth0%7Cabc/sessions");
    }

    @Test
    @DisplayName("Auth0 is an external client: it never receives our request id")
    void auth0ManagementWebClient_shouldNotCarryTheRequestId_evenWhileServingARequest() {
        List<ClientRequest> sent = new ArrayList<>();
        WebClient client = config.auth0ManagementWebClient(DOMAIN, noToken()).mutate()
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

    private static ReactiveOAuth2AuthorizedClientManager noToken() {
        ReactiveOAuth2AuthorizedClientManager manager = mock(ReactiveOAuth2AuthorizedClientManager.class);
        when(manager.authorize(any())).thenReturn(Mono.empty());
        return manager;
    }
}
