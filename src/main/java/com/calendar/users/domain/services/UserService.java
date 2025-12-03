package com.calendar.users.domain.services;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.ports.AwsPort;
import com.calendar.users.domain.ports.UserRepositoryPort;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

public class UserService {

    private final UserRepositoryPort userRepositoryPort;
    private final AwsPort awsPort;

    public UserService(UserRepositoryPort userRepositoryPort, AwsPort awsPort) {
        this.userRepositoryPort = userRepositoryPort;
        this.awsPort = awsPort;
    }

    public Mono<BusinessUser> readProfile(Jwt jwt) {
        return userRepositoryPort.getBusinessUserByKeycloakId(jwt.getSubject())
                .switchIfEmpty(
                        Mono.defer(() -> {
                            System.out.println("ça passe ?");
                            return userRepositoryPort.save(new BusinessUser(
                                    null,
                                    null,
                                    LocalDateTime.now()
                            ), jwt.getSubject());
                        })
                );
    }

    public Mono<String> updateProfilePicture(Jwt jwt, Mono<FilePart> filePartMono) {
        String keycloakId = jwt.getSubject();
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
