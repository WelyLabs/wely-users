package com.calendar.users.infrastructure.social.adapters;

import com.calendar.users.configuration.SocialApiClientConfig;
import com.calendar.users.exception.TechnicalErrorCode;
import com.calendar.users.exception.TechnicalException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** A real HTTP server on a random port: what is checked is what goes on the wire and how the answer is read. */
class SocialApiAdapterTest {

    private static final String FRIEND = "22222222-2222-2222-2222-222222222222";
    private static final String TOKEN = "abc.def.ghi";

    private HttpServer server;
    private SocialApiAdapter adapter;
    private final AtomicReference<String> requestedUri = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private volatile int status = 200;
    private volatile long delayMillis = 0;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestedUri.set(exchange.getRequestURI().toString());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            sleep(delayMillis);
            byte[] bytes = "{\"closeness\":\"FRIEND\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        // A generous timeout: a cold client's first call on a busy CI runner took over 300ms.
        adapter = adapterWithTimeout(Duration.ofSeconds(5));
    }

    private SocialApiAdapter adapterWithTimeout(Duration timeout) {
        return new SocialApiAdapter(new SocialApiClientConfig()
                .socialApiWebClient("http://127.0.0.1:" + server.getAddress().getPort()), timeout);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Mono<Boolean> areFriendsAsCaller() {
        JwtAuthenticationToken caller = new JwtAuthenticationToken(
                Jwt.withTokenValue(TOKEN).header("alg", "none").claim("businessId", "me").build());
        return adapter.areFriends("me", FRIEND)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(caller));
    }

    private static boolean isSocialError(Throwable error) {
        return error instanceof TechnicalException e && e.getErrorCode() == TechnicalErrorCode.SOCIAL_ERROR;
    }

    @Test
    void areFriends_shouldBeTrue_whenSocialFindsTheFriendship() {
        StepVerifier.create(areFriendsAsCaller()).expectNext(true).expectComplete().verify(Duration.ofSeconds(10));

        assertThat(requestedUri.get()).isEqualTo("/social-service/relationships/" + FRIEND);
    }

    @Test
    void areFriends_shouldRelayTheCallersBearerToken() {
        StepVerifier.create(areFriendsAsCaller()).expectNext(true).expectComplete().verify(Duration.ofSeconds(10));

        assertThat(authorization.get()).isEqualTo("Bearer " + TOKEN);
    }

    @Test
    void areFriends_shouldBeFalse_whenSocialAnswers404() {
        status = 404;

        StepVerifier.create(areFriendsAsCaller()).expectNext(false).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void areFriends_shouldReportSocialError_whenSocialAnswers500() {
        status = 500;

        StepVerifier.create(areFriendsAsCaller()).expectErrorMatches(SocialApiAdapterTest::isSocialError).verify(Duration.ofSeconds(10));
    }

    @Test
    void areFriends_shouldKeepTheHttpErrorAsCause_whenSocialAnswers500() {
        status = 500;

        StepVerifier.create(areFriendsAsCaller())
                .expectErrorSatisfies(error -> assertThat(error.getCause())
                        .isInstanceOf(WebClientResponseException.InternalServerError.class))
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void areFriends_shouldReportSocialError_whenSocialAnswers403() {
        status = 403;

        StepVerifier.create(areFriendsAsCaller()).expectErrorMatches(SocialApiAdapterTest::isSocialError).verify(Duration.ofSeconds(10));
    }

    @Test
    void areFriends_shouldReportSocialError_whenSocialTimesOut() {
        delayMillis = 1500;
        adapter = adapterWithTimeout(Duration.ofMillis(300));

        StepVerifier.create(areFriendsAsCaller()).expectErrorMatches(SocialApiAdapterTest::isSocialError).verify(Duration.ofSeconds(10));
    }

    @Test
    void areFriends_shouldKeepTheTimeoutAsCause_whenSocialTimesOut() {
        delayMillis = 1500;
        adapter = adapterWithTimeout(Duration.ofMillis(300));

        StepVerifier.create(areFriendsAsCaller())
                .expectErrorSatisfies(error -> assertThat(error.getCause()).isInstanceOf(TimeoutException.class))
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void areFriends_shouldReportSocialError_whenSocialIsUnreachable() {
        server.stop(0);

        StepVerifier.create(areFriendsAsCaller()).expectErrorMatches(SocialApiAdapterTest::isSocialError).verify(Duration.ofSeconds(10));
    }

    @Test
    void areFriends_shouldRefuseWithoutCalling_whenThereIsNoTokenToRelay() {
        StepVerifier.create(adapter.areFriends("me", FRIEND))
                .expectErrorSatisfies(error -> {
                    assertThat(isSocialError(error)).isTrue();
                    assertThat(error.getCause())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage("no authenticated request to relay");
                })
                .verify(Duration.ofSeconds(10));

        assertThat(requestedUri.get()).isNull();
    }

    @Test
    void areFriends_shouldNotLeakTheRelayedToken_whenSocialFails() {
        // The caller's token is relayed to wely-social: a failure's logged chain must not carry it.
        status = 500;

        StepVerifier.create(areFriendsAsCaller())
                .expectErrorSatisfies(error -> {
                    StringWriter trace = new StringWriter();
                    error.printStackTrace(new PrintWriter(trace));
                    assertThat(trace.toString()).doesNotContain(TOKEN);
                })
                .verify(Duration.ofSeconds(10));
    }
}
