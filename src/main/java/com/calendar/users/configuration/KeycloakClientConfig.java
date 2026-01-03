package com.calendar.users.configuration;

import com.calendar.users.infrastructure.identity.api.KeycloakAdminApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServerOAuth2AuthorizedClientExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration
public class KeycloakClientConfig {

    private static final String CLIENT_REGISTRATION_ID = "calendar-users-api-client";

    @Bean
    public ReactiveOAuth2AuthorizedClientManager authorizedClientManager(
            ReactiveClientRegistrationRepository clientRegistrationRepository,
            ReactiveOAuth2AuthorizedClientService authorizedClientService) {

        return new AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager(
                clientRegistrationRepository, authorizedClientService);
    }

    @Bean
    public WebClient keycloakAdminWebClient(
            @Value("${keycloak.realm.calendar-app.base-url}") String baseUrl,
            ReactiveOAuth2AuthorizedClientManager authorizedClientManager) {

        var oauth = new ServerOAuth2AuthorizedClientExchangeFilterFunction(authorizedClientManager);

        oauth.setDefaultClientRegistrationId(CLIENT_REGISTRATION_ID);

        return WebClient.builder()
                .baseUrl(baseUrl)
                .filter(oauth)
                .build();
    }

    @Bean
    HttpServiceProxyFactory httpServiceProxyFactory(WebClient keycloakAdminWebClient) {
        return HttpServiceProxyFactory
                .builderFor(WebClientAdapter.create(keycloakAdminWebClient))
                .build();
    }

    @Bean
    public KeycloakAdminApi keycloakAdminApi(HttpServiceProxyFactory factory) {
        return factory.createClient(KeycloakAdminApi.class);
    }
}
