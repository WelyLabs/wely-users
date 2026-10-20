package com.calendar.users.infrastructure.media.adapters;

import com.calendar.users.configuration.MediaApiClientConfig;
import com.calendar.users.domain.models.MediaProfile;
import com.calendar.users.domain.models.StoredMedia;
import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import com.calendar.users.exception.TechnicalErrorCode;
import com.calendar.users.exception.TechnicalException;
import com.calendar.users.infrastructure.media.mappers.MediaApiMapperImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ConnectException;
import java.time.Duration;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** Requests go to a stub exchange: what is checked is what the adapter sends and how it reads the answer. */
class MediaApiAdapterTest {

    private static final String BASE_URL = "http://wely-media:8085";
    private static final String SECRET = "media-secret";
    private static final String KEY = "avatars/u1/a.jpg";
    private static final byte[] UPLOAD = {1, 2, 3};

    private final List<ClientRequest> sent = new ArrayList<>();
    private Function<ClientRequest, Mono<ClientResponse>> answer;
    private MediaApiAdapter adapter;

    @BeforeEach
    void setUp() {
        WebClient client = new MediaApiClientConfig().mediaApiWebClient(BASE_URL, SECRET).mutate()
                .exchangeFunction(request -> {
                    sent.add(request);
                    return answer.apply(request);
                })
                .build();
        adapter = new MediaApiAdapter(client, new MediaApiMapperImpl());
    }

    private void answerJson(HttpStatus status, String json) {
        answer = request -> Mono.just(ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(json)
                .build());
    }

