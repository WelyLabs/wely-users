package com.calendar.users.configuration;

import com.calendar.users.domain.ports.IdentityProvider;
import com.calendar.users.domain.ports.PictureNormalizer;
import com.calendar.users.domain.ports.PictureStorage;
import com.calendar.users.domain.ports.UserRepository;
import com.calendar.users.domain.services.UserService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UsersApplicationConfig {

    @Bean
    public UserService userService(UserRepository userRepository, IdentityProvider identityProvider,
                                   PictureStorage pictureStorage, PictureNormalizer pictureNormalizer) {
        return new UserService(userRepository, identityProvider, pictureStorage, pictureNormalizer);
    }
}
