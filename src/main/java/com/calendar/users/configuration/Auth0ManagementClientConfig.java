package com.calendar.users.configuration;

import com.calendar.users.infrastructure.identity.api.Auth0ManagementApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.WebClientReactiveClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServerOAuth2AuthorizedClientExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * The client of Auth0's Management API: a client_credentials token, kept by the authorized client
 * service until shortly before it expires, then {@code /api/v2} calls carrying it.
 */
@Configuration
public class Auth0ManagementClientConfig {

    static final String CLIENT_REGISTRATION_ID = "auth0-management";
    private static final String AUDIENCE = "audience";

    @Bean
    public ReactiveOAuth2AuthorizedClientManager authorizedClientManager(
            @Value("${app.auth0.domain}") String domain,
            ReactiveClientRegistrationRepository clientRegistrationRepository,
            ReactiveOAuth2AuthorizedClientService authorizedClientService) {

        // Auth0 needs the audience in the token request, or it refuses the grant.
        var tokenClient = new WebClientReactiveClientCredentialsTokenResponseClient();
        tokenClient.setParametersCustomizer(parameters -> parameters.set(AUDIENCE, managementAudience(domain)));

        var manager = new AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager(
                clientRegistrationRepository, authorizedClientService);
        manager.setAuthorizedClientProvider(ReactiveOAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials(grant -> grant.accessTokenResponseClient(tokenClient))
                .build());
        return manager;
    }

    @Bean
    public WebClient auth0ManagementWebClient(
            @Value("${app.auth0.domain}") String domain,
            ReactiveOAuth2AuthorizedClientManager authorizedClientManager) {

        var oauth = new ServerOAuth2AuthorizedClientExchangeFilterFunction(authorizedClientManager);

        oauth.setDefaultClientRegistrationId(CLIENT_REGISTRATION_ID);

        return WebClient.builder()
                .baseUrl("https://" + domain + "/api/v2")
                .filter(oauth)
                .build();
    }

    @Bean
    HttpServiceProxyFactory httpServiceProxyFactory(WebClient auth0ManagementWebClient) {
        return HttpServiceProxyFactory
                .builderFor(WebClientAdapter.create(auth0ManagementWebClient))
                .build();
    }

    @Bean
    public Auth0ManagementApi auth0ManagementApi(HttpServiceProxyFactory factory) {
        return factory.createClient(Auth0ManagementApi.class);
    }

    static String managementAudience(String domain) {
        return "https://" + domain + "/api/v2/";
    }
}
