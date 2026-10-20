package com.calendar.users.infrastructure.observability;

import com.calendar.users.domain.ports.UsersActivity;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * {@link UsersActivity} as Prometheus counters. The names and tags are a contract with the
 * Grafana dashboards: {@code wely_users_signups_total} and {@code wely_users_deletions_total{stage}}.
 */
@Component
public class MicrometerUsersActivity implements UsersActivity {

    static final String SIGNUPS = "wely.users.signups";
    static final String DELETIONS = "wely.users.deletions";
    static final String STAGE_TAG = "stage";
    static final String STAGE_REQUESTED = "requested";
    static final String STAGE_COMPLETED = "completed";

    private final Counter signups;
    private final Counter deletionsRequested;
    private final Counter deletionsCompleted;

    public MicrometerUsersActivity(MeterRegistry meterRegistry) {
        // Registered up front, so the series exist at zero before the first fact.
        this.signups = Counter.builder(SIGNUPS)
                .description("Users created at registration")
                .register(meterRegistry);
        this.deletionsRequested = Counter.builder(DELETIONS)
                .description("Account deletions, by stage")
                .tag(STAGE_TAG, STAGE_REQUESTED)
                .register(meterRegistry);
        this.deletionsCompleted = Counter.builder(DELETIONS)
                .description("Account deletions, by stage")
                .tag(STAGE_TAG, STAGE_COMPLETED)
                .register(meterRegistry);
    }

    @Override
    public void userSignedUp() {
        signups.increment();
    }

    @Override
    public void deletionRequested() {
        deletionsRequested.increment();
    }

    @Override
    public void deletionCompleted() {
        deletionsCompleted.increment();
    }
}
