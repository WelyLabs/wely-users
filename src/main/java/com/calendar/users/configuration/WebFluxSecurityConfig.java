package com.calendar.users.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.server.WebFilter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Configuration
@EnableWebFluxSecurity
public class WebFluxSecurityConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String publicIssuer;

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
    private String internalJwkSetUri;

    /**
     * Shared secret guarding the internal resolve endpoint, which Keycloak's
     * protocol mapper calls while minting a token — before any JWT exists.
     * Injected from the environment; the application fails to start if unset.
     */
    @Value("${app.internal-secret}")
    private String internalSecret;

    @Bean
    public SecurityWebFilterChain apiHttpSecurity(ServerHttpSecurity http) {
        return http
                .cors(Customizer.withDefaults())
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .addFilterAt(internalSecretWebFilter(), SecurityWebFiltersOrder.AUTHENTICATION)
                .authorizeExchange((authorize) -> authorize
                        .pathMatchers(HttpMethod.GET, "/user-service/profile/resolve/**").permitAll()
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Kubernetes probes: the kubelet carries no token. Only the two
                        // health groups are opened, not /actuator as a whole.
                        .pathMatchers("/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
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

    private WebFilter internalSecretWebFilter() {
        return (exchange, chain) -> {
            String path = exchange.getRequest().getURI().getPath();

            if (HttpMethod.OPTIONS.equals(exchange.getRequest().getMethod())) {
                return chain.filter(exchange);
            }

            if (path.startsWith("/user-service/profile/resolve/")) {
                String headerSecret = exchange.getRequest().getHeaders().getFirst("X-Internal-Secret");

                if (!secretMatches(headerSecret)) {
                    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                    return exchange.getResponse().setComplete();
                }
            }
            return chain.filter(exchange);
        };
    }

    /**
     * Constant-time comparison: a plain {@code equals} returns as soon as two
     * bytes differ, which leaks the length of the matching prefix to a caller
     * timing the responses.
     */
    private boolean secretMatches(String candidate) {
        if (candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.UTF_8),
                internalSecret.getBytes(StandardCharsets.UTF_8));
    }
}
