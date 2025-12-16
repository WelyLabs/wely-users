package com.calendar.users.domain.services;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.ports.AwsPort;
import com.calendar.users.domain.ports.UserRepositoryPort;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

public class UserService {

    private final UserRepositoryPort userRepositoryPort;
    private final AwsPort awsPort;

    public UserService(UserRepositoryPort userRepositoryPort, AwsPort awsPort) {
        this.userRepositoryPort = userRepositoryPort;
        this.awsPort = awsPort;
    }

    public Mono<BusinessUser> readProfile(String userId) {
        return userRepositoryPort.getBusinessUserByUserId(userId)
                .onErrorResume(e -> Mono.empty());
    }

    public Mono<BusinessUser> createUserOnSignUp(String keycloakId) {
        return userRepositoryPort.save(new BusinessUser(null, null, LocalDateTime.now()), keycloakId);
    }

    public Mono<String> updateProfilePicture(String keycloakId, Mono<FilePart> filePartMono) {
        return filePartMono.flatMap(filePart ->
                    awsPort.storeObject(filePart, keycloakId)
                        .flatMap(profilePicUrl ->
                                userRepositoryPort.updateProfilePicUrl(profilePicUrl, keycloakId)
                                        .flatMap(update ->
                                            update > 0 ? Mono.just(profilePicUrl) :  Mono.error(new Exception())
                                        )
                        ));
    }
}
