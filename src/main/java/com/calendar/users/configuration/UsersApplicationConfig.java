package com.calendar.users.configuration;

import com.calendar.users.domain.ports.AwsPort;
import com.calendar.users.domain.ports.KeycloakPort;
import com.calendar.users.domain.ports.UserRepositoryPort;
import com.calendar.users.domain.services.UserService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UsersApplicationConfig {

    @Bean
    public UserService userService(
            UserRepositoryPort userRepositoryPort,
            AwsPort awsPort,
            KeycloakPort keycloakPort) {
        return new UserService(userRepositoryPort, awsPort, keycloakPort);
    }
}
