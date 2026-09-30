package com.calendar.users;

import com.calendar.users.domain.ports.IdentityProvider;
import com.calendar.users.domain.ports.UserEventPublisher;
import com.calendar.users.domain.ports.UserRepository;
import com.calendar.users.domain.services.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Spring context actually starts.
 *
 * <p>The previous version was commented out, so nothing checked that the beans wire
 * together — the three ports, the Keycloak admin client and the Kafka binder included.
 */
@SpringBootTest
@ActiveProfiles("test")
class UsersApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
    }

    @Test
    void contextShouldExposeTheDomainServiceAndItsThreePorts() {
        assertThat(context.getBean(UserService.class)).isNotNull();
        assertThat(context.getBean(UserRepository.class)).isNotNull();
        assertThat(context.getBean(IdentityProvider.class)).isNotNull();
        assertThat(context.getBean(UserEventPublisher.class)).isNotNull();
    }

    @Test
    void seederShouldNotBeActiveOutsideTheLocalProfile() {
        // The seeder tried to insert a million rows whenever the test profile was on.
        assertThat(context.getBeansOfType(
                com.calendar.users.infrastructure.config.DatabaseSeeder.class)).isEmpty();
    }
}
