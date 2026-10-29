package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.security.test.context.support.WithMockUser;

class ConsultantCreationOperationPrivacyIT extends ConsultantCreationOperationWebSecurityIT {
  enum FrameworkLevel {
    DEBUG,
    TRACE
  }

  @ParameterizedTest
  @EnumSource(FrameworkLevel.class)
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_CREATE)
  void actualFrameworkDecodeAndValidationNeverLogCanaries(FrameworkLevel requested)
      throws Exception {
    Logger logger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    java.util.Map<Logger, Level> oldLevels = new java.util.LinkedHashMap<>();
    ((ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory())
        .getLoggerList().stream()
            .filter(
                l ->
                    l.getName().startsWith("org.springframework.web")
                        || l.getName().startsWith("org.springframework.security"))
            .forEach(
                l -> {
                  oldLevels.put(l, l.getLevel());
                  l.setLevel(Level.valueOf(requested.name()));
                });
    var capture = new ListAppender<ILoggingEvent>();
    capture.start();
    logger.addAppender(capture);
    try {
      legacyResult();
      mvc.perform(creation(BODY)).andExpect(status().isOk());
      var legacyFailure =
          new de.caritas.cob.userservice.api.exception.httpresponses
              .CustomValidationHttpStatusException(
              de.caritas.cob.userservice.api.exception.httpresponses.customheader
                  .HttpStatusExceptionReason.NUMBER_OF_LICENSES_EXCEEDED);
      legacyFailure.initCause(new IllegalStateException("PrivacyLegacyReasonCanary"));
      org.mockito.Mockito.doThrow(legacyFailure)
          .when(facade)
          .createNewConsultant(org.mockito.ArgumentMatchers.any());
      mvc.perform(creation(BODY))
          .andExpect(status().isBadRequest())
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                  .string("X-Reason", "NUMBER_OF_LICENSES_EXCEEDED"));
      mvc.perform(creation(BODY.replace("PrivacyPasswordCanary!", "short!")))
          .andExpect(status().isBadRequest());
      mvc.perform(
              creation(
                  "{\"username\":\"PrivacyUserCanary\",\"password\":\"PrivacyPasswordCanary\",BROKEN"))
          .andExpect(status().isBadRequest());
      mvc.perform(creation(BODY.replace("\"tenantId\":7", "\"tenantId\":\"PrivacyTypeCanary\"")))
          .andExpect(status().isBadRequest());
      trackedResult();
      mvc.perform(creation(BODY).header(HEADER, KEY)).andExpect(status().isOk());
      mvc.perform(creation(BODY + " ".repeat(65536)).header(HEADER, KEY))
          .andExpect(status().isBadRequest());
      org.mockito.Mockito.doThrow(
              new IllegalStateException(
                  "PrivacySetupCanary PrivacyPasswordCanary PrivacyEmailCanary"))
          .when(issuer)
          .issueAfterCreation(
              org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any());
      mvc.perform(creation(BODY).header(HEADER, KEY))
          .andExpect(status().isInternalServerError())
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                  .string(
                      org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Privacy"))));
      org.mockito.Mockito.doThrow(
              new IllegalStateException(
                  "PrivacyProviderCanary PrivacyPasswordCanary PrivacyEmailCanary"))
          .when(facade)
          .createNewConsultant(
              org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
      mvc.perform(creation(BODY).header(HEADER, KEY))
          .andExpect(status().isInternalServerError())
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                  .string(
                      org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Privacy"))));
      mvc.perform(
          org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
              "/useradmin/consultant-creation-operations/00000000-0000-0000-0000-000000000001"));
      assertThat(capture.list).isNotEmpty();
      String messages =
          capture.list.stream()
              .map(
                  event ->
                      event.getFormattedMessage()
                          + (event.getThrowableProxy() == null
                              ? ""
                              : ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(
                                  event.getThrowableProxy())))
              .collect(java.util.stream.Collectors.joining("\n"));
      assertThat(messages).contains("getConsultantCreationOperation(UUID)");
      assertThat(messages)
          .doesNotContain(
              "PrivacyUserCanary",
              "PrivacyEmailCanary",
              "PrivacyPasswordCanary",
              "PrivacyTypeCanary",
              "PrivacySetupCanary",
              "PrivacyProviderCanary",
              "PrivacyLegacyReasonCanary",
              "short!");
    } finally {
      logger.detachAppender(capture);
      oldLevels.forEach(Logger::setLevel);
      capture.stop();
    }
  }
}
