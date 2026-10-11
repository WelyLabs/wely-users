package com.calendar.users.configuration;

import com.calendar.users.domain.ports.UserRepository;
import com.calendar.users.domain.services.UserService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UsersApplicationConfig {

    @Bean
    public UserService userService(UserRepository userRepository) {
        return new UserService(userRepository);
    }
}
