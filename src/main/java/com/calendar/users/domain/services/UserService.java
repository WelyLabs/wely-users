package com.calendar.users.domain.services;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.ports.FileStorage;
import com.calendar.users.domain.ports.IdentityProvider;
import com.calendar.users.domain.ports.UserEventPublisher;
import com.calendar.users.domain.ports.UserRepository;
import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class UserService {

    private final UserRepository userRepository;
    private final FileStorage fileStorage;
    private final IdentityProvider identityProvider;
    private final UserEventPublisher userEventPublisher;

    public UserService(UserRepository userRepository, FileStorage fileStorage, IdentityProvider identityProvider, UserEventPublisher userEventPublisher) {
        this.userRepository = userRepository;
        this.fileStorage = fileStorage;
        this.identityProvider = identityProvider;
        this.userEventPublisher = userEventPublisher;
    }

    public Mono<BusinessUser> readProfile(UUID userId) {
        return userRepository.getBusinessUserByUserId(userId)
                .switchIfEmpty(Mono.error(new BusinessException(BusinessErrorCode.USER_NOT_FOUND)));
    }

    public Mono<UUID> resolveInternalUserId(String keycloakId) {
        return userRepository.findIdByKeycloakId(keycloakId)
                .switchIfEmpty(
                        identityProvider.getUser(keycloakId)
                                .flatMap(keycloakUserResponse ->
                                        generateUniqueHashtag(keycloakUserResponse.username())
                                                .flatMap(hashtag -> {
                                                    BusinessUser newUser = new BusinessUser(
                                                            null,
                                                            keycloakUserResponse.username(),
                                                            hashtag,
                                                            keycloakUserResponse.firstName(),
                                                            keycloakUserResponse.lastName(),
                                                            null,
                                                            LocalDateTime.now()
                                                    );

                                                    return userRepository.save(newUser, keycloakId)
                                                            .flatMap(userEventPublisher::publishUserCreatedEvent);
                                                })
                                )
                );
    }

    // todo : transférer cette logique dans un service dédié
    public Mono<String> updateProfilePicture(Long userId, Mono<FilePart> filePartMono) {
        return filePartMono.flatMap(filePart ->
                    fileStorage.storeObject(filePart, userId.toString())
                        .flatMap(profilePicUrl ->
                                userRepository.updateProfilePicUrl(profilePicUrl, userId)
                                        .flatMap(update ->
                                            update > 0 ? Mono.just(profilePicUrl) :  Mono.error(new Exception())
                                        )
                        ));
    }

    private Mono<Integer> generateUniqueHashtag(String username) {
        // 1. On génère un candidat au hasard (ex: entre 1000 et 9999)
        int candidate = ThreadLocalRandom.current().nextInt(1000, 10000);

        // 2. On vérifie en base s'il est déjà pris pour ce pseudo
        return userRepository.existsByUserNameAndHashtag(username, candidate)
                .flatMap(exists -> {
                    if (exists) {
                        // S'il existe, on relance récursivement la génération
                        return generateUniqueHashtag(username);
                    } else {
                        // S'il est libre, on le renvoie
                        return Mono.just(candidate);
                    }
                });
    }
}
