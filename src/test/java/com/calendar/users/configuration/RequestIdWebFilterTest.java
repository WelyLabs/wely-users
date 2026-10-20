package com.calendar.users.configuration;

import com.calendar.users.exception.RequestIds;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdWebFilterTest {

    private static final String GATEWAY_ID = "abcd1234-5678-90ef-abcd-1234567890ef";

    private final RequestIdWebFilter filter = new RequestIdWebFilter();

    /** Runs the filter and answers the id the rest of the chain saw in the Reactor Context. */
    private static String filterAndReadContext(RequestIdWebFilter filter, MockServerWebExchange exchange) {
        AtomicReference<String> seen = new AtomicReference<>();
        StepVerifier.create(filter.filter(exchange, ignored -> Mono.deferContextual(context -> {
                    seen.set(context.getOrDefault(RequestIds.REQUEST_ID_ATTRIBUTE, null));
                    return Mono.empty();
                })))
                .expectComplete()
                .verify(Duration.ofSeconds(10));
        return seen.get();
    }

    private static MockServerWebExchange withHeader(String value) {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/user-service/profile")
                .header(RequestIds.REQUEST_ID_HEADER, value));
    }

    @Test
    void filter_shouldKeepTheGatewaysId_whenTheHeaderIsValid() {
        MockServerWebExchange exchange = withHeader(GATEWAY_ID);

        String inContext = filterAndReadContext(filter, exchange);

        assertThat(inContext).isEqualTo(GATEWAY_ID);
        assertThat(RequestIds.of(exchange)).isEqualTo(GATEWAY_ID);
        assertThat(exchange.getResponse().getHeaders().getFirst(RequestIds.REQUEST_ID_HEADER)).isEqualTo(GATEWAY_ID);
    }

    @Test
    void filter_shouldFallBackToTheWebFluxId_whenTheHeaderIsMissing() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/user-service/profile"));

        String inContext = filterAndReadContext(filter, exchange);

        assertThat(inContext).isEqualTo(exchange.getRequest().getId());
        assertThat(exchange.getAttributes()).containsEntry(RequestIds.REQUEST_ID_ATTRIBUTE, exchange.getRequest().getId());
        assertThat(exchange.getResponse().getHeaders().getFirst(RequestIds.REQUEST_ID_HEADER))
                .isEqualTo(exchange.getRequest().getId());
    }

    @Test
    void filter_shouldFallBack_whenTheHeaderCarriesForbiddenCharacters() {
        // A forged id must not inject text into the log lines.
        MockServerWebExchange exchange = withHeader("abcd1234\nERROR forged line");

        assertThat(filterAndReadContext(filter, exchange)).isEqualTo(exchange.getRequest().getId());
    }

    @Test
    void filter_shouldFallBack_whenTheHeaderIsOversized() {
        MockServerWebExchange exchange = withHeader("a".repeat(65));

        assertThat(filterAndReadContext(filter, exchange)).isEqualTo(exchange.getRequest().getId());
    }

    @Test
    void filter_shouldFallBack_whenTheHeaderIsTooShort() {
        MockServerWebExchange exchange = withHeader("abc1234");

        assertThat(filterAndReadContext(filter, exchange)).isEqualTo(exchange.getRequest().getId());
    }
}
