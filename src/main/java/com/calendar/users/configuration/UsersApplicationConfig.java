package com.calendar.users.configuration;

import com.calendar.users.domain.ports.FileStorage;
import com.calendar.users.domain.ports.IdentityProvider;
import com.calendar.users.domain.ports.UserEventPublisher;
import com.calendar.users.domain.ports.UserRepository;
import com.calendar.users.domain.services.UserService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UsersApplicationConfig {

    @Bean
    public UserService userService(
            UserRepository userRepository,
            FileStorage fileStorage,
            IdentityProvider identityProvider,
            UserEventPublisher userEventPublisher) {
        return new UserService(userRepository, fileStorage, identityProvider, userEventPublisher);
    }
}
