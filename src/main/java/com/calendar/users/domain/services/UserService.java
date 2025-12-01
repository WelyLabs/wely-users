package com.calendar.users.domain.services;

import com.calendar.users.domain.models.BusinessUser;
import com.calendar.users.domain.ports.UserRepositoryPort;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

public class UserService {

    private final UserRepositoryPort userRepositoryPort;

    public UserService(UserRepositoryPort userRepositoryPort) {
        this.userRepositoryPort = userRepositoryPort;
    }

    public Mono<BusinessUser> readProfile(Jwt jwt) {
        return userRepositoryPort.getBusinessUserByKeycloakId(jwt.getSubject())
                .switchIfEmpty(
                        Mono.defer(() -> userRepositoryPort.save(new BusinessUser(
                            LocalDateTime.now()
                            ), jwt.getSubject()))
                );
    }
}
