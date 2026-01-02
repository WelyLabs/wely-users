package com.calendar.users.domain.services;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.ports.AwsPort;
import com.calendar.users.domain.ports.KeycloakPort;
import com.calendar.users.domain.ports.UserEventPublisher;
import com.calendar.users.domain.ports.UserRepositoryPort;
import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

public class UserService {

    private final UserRepositoryPort userRepositoryPort;
    private final AwsPort awsPort;
    private final KeycloakPort keycloakPort;
    private final UserEventPublisher userEventPublisher;

    public UserService(UserRepositoryPort userRepositoryPort, AwsPort awsPort, KeycloakPort keycloakPort, UserEventPublisher userEventPublisher) {
        this.userRepositoryPort = userRepositoryPort;
        this.awsPort = awsPort;
        this.keycloakPort = keycloakPort;
        this.userEventPublisher = userEventPublisher;
    }

    public Mono<BusinessUser> readProfile(Long userId) {
        return userRepositoryPort.getBusinessUserByUserId(userId)
                .switchIfEmpty(Mono.error(new BusinessException(BusinessErrorCode.USER_NOT_FOUND)));
    }

    public Mono<Long> resolveInternalUserId(String keycloakId) {
        return userRepositoryPort.findIdByKeycloakId(keycloakId)
                .switchIfEmpty(
                        keycloakPort.getUser(keycloakId)
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

                                                    return userRepositoryPort.save(newUser, keycloakId)
                                                            .flatMap(userEventPublisher::publishUserCreatedEvent);
                                                })
                                )
                );
    }

    // todo : transférer cette logique dans un service dédié
    public Mono<String> updateProfilePicture(Long userId, Mono<FilePart> filePartMono) {
        return filePartMono.flatMap(filePart ->
                    awsPort.storeObject(filePart, userId.toString())
                        .flatMap(profilePicUrl ->
                                userRepositoryPort.updateProfilePicUrl(profilePicUrl, userId)
                                        .flatMap(update ->
                                            update > 0 ? Mono.just(profilePicUrl) :  Mono.error(new Exception())
                                        )
                        ));
    }

    private Mono<Integer> generateUniqueHashtag(String username) {
        // 1. On génère un candidat au hasard (ex: entre 1000 et 9999)
        int candidate = ThreadLocalRandom.current().nextInt(1000, 10000);

        // 2. On vérifie en base s'il est déjà pris pour ce pseudo
        return userRepositoryPort.existsByUserNameAndHashtag(username, candidate)
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
