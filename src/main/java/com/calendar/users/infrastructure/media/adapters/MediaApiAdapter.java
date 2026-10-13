package com.calendar.users.infrastructure.media.adapters;

import com.calendar.users.domain.models.MediaProfile;
import com.calendar.users.domain.models.StoredMedia;
import com.calendar.users.domain.ports.MediaStore;
import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import com.calendar.users.exception.TechnicalErrorCode;
import com.calendar.users.exception.TechnicalException;
import com.calendar.users.infrastructure.media.mappers.MediaApiMapper;
import com.calendar.users.infrastructure.media.models.SignUrlsRequest;
import com.calendar.users.infrastructure.media.models.SignUrlsResponse;
import com.calendar.users.infrastructure.media.models.StoreMediaResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Stores and signs pictures through wely-media, which normalizes, keeps and signs them.
 *
 * <p>Failures are translated here rather than left to {@code InfrastructureErrorAspect}: wely-media
 * says why it refused in a {@code code}, and two of them are the user's fault, not an incident.
 * Everything else, its 401, 403 and 5xx included, is MEDIA_ERROR.
 */
@Slf4j
@Component
public class MediaApiAdapter implements MediaStore {

    static final String MEDIA_PATH = "/internal/media";
    static final String URLS_PATH = "/internal/media/urls";
    /** The web app reuses a URL for a little less than this. */
    static final long URL_TTL_SECONDS = 3600;
    static final String INVALID_IMAGE = "MED-BUS-001";
    static final String TOO_LARGE = "MED-BUS-002";
    private static final String UPLOAD_FILENAME = "upload";
    private static final String CODE_PROPERTY = "code";

    private final WebClient client;
    private final MediaApiMapper mapper;

    public MediaApiAdapter(@Qualifier("mediaApiWebClient") WebClient client, MediaApiMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    @Override
    public Mono<StoredMedia> store(String key, byte[] content, MediaProfile profile) {
        return Mono.defer(() -> client.post()
                        .uri(MEDIA_PATH)
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(BodyInserters.fromMultipartData(parts(key, content, profile)))
                        .retrieve()
                        .bodyToMono(StoreMediaResponse.class))
                .switchIfEmpty(Mono.error(() -> new IllegalStateException("wely-media stored without answering")))
                .map(mapper::toStoredMedia)
                .onErrorMap(error -> translate("store", error));
    }

    /** wely-media answers 204 for a missing key too. */
    @Override
    public Mono<Void> delete(String key) {
        return Mono.defer(() -> client.delete()
                        .uri(builder -> builder.path(MEDIA_PATH).queryParam("key", "{key}").build(key))
                        .retrieve()
                        .toBodilessEntity())
                .then()
                .onErrorMap(error -> translate("delete", error));
    }

    /** One call: the callers ask for at most 100 users, below wely-media's 200 keys. */
    @Override
    public Mono<Map<String, String>> urls(Collection<String> keys) {
        return Mono.defer(() -> client.post()
                        .uri(URLS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(new SignUrlsRequest(List.copyOf(keys), URL_TTL_SECONDS))
                        .retrieve()
                        .bodyToMono(SignUrlsResponse.class))
                .<Map<String, String>>map(response -> response.urls() == null ? Map.of() : response.urls())
                .defaultIfEmpty(Map.of())
                .onErrorMap(error -> translate("urls", error));
    }

    private static MultiValueMap<String, HttpEntity<?>> parts(
            String key, byte[] content, MediaProfile profile) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", content).filename(UPLOAD_FILENAME).contentType(MediaType.APPLICATION_OCTET_STREAM);
        builder.part("key", key);
        builder.part("profile", profile.name());
        return builder.build();
    }

    /** wely-media's two refusals become the user's; the rest is an incident, logged once here. */
    private static Throwable translate(String operation, Throwable error) {
        if (error instanceof BusinessException || error instanceof TechnicalException) {
            return error;
        }
        String code = problemCode(error);
        if (INVALID_IMAGE.equals(code)) {
            return new BusinessException(BusinessErrorCode.PICTURE_INVALID);
        }
        if (TOO_LARGE.equals(code)) {
            return new BusinessException(BusinessErrorCode.PICTURE_TOO_LARGE);
        }
        log.error("wely-media failure in {} ({}): {}", operation, code, error.getMessage(), error);
        return new TechnicalException(TechnicalErrorCode.MEDIA_ERROR);
    }

    /** The ProblemDetail's code, or null for no response or a body that is not one. */
    private static String problemCode(Throwable error) {
        if (!(error instanceof WebClientResponseException response)) {
            return null;
        }
        try {
            Map<?, ?> body = response.getResponseBodyAs(Map.class);
            return body != null && body.get(CODE_PROPERTY) instanceof String code ? code : null;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }
}
