package com.calendar.users.infrastructure.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The names and tags are a contract with the Grafana dashboards: these pin them down. */
class MicrometerUsersActivityTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final MicrometerUsersActivity activity = new MicrometerUsersActivity(meterRegistry);

    private double count(String name, String... tags) {
        return meterRegistry.get(name).tags(tags).counter().count();
    }

    @Test
    void constructor_shouldRegisterEverySeriesAtZero_soDashboardsShowThemBeforeTheFirstFact() {
        assertThat(count("wely.users.signups")).isZero();
        assertThat(count("wely.users.deletions", "stage", "requested")).isZero();
        assertThat(count("wely.users.deletions", "stage", "completed")).isZero();
    }

    @Test
    void userSignedUp_shouldIncrementTheSignupCounter() {
        activity.userSignedUp();
        activity.userSignedUp();

        assertThat(count("wely.users.signups")).isEqualTo(2.0);
    }

    @Test
    void deletionRequested_shouldIncrementTheRequestedStageOnly() {
        activity.deletionRequested();

        assertThat(count("wely.users.deletions", "stage", "requested")).isEqualTo(1.0);
        assertThat(count("wely.users.deletions", "stage", "completed")).isZero();
    }

    @Test
    void deletionCompleted_shouldIncrementTheCompletedStageOnly() {
        activity.deletionCompleted();

        assertThat(count("wely.users.deletions", "stage", "completed")).isEqualTo(1.0);
        assertThat(count("wely.users.deletions", "stage", "requested")).isZero();
    }

    @Test
    void constructor_shouldExposeTheContractedPrometheusSeries() {
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new MicrometerUsersActivity(prometheus).userSignedUp();

        assertThat(prometheus.scrape())
                .contains("wely_users_signups_total 1")
                .contains("wely_users_deletions_total{stage=\"requested\"} 0")
                .contains("wely_users_deletions_total{stage=\"completed\"} 0");
    }
}
