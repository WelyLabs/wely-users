package com.calendar.users.configuration;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.reactive.config.PathMatchConfigurer;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * Adds this service's path prefix on top of Boot's WebFlux auto-configuration.
 *
 * <p>Deliberately not {@code @EnableWebFlux}: that annotation switches Boot's WebFlux
 * auto-configuration off entirely, and with it every {@code spring.webflux.*} property — the
 * multipart in-memory limit was silently 256 KB instead of the configured 6 MB — and Boot's
 * codecs. Boot still applies this {@link WebFluxConfigurer}.
 */
@Configuration
public class WebConfig implements WebFluxConfigurer {

    @Override
    public void configurePathMatching(PathMatchConfigurer configurer) {
        // Scoped to this service's own controllers by package, not by the @RestController
        // annotation: that predicate also matched springdoc's OpenApiWebfluxResource, which
        // moved the specification to /user-service/v3/api-docs and left /v3/api-docs a 404.
        configurer.addPathPrefix(
                "/user-service",
                HandlerTypePredicate.forBasePackage("com.calendar.users.application.rest"));
    }
}