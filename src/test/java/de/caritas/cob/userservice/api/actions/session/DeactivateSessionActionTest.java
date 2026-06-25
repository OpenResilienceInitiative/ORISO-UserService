package de.caritas.cob.userservice.api.actions.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.service.LogService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.testutils.LogbackCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeactivateSessionActionTest {

  @InjectMocks private DeactivateSessionActionCommand deactivateSessionAction;

  @Mock private SessionService sessionService;

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
  void execute_Should_returnEmptyList_When_deactivationOfSessionIsSuccessful() {
    Session mockedSession = mock(Session.class);

    this.deactivateSessionAction.execute(mockedSession);

    verify(mockedSession, times(1)).setStatus(SessionStatus.DONE);
    verify(this.sessionService, times(1)).saveSession(any());
    assertThat(logCaptor.events()).isEmpty();
  }

  @Test
  void execute_Should_returnExpectedWorkflowErrorAndLogError_When_sessionIsAlreadyDone() {
    Session mockedSession = mock(Session.class);
    when(mockedSession.getStatus()).thenReturn(SessionStatus.DONE);

    assertThrows(
        ConflictException.class, () -> this.deactivateSessionAction.execute(mockedSession));
  }
}
