package com.calendar.users.infrastructure.social.adapters;

import com.calendar.users.domain.ports.FriendshipChecker;
import com.calendar.users.exception.TechnicalErrorCode;
import com.calendar.users.exception.TechnicalException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Asks wely-social whether the caller is friends with a user, on the caller's own token:
 * wely-social answers for whoever the token names, so the answer cannot be about anyone else.
 */
@Slf4j
@Component
public class SocialApiAdapter implements FriendshipChecker {

    static final String FRIENDSHIP_PATH = "/social-service/relationships/{userId}";

    private final WebClient client;
    private final Duration timeout;

    public SocialApiAdapter(@Qualifier("socialApiWebClient") WebClient client,
                            @Value("${app.social-api.timeout:5s}") Duration timeout) {
        this.client = client;
        this.timeout = timeout;
    }

    /** 200 means friends, 404 means not; anything else is an outage, never a guess. */
    @Override
    public Mono<Boolean> areFriends(String viewerId, String userId) {
        return bearerToken()
                .flatMap(token -> client.get()
                        .uri(FRIENDSHIP_PATH, userId)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .exchangeToMono(response -> {
                            if (response.statusCode().value() == HttpStatus.NOT_FOUND.value()) {
                                return response.releaseBody().thenReturn(false);
                            }
                            if (response.statusCode().is2xxSuccessful()) {
                                return response.releaseBody().thenReturn(true);
                            }
                            return response.createError();
                        })
                        .timeout(timeout))
                .onErrorMap(error -> !(error instanceof TechnicalException), error -> {
                    log.error("wely-social failure checking a friendship: {}", error.getMessage(), error);
                    return new TechnicalException(TechnicalErrorCode.SOCIAL_ERROR, error);
                });
    }

    /** The caller's own token, relayed: without one there is no way to ask, so refuse rather than skip. */
    private static Mono<String> bearerToken() {
        return ReactiveSecurityContextHolder.getContext()
                .map(context -> ((JwtAuthenticationToken) context.getAuthentication()).getToken().getTokenValue())
                .map(token -> "Bearer " + token)
                .switchIfEmpty(Mono.error(() -> new IllegalStateException("no authenticated request to relay")));
    }
}