    private void answerProblem(HttpStatus status, String code) {
        answer = request -> Mono.just(ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE)
                .body("{\"status\":" + status.value() + ",\"code\":\"" + code + "\"}")
                .build());
    }

    private void answerConnectionRefused() {
        answer = request -> Mono.error(new WebClientRequestException(new ConnectException("Connection refused"),
                request.method(), request.url(), new HttpHeaders()));
    }

    private static MockClientHttpRequest written(ClientRequest request) {
        MockClientHttpRequest out = new MockClientHttpRequest(request.method(), request.url());
        request.writeTo(out, ExchangeStrategies.withDefaults()).block();
        return out;
    }

    private static boolean hasCode(Throwable error, BusinessErrorCode code) {
        return error instanceof BusinessException business && business.getErrorCode() == code;
    }

    private static boolean isMediaError(Throwable error) {
        return error instanceof TechnicalException technical && technical.getErrorCode() == TechnicalErrorCode.MEDIA_ERROR;
    }

    // --- store ---------------------------------------------------------------

    @Test
    void store_shouldAnswerTheStoredPicture_whenWelyMediaAccepts() {
        answerJson(HttpStatus.CREATED, "{\"key\":\"" + KEY + "\",\"width\":512,\"height\":512,\"contentType\":\"image/jpeg\"}");

        StepVerifier.create(adapter.store(KEY, UPLOAD, MediaProfile.AVATAR))
                .expectNext(new StoredMedia(KEY, 512, 512))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void store_shouldPostTheFileKeyAndProfileAsMultipart_withTheSecret() {
        answerJson(HttpStatus.CREATED, "{\"key\":\"" + KEY + "\",\"width\":512,\"height\":512,\"contentType\":\"image/jpeg\"}");

        adapter.store(KEY, UPLOAD, MediaProfile.AVATAR).block();

        ClientRequest request = sent.getFirst();
        assertThat(request.method()).isEqualTo(HttpMethod.POST);
        assertThat(request.url()).isEqualTo(URI.create(BASE_URL + "/internal/media"));
        assertThat(request.headers().getFirst("X-Internal-Secret")).isEqualTo(SECRET);
        MockClientHttpRequest body = written(request);
        assertThat(body.getHeaders().getContentType()).isNotNull();
        assertThat(body.getHeaders().getContentType().isCompatibleWith(MediaType.MULTIPART_FORM_DATA)).isTrue();
        assertThat(body.getBodyAsString().block())
                .contains("name=\"file\"; filename=\"upload\"")
                .contains("name=\"key\"")
                .contains(KEY)
                .contains("name=\"profile\"")
                .contains("AVATAR");
    }

    @Test
    void store_shouldFailAsPictureInvalid_whenWelyMediaRefusesTheImage() {
        answerProblem(HttpStatus.BAD_REQUEST, "MED-BUS-001");

        StepVerifier.create(adapter.store(KEY, UPLOAD, MediaProfile.AVATAR))
                .expectErrorMatches(error -> hasCode(error, BusinessErrorCode.PICTURE_INVALID))
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void store_shouldFailAsPictureTooLarge_whenWelyMediaRefusesTheSize() {
        answerProblem(HttpStatus.CONTENT_TOO_LARGE, "MED-BUS-002");

        StepVerifier.create(adapter.store(KEY, UPLOAD, MediaProfile.AVATAR))
                .expectErrorMatches(error -> hasCode(error, BusinessErrorCode.PICTURE_TOO_LARGE))
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void store_shouldFailAsMediaError_whenTheKeyIsOutsideThePrefix() {
        answerProblem(HttpStatus.FORBIDDEN, "MED-BUS-003");

        StepVerifier.create(adapter.store(KEY, UPLOAD, MediaProfile.AVATAR))
                .expectErrorMatches(MediaApiAdapterTest::isMediaError)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void store_shouldFailAsMediaError_whenTheSecretIsRefused() {
        answerProblem(HttpStatus.UNAUTHORIZED, "MED-REQ-001");

        StepVerifier.create(adapter.store(KEY, UPLOAD, MediaProfile.AVATAR))
                .expectErrorMatches(MediaApiAdapterTest::isMediaError)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void store_shouldFailAsMediaError_whenTheStorageIsDown() {
        answerProblem(HttpStatus.BAD_GATEWAY, "MED-TEC-001");

        StepVerifier.create(adapter.store(KEY, UPLOAD, MediaProfile.AVATAR))
                .expectErrorMatches(MediaApiAdapterTest::isMediaError)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void store_shouldKeepWelyMediasAnswerAsCause_whenTheStorageIsDown() {
        answerProblem(HttpStatus.BAD_GATEWAY, "MED-TEC-001");

        StepVerifier.create(adapter.store(KEY, UPLOAD, MediaProfile.AVATAR))
                .expectErrorSatisfies(error -> assertThat(error.getCause())
                        .isInstanceOf(WebClientResponseException.BadGateway.class))
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void store_shouldFailAsMediaError_whenTheErrorBodyIsNotAProblem() {
        answer = request -> Mono.just(ClientResponse.create(HttpStatus.BAD_REQUEST)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE)
                .body("<html>bad gateway</html>")
                .build());

        StepVerifier.create(adapter.store(KEY, UPLOAD, MediaProfile.AVATAR))
                .expectErrorMatches(MediaApiAdapterTest::isMediaError)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void store_shouldFailAsMediaError_whenWelyMediaIsUnreachable() {
        answerConnectionRefused();

        StepVerifier.create(adapter.store(KEY, UPLOAD, MediaProfile.AVATAR))
                .expectErrorMatches(MediaApiAdapterTest::isMediaError)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void store_shouldKeepTheConnectionFailureAsCause_whenWelyMediaIsUnreachable() {
        answerConnectionRefused();

        StepVerifier.create(adapter.store(KEY, UPLOAD, MediaProfile.AVATAR))
                .expectErrorSatisfies(error -> assertThat(error.getCause())
                        .isInstanceOf(WebClientRequestException.class)
                        .hasRootCauseInstanceOf(ConnectException.class))
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void store_shouldFailAsMediaError_whenWelyMediaAnswersNoBody() {
        answer = request -> Mono.just(ClientResponse.create(HttpStatus.CREATED).build());

        StepVerifier.create(adapter.store(KEY, UPLOAD, MediaProfile.AVATAR))
                .expectErrorSatisfies(error -> {
                    assertThat(isMediaError(error)).isTrue();
                    assertThat(error.getCause()).isInstanceOf(IllegalStateException.class);
                })
                .verify(Duration.ofSeconds(10));
    }

    // --- delete --------------------------------------------------------------

    @Test
    void delete_shouldSendTheKeyAsAQueryParameter_withTheSecret() {
        answer = request -> Mono.just(ClientResponse.create(HttpStatus.NO_CONTENT).build());

        StepVerifier.create(adapter.delete(KEY)).expectComplete().verify(Duration.ofSeconds(10));

        ClientRequest request = sent.getFirst();
        assertThat(request.method()).isEqualTo(HttpMethod.DELETE);
        assertThat(request.url().getPath()).isEqualTo("/internal/media");
        assertThat(request.url().getQuery()).isEqualTo("key=" + KEY);
        assertThat(request.headers().getFirst("X-Internal-Secret")).isEqualTo(SECRET);
    }

    @Test
    void delete_shouldFailAsMediaError_whenTheStorageIsDown() {
        answerProblem(HttpStatus.BAD_GATEWAY, "MED-TEC-001");

        StepVerifier.create(adapter.delete(KEY))
                .expectErrorMatches(MediaApiAdapterTest::isMediaError)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void delete_shouldFailAsMediaError_whenWelyMediaIsUnreachable() {
        answerConnectionRefused();

        StepVerifier.create(adapter.delete(KEY))
                .expectErrorMatches(MediaApiAdapterTest::isMediaError)
                .verify(Duration.ofSeconds(10));
    }

    // --- deletePrefix ---------------------------------------------------------

    private static final String AVATARS = "avatars/u1/";

    @Test
    void deletePrefix_shouldSendThePrefixAsAQueryParameterWithTheSecret_whenCalled() {
        answer = request -> Mono.just(ClientResponse.create(HttpStatus.NO_CONTENT).build());

        StepVerifier.create(adapter.deletePrefix(AVATARS)).expectComplete().verify(Duration.ofSeconds(10));

        ClientRequest request = sent.getFirst();
        assertThat(request.method()).isEqualTo(HttpMethod.DELETE);
        assertThat(request.url().getPath()).isEqualTo("/internal/media/prefix");
        assertThat(request.url().getQuery()).isEqualTo("prefix=" + AVATARS);
        assertThat(request.headers().getFirst("X-Internal-Secret")).isEqualTo(SECRET);
    }

    @Test
    void deletePrefix_shouldComplete_whenWelyMediaAnswers204() {
        answer = request -> Mono.just(ClientResponse.create(HttpStatus.NO_CONTENT).build());

        StepVerifier.create(adapter.deletePrefix(AVATARS)).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void deletePrefix_shouldFailAsMediaError_whenWelyMediaRefusesThePrefix() {
        answerProblem(HttpStatus.FORBIDDEN, "MED-BUS-003");

        StepVerifier.create(adapter.deletePrefix("avatars/"))
                .expectErrorMatches(MediaApiAdapterTest::isMediaError)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void deletePrefix_shouldFailAsMediaError_whenTheStorageIsDown() {
        answerProblem(HttpStatus.BAD_GATEWAY, "MED-TEC-001");

        StepVerifier.create(adapter.deletePrefix(AVATARS))
                .expectErrorMatches(MediaApiAdapterTest::isMediaError)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void deletePrefix_shouldFailAsMediaError_whenWelyMediaIsUnreachable() {
        answerConnectionRefused();

        StepVerifier.create(adapter.deletePrefix(AVATARS))
                .expectErrorMatches(MediaApiAdapterTest::isMediaError)
                .verify(Duration.ofSeconds(10));
    }

    // --- urls ----------------------------------------------------------------

    @Test
    void urls_shouldAnswerTheSignedUrlsByKey() {
        answerJson(HttpStatus.OK, "{\"urls\":{\"" + KEY + "\":\"https://media/signed\"}}");

        StepVerifier.create(adapter.urls(List.of(KEY)))
                .expectNext(Map.of(KEY, "https://media/signed"))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void urls_shouldPostTheKeysAndTheTtlAsJson_withTheSecret() {
        answerJson(HttpStatus.OK, "{\"urls\":{}}");

        adapter.urls(List.of(KEY, "avatars/u2/b.jpg")).block();

        ClientRequest request = sent.getFirst();
        assertThat(request.method()).isEqualTo(HttpMethod.POST);
        assertThat(request.url()).isEqualTo(URI.create(BASE_URL + "/internal/media/urls"));
        assertThat(request.headers().getFirst("X-Internal-Secret")).isEqualTo(SECRET);
        assertThat(request.headers().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(written(request).getBodyAsString().block())
                .isEqualTo("{\"keys\":[\"" + KEY + "\",\"avatars/u2/b.jpg\"],\"ttlSeconds\":3600}");
    }

    @Test
    void urls_shouldAnswerAnEmptyMap_whenWelyMediaSendsNoUrls() {
        answerJson(HttpStatus.OK, "{}");

        StepVerifier.create(adapter.urls(List.of(KEY)))
                .expectNext(Map.of())
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void urls_shouldAnswerAnEmptyMap_whenWelyMediaSendsNoBody() {
        answer = request -> Mono.just(ClientResponse.create(HttpStatus.OK).build());

        StepVerifier.create(adapter.urls(List.of(KEY)))
                .expectNext(Map.of())
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void urls_shouldFailAsMediaError_whenTheStorageIsDown() {
        answerProblem(HttpStatus.BAD_GATEWAY, "MED-TEC-001");

        StepVerifier.create(adapter.urls(List.of(KEY)))
                .expectErrorMatches(MediaApiAdapterTest::isMediaError)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void urls_shouldFailAsMediaError_whenWelyMediaIsUnreachable() {
        answerConnectionRefused();

        StepVerifier.create(adapter.urls(List.of(KEY)))
                .expectErrorMatches(MediaApiAdapterTest::isMediaError)
                .verify(Duration.ofSeconds(10));
    }

    // --- secrets in the logged cause -------------------------------------------

    private static String stackTrace(MediaApiAdapter adapter) {
        Throwable error = adapter.urls(List.of(KEY)).materialize().block(Duration.ofSeconds(20)).getThrowable();
        assertThat(isMediaError(error)).isTrue();
        assertThat(error.getCause()).isInstanceOf(WebClientRequestException.class);
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        return trace.toString();
    }

    @Test
    @DisplayName("an unreachable wely-media does not print the shared secret header in the logged chain")
    void urls_shouldNotLeakTheSharedSecret_whenWelyMediaIsUnreachable() {
        // A real client and a real connection failure: the ERROR line prints this whole chain.
        String secret = "s3cr3t";
        WebClient client = new MediaApiClientConfig().mediaApiWebClient("http://127.0.0.1:1", secret);

        assertThat(stackTrace(new MediaApiAdapter(client, new MediaApiMapperImpl()))).doesNotContain(secret);
    }

    @Test
    @DisplayName("user-info in the base URL does reach the chain: logback-spring.xml masks it")
    void urls_shouldCarryTheBaseUrlUserInfo_whenItIsConfigured() {
        // Evidence for the masking in logback-spring.xml, proven in LogbackSpringXmlTest:
        // Reactor's checkpoint line prints the request URI with its user-info.
        WebClient client = new MediaApiClientConfig().mediaApiWebClient("http://wely:s3cr3t@127.0.0.1:1", "secret");

        assertThat(stackTrace(new MediaApiAdapter(client, new MediaApiMapperImpl())))
                .contains("Request to POST http://wely:s3cr3t@127.0.0.1:1/internal/media/urls");
    }
}
