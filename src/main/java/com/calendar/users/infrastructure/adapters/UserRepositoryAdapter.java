package com.calendar.users.infrastructure.adapters;

import com.calendar.users.domain.ports.UserRepositoryPort;
import com.calendar.users.infrastructure.mappers.UserEntityMapper;
import com.calendar.users.infrastructure.repositories.UserRepository;
import org.springframework.stereotype.Component;

@Component
public class UserRepositoryAdapter implements UserRepositoryPort {

    private UserRepository userRepository;
    private UserEntityMapper userEntityMapper;

    public UserRepositoryAdapter(UserRepository userRepository, UserEntityMapper userEntityMapper) {
        this.userRepository = userRepository;
        this.userEntityMapper = userEntityMapper;
    }
}
