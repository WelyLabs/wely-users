package com.calendar.users;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import com.calendar.users.configuration.MediaApiClientConfig;
import com.calendar.users.exception.TechnicalErrorCode;
import com.calendar.users.exception.TechnicalException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Covers {@code logback-spring.xml}: a URL password never reaches the console, whether it sits in
 * the message or in a stack trace.
 *
 * <p>The leak is real, not hypothetical: WebClient puts the request URL, userinfo included, in its
 * error-answer messages and checkpoints. The first assertion of each test proves the secret is in
 * the raw text before the layout masks it.
 */
class LogbackSpringXmlTest {

    private static final String SECRET = "s3cr3t";

    private OutputStreamAppender<ILoggingEvent> console;
    private Logger logger;
    private HttpServer mediaRefusing;

    @BeforeEach
    void configure() throws Exception {
        LoggerContext context = new LoggerContext();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(getClass().getResource("/logback-spring.xml"));
        logger = context.getLogger(Logger.ROOT_LOGGER_NAME);
        @SuppressWarnings("unchecked")
        OutputStreamAppender<ILoggingEvent> appender = (OutputStreamAppender<ILoggingEvent>) logger.getAppender("CONSOLE");
        console = appender;

        // Answers every request 401, as wely-media does for a wrong secret.
        mediaRefusing = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mediaRefusing.createContext("/", exchange -> {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        mediaRefusing.start();
    }

    @AfterEach
    void stop() {
        mediaRefusing.stop(0);
    }

    private String format(String message, Throwable error) {
        LoggingEvent event = new LoggingEvent(Logger.class.getName(), logger, Level.ERROR, message, error, null);
        return new String(console.getEncoder().encode(event), StandardCharsets.UTF_8);
    }

    private static String trace(Throwable error) {
        StringWriter out = new StringWriter();
        error.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    /** What the ERROR log of GlobalErrorHandler receives when wely-media's URL carries a password. */
    private TechnicalException mediaErrorAnswerThroughAUrlWithAPassword() {
        Throwable failure = catchThrowable(() -> new MediaApiClientConfig()
                .mediaApiWebClient("http://wely:" + SECRET + "@127.0.0.1:" + mediaRefusing.getAddress().getPort(),
                        "header-secret")
                .post().uri("/internal/media/urls")
                .retrieve().toBodilessEntity()
                .block(Duration.ofSeconds(10)));
        return new TechnicalException(TechnicalErrorCode.MEDIA_ERROR, failure);
    }

    @Test
    void consolePattern_shouldMaskAUrlPassword_inTheStackTrace() {
        TechnicalException failure = mediaErrorAnswerThroughAUrlWithAPassword();
        assertThat(trace(failure)).contains(SECRET);

        String line = format("Technical failure USR-TEC-004 on /x (request 1)", failure);

        assertThat(line)
                .doesNotContain(SECRET)
                .contains("wely:****@127.0.0.1")
                .contains("WebClientResponseException");
    }

    @Test
    void consolePattern_shouldPrintTheStackTraceOnce() {
        String line = format("Technical failure", mediaErrorAnswerThroughAUrlWithAPassword());

        // Without %nopex, logback appends its own, unmasked copy of the trace.
        assertThat(line.split("Caused by: org.springframework.web.reactive.function.client.WebClientResponseException", -1))
                .hasSize(2);
    }

    @Test
    void consolePattern_shouldMaskAUrlPassword_inTheMessage() {
        String line = format("wely-media failure in store (null): 401 Unauthorized from POST http://wely:" + SECRET
                + "@wely-media:8085/internal/media", null);

        assertThat(line).doesNotContain(SECRET).contains("http://wely:****@wely-media:8085/internal/media");
    }

    @Test
    void consolePattern_shouldLeaveAUrlWithoutPasswordAlone() {
        String line = format("Request to GET http://127.0.0.1:8085/internal/media/urls", null);

        assertThat(line).contains("http://127.0.0.1:8085/internal/media/urls");
    }
}
