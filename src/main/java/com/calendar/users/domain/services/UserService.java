package com.calendar.users.domain.services;

import com.calendar.users.domain.ports.UserRepositoryPort;

public class UserService {

    private final UserRepositoryPort userRepositoryPort;

    public UserService(UserRepositoryPort userRepositoryPort) {
        this.userRepositoryPort = userRepositoryPort;
    }
}
