package com.calendar.users.configuration;

import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import com.calendar.users.exception.ProblemAccessDeniedHandler;
import com.calendar.users.exception.ProblemAuthenticationEntryPoint;
import com.calendar.users.exception.ProblemResponseWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.OrServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

@Configuration
@EnableWebFluxSecurity
public class WebFluxSecurityConfig {

    static final String SECRET_HEADER = "X-Internal-Secret";

    /** Called by Keycloak before any JWT exists: provision at registration, resolve while minting a token. */
    static final ServerWebExchangeMatcher KEYCLOAK_CALLBACKS = new OrServerWebExchangeMatcher(
            ServerWebExchangeMatchers.pathMatchers(HttpMethod.GET, "/user-service/profile/resolve/**"),
            ServerWebExchangeMatchers.pathMatchers(HttpMethod.POST, "/user-service/profile/provision"));

    /** Read by the other services' reconciliation, with a secret of its own. */
    static final ServerWebExchangeMatcher DELETED_USERS_READER =
            ServerWebExchangeMatchers.pathMatchers(HttpMethod.GET, "/user-service/internal/**");

    private final String publicIssuer;
    private final String internalJwkSetUri;
    private final String internalSecret;
    private final String deletedUsersReaderSecret;

    /** The internal secret is required at startup; a blank reader secret refuses every call to the listing. */
    public WebFluxSecurityConfig(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String publicIssuer,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String internalJwkSetUri,
            @Value("${app.internal-secret}") String internalSecret,
            @Value("${app.deleted-users.reader-secret:}") String deletedUsersReaderSecret) {
        this.publicIssuer = publicIssuer;
        this.internalJwkSetUri = internalJwkSetUri;
        this.internalSecret = internalSecret;
        this.deletedUsersReaderSecret = deletedUsersReaderSecret;
    }

    @Bean
    public SecurityWebFilterChain apiHttpSecurity(ServerHttpSecurity http,
                                                  ProblemResponseWriter problems,
                                                  ProblemAuthenticationEntryPoint authenticationEntryPoint,
                                                  ProblemAccessDeniedHandler accessDeniedHandler) {
        return http
                .cors(Customizer.withDefaults())
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .addFilterAt(internalSecretWebFilter(problems), SecurityWebFiltersOrder.AUTHENTICATION)
                // Refusals answer in the same problem shape as every other error.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeExchange((authorize) -> authorize
                        // Guarded by their secret in the filter below, which uses the same matchers.
                        .matchers(KEYCLOAK_CALLBACKS, DELETED_USERS_READER).permitAll()
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Kubernetes probes: the kubelet carries no token. Only the two
                        // health groups are opened, not /actuator as a whole.
                        .pathMatchers("/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        // Prometheus scrapes without a token. The Service is ClusterIP only, the
                        // gateway routes /api/v1/<service>/** to /<service>/** and never
                        // /actuator/**, and metrics hold counts and tags, no user data. Only this
                        // endpoint is opened, not /actuator as a whole.
                        .pathMatchers(HttpMethod.GET, "/actuator/prometheus").permitAll()
                        // OpenAPI. These paths are not routed by the gateway — it forwards
                        // /api/v1/<service>/** and /rsocket only — so opening them here makes
                        // the documentation reachable in-cluster and in dev, and nowhere else.
                        .pathMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui/**",
                                      "/swagger-ui.html", "/webjars/swagger-ui/**").permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        .jwt(Customizer.withDefaults()))
                .build();
    }

    @Bean
    public ReactiveJwtDecoder jwtDecoder() {
        NimbusReactiveJwtDecoder jwtDecoder = NimbusReactiveJwtDecoder.withJwkSetUri(internalJwkSetUri).build();

        OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(publicIssuer);
        OAuth2TokenValidator<Jwt> withTimestamp = new JwtTimestampValidator();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(withTimestamp, issuerValidator);

        jwtDecoder.setJwtValidator(validator);
        return jwtDecoder;
    }

    /** 401 problem for an internal path whose secret is missing or wrong; every other request passes through. */
    WebFilter internalSecretWebFilter(ProblemResponseWriter problems) {
        return (exchange, chain) -> {
            if (HttpMethod.OPTIONS.equals(exchange.getRequest().getMethod())) {
                return chain.filter(exchange);
            }
            String candidate = exchange.getRequest().getHeaders().getFirst(SECRET_HEADER);
            return requiredSecret(exchange)
                    .flatMap(required -> required.isEmpty() || secretMatches(candidate, required.get())
                            ? chain.filter(exchange)
                            : problems.write(exchange,
                                    new BusinessException(BusinessErrorCode.AUTHENTICATION_REQUIRED)));
        };
    }

    /** The secret an internal path requires, or empty for any other path. */
    private Mono<Optional<String>> requiredSecret(ServerWebExchange exchange) {
        return KEYCLOAK_CALLBACKS.matches(exchange)
                .flatMap(callback -> callback.isMatch()
                        ? Mono.just(Optional.of(internalSecret))
                        : DELETED_USERS_READER.matches(exchange)
                                .map(reader -> reader.isMatch()
                                        ? Optional.of(deletedUsersReaderSecret)
                                        : Optional.<String>empty()));
    }

    /** Constant-time, so response timing leaks no matching prefix; a blank expected secret matches nothing. */
    private static boolean secretMatches(String candidate, String expected) {
        if (candidate == null || expected == null || expected.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
