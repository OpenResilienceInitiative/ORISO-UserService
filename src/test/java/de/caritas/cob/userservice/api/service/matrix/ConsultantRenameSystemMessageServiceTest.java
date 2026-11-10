package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** OBP 2.1 #119 / ORISO-UserService#1533: in-chat system message on a display-name change. */
@ExtendWith(MockitoExtension.class)
class ConsultantRenameSystemMessageServiceTest {

  private static final String PREFIX = "[SYSTEM_NOTIFICATION]";

  @InjectMocks private ConsultantRenameSystemMessageService service;

  @Mock private SessionRepository sessionRepository;
  @Mock private MatrixSessionSystemMessageService matrixSessionSystemMessageService;

  private Logger logger;
  private ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  void setUp() {
    logger = (Logger) LoggerFactory.getLogger(ConsultantRenameSystemMessageService.class);
    logAppender = new ListAppender<>();
    logAppender.start();
    logger.addAppender(logAppender);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(logAppender);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void postRenameMessages_Should_postOneRenameNoticePerActiveSession_asTheCounsellor()
      throws Exception {
    var consultant = new Consultant();
    var first = sessionWithId(1L);
    var second = sessionWithId(2L);
    when(sessionRepository.findByConsultantAndStatusIn(
            consultant, List.of(SessionStatus.IN_PROGRESS)))
        .thenReturn(List.of(first, second));
    when(matrixSessionSystemMessageService.postSystemMessage(any(), anyString(), eq(true)))
        .thenReturn(true);

    service.postRenameMessages(consultant, "Frau Alt.", "Frau Neu.");

    var body = ArgumentCaptor.forClass(String.class);
    verify(matrixSessionSystemMessageService)
        .postSystemMessage(eq(first), body.capture(), eq(true));
    verify(matrixSessionSystemMessageService).postSystemMessage(eq(second), anyString(), eq(true));
    assertThat(body.getValue()).startsWith(PREFIX);
    assertThat(payloadOf(body.getValue()))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("type", "CONSULTANT_RENAMED", "oldName", "Frau Alt.", "newName", "Frau Neu."));
  }

  @Test
  void postRenameMessages_Should_postNothing_When_theNamesAreEqualAfterTrimming() {
    var consultant = new Consultant();

    service.postRenameMessages(consultant, "  Frau Alt. ", "Frau Alt.");

    verifyNoInteractions(sessionRepository, matrixSessionSystemMessageService);
  }

  @Test
  void postRenameMessages_Should_postNothing_When_aNameIsMissing() {
    var consultant = new Consultant();

    service.postRenameMessages(consultant, null, "Frau Neu.");
    service.postRenameMessages(consultant, "Frau Alt.", "   ");
    service.postRenameMessages(null, "Frau Alt.", "Frau Neu.");

    verifyNoInteractions(sessionRepository, matrixSessionSystemMessageService);
  }

  @Test
  void postRenameMessages_Should_treatACaseOnlyChangeAsARealChange() throws Exception {
    var consultant = new Consultant();
    var session = sessionWithId(3L);
    when(sessionRepository.findByConsultantAndStatusIn(any(), any())).thenReturn(List.of(session));

    service.postRenameMessages(consultant, "frau alt.", "Frau Alt.");

    var body = ArgumentCaptor.forClass(String.class);
    verify(matrixSessionSystemMessageService)
        .postSystemMessage(eq(session), body.capture(), eq(true));
    assertThat(payloadOf(body.getValue())).containsEntry("newName", "Frau Alt.");
  }

  @Test
  void postRenameMessages_Should_sendTrimmedAndSafelyEscapedNames() throws Exception {
    var consultant = new Consultant();
    var session = sessionWithId(4L);
    when(sessionRepository.findByConsultantAndStatusIn(any(), any())).thenReturn(List.of(session));

    service.postRenameMessages(consultant, "  Ada \"A\" ", " Bea\\B ");

    var body = ArgumentCaptor.forClass(String.class);
    verify(matrixSessionSystemMessageService)
        .postSystemMessage(eq(session), body.capture(), eq(true));
    assertThat(payloadOf(body.getValue()))
        .containsEntry("oldName", "Ada \"A\"")
        .containsEntry("newName", "Bea\\B");
  }

  @Test
  void postRenameMessages_Should_continueWithTheNextSession_When_onePostFails() {
    var consultant = new Consultant();
    var failing = sessionWithId(5L);
    var next = sessionWithId(6L);
    when(sessionRepository.findByConsultantAndStatusIn(any(), any()))
        .thenReturn(List.of(failing, next));
    when(matrixSessionSystemMessageService.postSystemMessage(
            eq(failing), anyString(), anyBoolean()))
        .thenThrow(new IllegalStateException("matrix down"));

    service.postRenameMessages(consultant, "Frau Alt.", "Frau Neu.");

    verify(matrixSessionSystemMessageService).postSystemMessage(eq(next), anyString(), eq(true));
  }

  @Test
  void postRenameMessages_Should_notFail_When_theSessionLookupFails() {
    var consultant = new Consultant();
    when(sessionRepository.findByConsultantAndStatusIn(any(), any()))
        .thenThrow(new IllegalStateException("db down"));

    service.postRenameMessages(consultant, "Frau Alt.", "Frau Neu.");

    verifyNoInteractions(matrixSessionSystemMessageService);
  }

  @Test
  void postRenameMessages_Should_neverLogTheNames() {
    var consultant = new Consultant();
    consultant.setId("counsellor-1");
    var failing = sessionWithId(7L);
    when(sessionRepository.findByConsultantAndStatusIn(any(), any())).thenReturn(List.of(failing));
    when(matrixSessionSystemMessageService.postSystemMessage(any(), anyString(), anyBoolean()))
        .thenThrow(new IllegalStateException("matrix down"));

    service.postRenameMessages(consultant, "Frau Alt.", "Frau Neu.");

    assertThat(logAppender.list).isNotEmpty();
    assertThat(logAppender.list)
        .allSatisfy(
            event ->
                assertThat(event.getFormattedMessage())
                    .doesNotContain("Frau Alt.")
                    .doesNotContain("Frau Neu."));
  }

  @Test
  void postRenameMessagesAfterCommit_Should_waitForTheCommit_When_aTransactionIsActive() {
    var consultant = new Consultant();
    var session = sessionWithId(8L);
    when(sessionRepository.findByConsultantAndStatusIn(any(), any())).thenReturn(List.of(session));
    TransactionSynchronizationManager.initSynchronization();

    service.postRenameMessagesAfterCommit(consultant, "Frau Alt.", "Frau Neu.");

    verifyNoInteractions(matrixSessionSystemMessageService);
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(TransactionSynchronization::afterCommit);
    verify(matrixSessionSystemMessageService).postSystemMessage(eq(session), anyString(), eq(true));
  }

  @Test
  void postRenameMessagesAfterCommit_Should_postImmediately_When_noTransactionIsActive() {
    var consultant = new Consultant();
    var session = sessionWithId(9L);
    when(sessionRepository.findByConsultantAndStatusIn(any(), any())).thenReturn(List.of(session));

    service.postRenameMessagesAfterCommit(consultant, "Frau Alt.", "Frau Neu.");

    verify(matrixSessionSystemMessageService).postSystemMessage(eq(session), anyString(), eq(true));
  }

  private static Session sessionWithId(long id) {
    var session = new Session();
    session.setId(id);
    return session;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> payloadOf(String body) throws Exception {
    return new ObjectMapper().readValue(body.substring(PREFIX.length()), Map.class);
  }
}
