package de.caritas.cob.userservice.api.service;

import static de.caritas.cob.userservice.api.service.LogService.ASSIGN_SESSION_FACADE_ERROR_TEXT;
import static de.caritas.cob.userservice.api.service.LogService.ASSIGN_SESSION_FACADE_WARNING_TEXT;
import static de.caritas.cob.userservice.api.service.LogService.FORBIDDEN_WARNING_TEXT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import de.caritas.cob.userservice.testutils.LogbackCaptor;
import java.io.PrintWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class LogServiceTest {

  private final String ERROR_MESSAGE = "Error message";

  @Mock Exception exception;

  private LogbackCaptor logCaptor;

  @BeforeEach
  public void setup() {
    logCaptor = LogbackCaptor.forClass(LogService.class);
  }

  @AfterEach
  public void tearDown() {
    logCaptor.detach();
  }

  @Test
  public void logDatabaseError_Should_LogExceptionStackTrace() {

    LogService.logDatabaseError(exception);
    verify(exception, atLeastOnce()).printStackTrace(any(PrintWriter.class));
  }

  @Test
  public void logForbidden_Should_LogWarningMessage() {

    LogService.logForbidden(ERROR_MESSAGE);
    assertThat(logCaptor.contains(Level.WARN, FORBIDDEN_WARNING_TEXT + ERROR_MESSAGE)).isTrue();
  }

  @Test
  public void logAssignSessionFacadeWarning_should_LogErrorMessage() {

    LogService.logAssignSessionFacadeWarning(new Exception(ERROR_MESSAGE));
    assertThat(logCaptor.contains(Level.WARN, ASSIGN_SESSION_FACADE_WARNING_TEXT + ERROR_MESSAGE))
        .isTrue();
  }

  @Test
  public void logInfo_Should_LogMessage() {

    LogService.logInfo(ERROR_MESSAGE);
    assertThat(logCaptor.contains(Level.INFO, ERROR_MESSAGE)).isTrue();
  }

  @Test
  public void logInfo_Should_LogExceptionStackTrace() {
    LogService.logInfo(exception);
    verify(exception, atLeastOnce()).printStackTrace(any(PrintWriter.class));
  }

  @Test
  public void logWarn_Should_LogExceptionStackTrace() {

    LogService.logWarn(exception);
    verify(exception, atLeastOnce()).printStackTrace(any(PrintWriter.class));
  }

  @Test
  public void logAssignSessionFacadeError_Should_LogExceptionStackTraceAndErrorMessage() {

    when(exception.getMessage()).thenReturn(ERROR_MESSAGE);
    LogService.logAssignSessionFacadeError(exception);
    verify(exception, atLeastOnce()).printStackTrace(any(PrintWriter.class));
    assertThat(logCaptor.contains(Level.ERROR, ASSIGN_SESSION_FACADE_ERROR_TEXT + ERROR_MESSAGE))
        .isTrue();
  }

  @Test
  public void logInternalServerError_Should_LogExceptionStackTrace() {

    LogService.logInternalServerError(exception);
    verify(exception, atLeastOnce()).printStackTrace(any(PrintWriter.class));
  }

  @Test
  public void logForbidden_Should_LogExceptionStackTrace() {

    LogService.logForbidden(exception);
    verify(exception, atLeastOnce()).printStackTrace(any(PrintWriter.class));
  }

  @Test
  public void logRocketChatError_Should_LogExceptionMessage() {
    LogService.logRocketChatError(exception);
    verify(exception, atLeastOnce()).getMessage();
  }
}
