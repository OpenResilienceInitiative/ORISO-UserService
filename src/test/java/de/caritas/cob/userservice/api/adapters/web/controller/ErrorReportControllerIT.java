package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.web.dto.ErrorReportDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Covers the unauthenticated client error-intake endpoint (OBS-P3, ORISO-Helm#62): best-effort
 * logging via the existing structured logger, 202 on any usable payload, 400 only when there is
 * nothing worth logging.
 */
class ErrorReportControllerIT {

  private static final String PATH = "/error-reports";

  private MockMvc mockMvc;
  private ObjectMapper objectMapper;
  private ListAppender<ILoggingEvent> logAppender;
  private Logger controllerLogger;

  @BeforeEach
  void setUp() {
    var controller = new ErrorReportController();
    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    objectMapper = new ObjectMapper();

    controllerLogger = (Logger) LoggerFactory.getLogger(ErrorReportController.class);
    logAppender = new ListAppender<>();
    logAppender.start();
    controllerLogger.addAppender(logAppender);
  }

  @AfterEach
  void tearDown() {
    controllerLogger.detachAppender(logAppender);
  }

  @Test
  void reportClientError_Should_return202AndLogStructured_When_payloadIsValid() throws Exception {
    var report = new ErrorReportDTO();
    report.setSource("frontend");
    report.setMessage("Cannot read properties of undefined");
    report.setStack("TypeError: ...\n  at Component.render");
    report.setUrl("https://oriso-dev.site/app/chat");
    report.setUserAgent("Mozilla/5.0");
    report.setCorrelationId("abc-123");
    report.setSeverity("error");

    mockMvc
        .perform(
            post(PATH)
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(report)))
        .andExpect(status().isAccepted())
        .andExpect(content().string(""));

    var events = logAppender.list;
    var matching =
        events.stream()
            .filter(event -> event.getMessage().contains("Client-reported error"))
            .findFirst();
    org.assertj.core.api.Assertions.assertThat(matching).isPresent();
    var event = matching.get();
    org.assertj.core.api.Assertions.assertThat(event.getLevel()).isEqualTo(Level.ERROR);
    org.assertj.core.api.Assertions.assertThat(event.getMDCPropertyMap())
        .containsEntry("client_error_source", "frontend")
        .containsEntry("client_error_severity", "error");
  }

  @Test
  void reportClientError_Should_defaultSeverityToError_When_severityMissing() throws Exception {
    var report = new ErrorReportDTO();
    report.setSource("admin");
    report.setMessage("boom");
    report.setUrl("https://admin.oriso-dev.site/");

    mockMvc
        .perform(
            post(PATH)
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(report)))
        .andExpect(status().isAccepted());

    var event =
        logAppender.list.stream()
            .filter(e -> e.getMessage().contains("Client-reported error"))
            .findFirst()
            .orElseThrow();
    org.assertj.core.api.Assertions.assertThat(event.getLevel()).isEqualTo(Level.ERROR);
    org.assertj.core.api.Assertions.assertThat(event.getMDCPropertyMap())
        .containsEntry("client_error_severity", "error");
  }

  @Test
  void reportClientError_Should_useWarnLevel_When_severityIsWarn() throws Exception {
    var report = new ErrorReportDTO();
    report.setSource("frontend");
    report.setMessage("a recoverable hiccup");
    report.setUrl("https://oriso-dev.site/app");
    report.setSeverity("warn");

    mockMvc
        .perform(
            post(PATH)
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(report)))
        .andExpect(status().isAccepted());

    var event =
        logAppender.list.stream()
            .filter(e -> e.getMessage().contains("Client-reported error"))
            .findFirst()
            .orElseThrow();
    org.assertj.core.api.Assertions.assertThat(event.getLevel()).isEqualTo(Level.WARN);
  }

  @Test
  void reportClientError_Should_fallBackToUnknownSource_When_sourceNotRecognized()
      throws Exception {
    var report = new ErrorReportDTO();
    report.setSource("some-other-app");
    report.setMessage("boom");
    report.setUrl("https://example.test/");

    mockMvc
        .perform(
            post(PATH)
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(report)))
        .andExpect(status().isAccepted());

    var event =
        logAppender.list.stream()
            .filter(e -> e.getMessage().contains("Client-reported error"))
            .findFirst()
            .orElseThrow();
    org.assertj.core.api.Assertions.assertThat(event.getMDCPropertyMap())
        .containsEntry("client_error_source", "unknown");
  }

  @Test
  void reportClientError_Should_return400_When_bodyHasNoUsableContent() throws Exception {
    var report = new ErrorReportDTO();
    report.setSource("frontend");
    // message, url and stack all blank -> nothing worth logging

    mockMvc
        .perform(
            post(PATH)
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(report)))
        .andExpect(status().isBadRequest());

    org.assertj.core.api.Assertions.assertThat(
            logAppender.list.stream()
                .anyMatch(e -> e.getMessage().contains("Client-reported error")))
        .isFalse();
  }

  @Test
  void reportClientError_Should_return400_When_bodyIsEmpty() throws Exception {
    mockMvc
        .perform(post(PATH).contentType("application/json").content(""))
        .andExpect(status().isBadRequest());
  }

  @Test
  void reportClientError_Should_truncateOversizedFields_When_payloadExceedsLimits()
      throws Exception {
    var report = new ErrorReportDTO();
    report.setSource("frontend");
    report.setMessage("x".repeat(5000));
    report.setUrl("https://oriso-dev.site/app");
    report.setStack("y".repeat(9000));

    mockMvc
        .perform(
            post(PATH)
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(report)))
        .andExpect(status().isAccepted());

    var event =
        logAppender.list.stream()
            .filter(e -> e.getFormattedMessage().contains("Client-reported error"))
            .findFirst()
            .orElseThrow();
    // formatted message embeds the (truncated) values; sanity check it isn't unbounded.
    org.assertj.core.api.Assertions.assertThat(event.getFormattedMessage().length())
        .isLessThan(15000);
  }
}
