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

@Configuration
@EnableWebFluxSecurity
public class WebFluxSecurityConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String publicIssuer;

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
    private String internalJwkSetUri;

    // @Value("${app.internal-secret}")
    private final String internalSecret = "mon-secret-local-123";

    @Bean
    public SecurityWebFilterChain apiHttpSecurity(ServerHttpSecurity http) {
        return http
                .cors(Customizer.withDefaults())
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .addFilterAt(internalSecretWebFilter(), SecurityWebFiltersOrder.AUTHENTICATION)
                .authorizeExchange((authorize) -> authorize
                        .pathMatchers(HttpMethod.GET, "/user-service/profile/resolve/**").permitAll()
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
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

                if (!internalSecret.equals(headerSecret)) {
                    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                    return exchange.getResponse().setComplete();
                }
            }
            return chain.filter(exchange);
        };
    }
}
