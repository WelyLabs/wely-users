package com.calendar.users.application.rest;

import com.calendar.users.application.dtos.DeletionEligibilityResponse;
import com.calendar.users.application.dtos.PictureResponse;
import com.calendar.users.application.dtos.ProvisionUserRequest;
import com.calendar.users.application.dtos.UpdateProfileRequest;
import com.calendar.users.application.mappers.ProvisionUserRequestMapper;
import com.calendar.users.application.mappers.UpdateProfileRequestMapper;
import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.services.AccountDeletionService;
import com.calendar.users.domain.services.UserService;
import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("profile")
@Validated
public class ProfileController {

    private static final String BUSINESS_ID = "businessId";
    private static final String AUTH_TIME = "auth_time";

    private final UserService userService;
    private final AccountDeletionService accountDeletionService;
    private final ProvisionUserRequestMapper provisionUserRequestMapper;
    private final UpdateProfileRequestMapper updateProfileRequestMapper;

    public ProfileController(UserService userService, AccountDeletionService accountDeletionService,
                             ProvisionUserRequestMapper provisionUserRequestMapper,
                             UpdateProfileRequestMapper updateProfileRequestMapper) {
        this.userService = userService;
        this.accountDeletionService = accountDeletionService;
        this.provisionUserRequestMapper = provisionUserRequestMapper;
        this.updateProfileRequestMapper = updateProfileRequestMapper;
    }

    /** Called by Keycloak during registration. Idempotent: a known Keycloak id returns its user. */
    @PostMapping("provision")
    public Mono<String> provisionUser(@Valid @RequestBody ProvisionUserRequest request) {
        return userService.provisionUser(request.keycloakId(), provisionUserRequestMapper.toIdentityUser(request))
                .map(String::valueOf);
    }

    @GetMapping("resolve/{keycloakId}")
    public Mono<String> findBusinessIdByKeycloakId(
            @NotNull @PathVariable String keycloakId) {
        return userService.findBusinessIdByKeycloakId(keycloakId).map(String::valueOf);
    }

    @GetMapping
    public Mono<ResponseEntity<BusinessUser>> readProfile(
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getClaimAsString("businessId"));

        return userService.readProfile(userId).map(ResponseEntity::ok);
    }

    /** Pictures above this are refused before they are decoded. */
    static final int MAX_PICTURE_BYTES = 5 * 1024 * 1024;

    /**
     * Replaces the caller's picture. The upload is read whole, up to the limit, then sent to
     * wely-media: 413 USR-BUS-007 above 5 MB, 400 USR-BUS-006 for a file that is not an accepted image.
     */
    @PostMapping(value = "picture", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<PictureResponse> updatePicture(
            @AuthenticationPrincipal Jwt jwt,
            @RequestPart("file") Mono<FilePart> file) {
        UUID userId = UUID.fromString(jwt.getClaimAsString("businessId"));

        return file
                .flatMap(part -> DataBufferUtils.join(part.content(), MAX_PICTURE_BYTES))
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    return bytes;
                })
                .onErrorMap(DataBufferLimitException.class,
                        e -> new BusinessException(BusinessErrorCode.PICTURE_TOO_LARGE))
                .switchIfEmpty(Mono.error(new BusinessException(BusinessErrorCode.PICTURE_INVALID)))
                .flatMap(bytes -> userService.updatePicture(userId, bytes))
                .map(PictureResponse::new)
                // Saved but not signed: the web app reads the URL from /avatars anyway.
                .defaultIfEmpty(new PictureResponse(null));
    }

    /** The caller's own profile only: whose it is comes from the token. 409 USR-BUS-004 if the username is taken. */
    @PutMapping
    public Mono<ResponseEntity<BusinessUser>> updateProfile(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateProfileRequest request) {
        UUID userId = UUID.fromString(jwt.getClaimAsString("businessId"));

        return userService.updateProfile(userId, updateProfileRequestMapper.toProfileUpdate(request))
                .map(ResponseEntity::ok);
    }

    /** Deletes the caller's account; identity and sign-in time come from the token only. */
    @DeleteMapping
    public Mono<ResponseEntity<Void>> deleteAccount(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getClaimAsString(BUSINESS_ID));

        return accountDeletionService.requestDeletion(userId, authenticatedAt(jwt))
                .then(Mono.fromSupplier(() -> ResponseEntity.accepted().<Void>build()));
    }

    /** Whether the caller may delete their account, so the web app never sends a demo visitor to sign in again. */
    @GetMapping("deletion")
    public Mono<DeletionEligibilityResponse> deletionEligibility(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getClaimAsString(BUSINESS_ID));

        return accountDeletionService.isDeletionAllowed(userId).map(DeletionEligibilityResponse::new);
    }

    /** Null when the claim is absent or unreadable, which the domain refuses: deletion fails closed. */
    private static Instant authenticatedAt(Jwt jwt) {
        try {
            return jwt.getClaimAsInstant(AUTH_TIME);
        } catch (IllegalArgumentException unreadable) {
            return null;
        }
    }
}
