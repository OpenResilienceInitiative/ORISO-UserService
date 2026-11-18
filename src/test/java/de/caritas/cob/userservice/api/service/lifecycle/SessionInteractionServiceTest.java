package de.caritas.cob.userservice.api.service.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.lifecycle.LifecycleEventPublisher.SessionInteraction;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class SessionInteractionServiceTest {

  private static final Instant START = Instant.parse("2026-11-17T09:00:00Z");
  private static final String COUNSELLOR = "consultant-1";

  @Mock private LifecycleEventPublisher publisher;
  @Mock private SessionRepository sessionRepository;

  private SessionInteractionService service;

  @BeforeEach
  void setUp() {
    service = new SessionInteractionService(publisher, sessionRepository);
    at(START);
    var session = new Session();
    session.setId(16L);
    session.setTenantId(3L);
    session.setAgencyId(4L);
    session.setPostcode("12345");
    lenient().when(sessionRepository.findById(16L)).thenReturn(Optional.of(session));
  }

  @AfterEach
  void tearDown() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void aCounsellorMessageIsAnnouncedWithTheSessionIds() {
    service.messageWritten(16L, COUNSELLOR, START);

    assertThat(announced())
        .containsExactly(new SessionInteraction(3L, 16L, 4L, COUNSELLOR, "message", START));
  }

  @Test
  void messagesOfOneCounsellorInOneSessionAreCoalescedForTenMinutes() {
    service.messageWritten(16L, COUNSELLOR, START);
    at(START.plus(Duration.ofMinutes(9)));
    service.messageWritten(16L, COUNSELLOR, START.plus(Duration.ofMinutes(9)));
    verify(publisher, times(1)).sessionInteraction(any());

    at(START.plus(Duration.ofMinutes(10)));
    service.messageWritten(16L, COUNSELLOR, START.plus(Duration.ofMinutes(10)));

    verify(publisher, times(2)).sessionInteraction(any());
  }

  @Test
  void anotherCounsellorInTheSameSessionIsNotCoalesced() {
    service.messageWritten(16L, COUNSELLOR, START);
    service.messageWritten(16L, "consultant-2", START);

    assertThat(announced())
        .extracting(SessionInteraction::consultantId)
        .containsExactly(COUNSELLOR, "consultant-2");
  }

  @Test
  void replayedHistoryOlderThanOneIntervalIsIgnored() {
    service.messageWritten(16L, COUNSELLOR, START.minus(Duration.ofMinutes(11)));

    verifyNoInteractions(publisher);
  }

  @Test
  void anEndedVideoCallIsAnnouncedOncePerCounsellorEvenWhenBothTrackersSeeIt() {
    var endedAt = START.minusSeconds(5);

    service.callEnded(16L, COUNSELLOR, "call-1", true, endedAt);
    service.callEnded(16L, COUNSELLOR, "call-1", true, endedAt);

    assertThat(announced())
        .containsExactly(new SessionInteraction(3L, 16L, 4L, COUNSELLOR, "video_call", endedAt));
  }

  @Test
  void callsAreNotCoalescedWithMessagesOrOtherCalls() {
    service.messageWritten(16L, COUNSELLOR, START);
    service.callEnded(16L, COUNSELLOR, "call-1", false, START);
    service.callEnded(16L, COUNSELLOR, "call-2", false, START);

    assertThat(announced())
        .extracting(SessionInteraction::interactionType)
        .containsExactly("message", "call", "call");
  }

  @ParameterizedTest
  @EnumSource(
      value = ConversationType.class,
      names = {"INTERNAL_GROUP", "SELF_HELP"})
  void groupChatsAnnounceNothing(ConversationType groupType) {
    // Group chats have a shadow session too; session.interaction is for 1:1 sessions only.
    var group = new Session();
    group.setId(18L);
    group.setTenantId(3L);
    group.setAgencyId(4L);
    group.setConversationType(groupType);
    org.mockito.Mockito.when(sessionRepository.findById(18L)).thenReturn(Optional.of(group));

    service.messageWritten(18L, COUNSELLOR, START);
    service.callEnded(18L, COUNSELLOR, "call-9", true, START);

    verify(publisher, never()).sessionInteraction(any());
  }

  @ParameterizedTest
  @EnumSource(
      value = ConversationType.class,
      names = {"AGENCY_COUNSELLING", "LIVE_CHAT"})
  void oneToOneConversationTypesAreAnnounced(ConversationType oneToOne) {
    var session = new Session();
    session.setId(19L);
    session.setTenantId(3L);
    session.setAgencyId(4L);
    session.setConversationType(oneToOne);
    org.mockito.Mockito.when(sessionRepository.findById(19L)).thenReturn(Optional.of(session));

    service.messageWritten(19L, COUNSELLOR, START);

    verify(publisher).sessionInteraction(any());
  }

  @Test
  void anUnknownSessionAnnouncesNothing() {
    service.messageWritten(17L, COUNSELLOR, START);

    verify(publisher, never()).sessionInteraction(any());
  }

  @Test
  void aRolledBackCallEndDoesNotSuppressItsReplay() {
    TransactionSynchronizationManager.initSynchronization();
    service.callEnded(16L, COUNSELLOR, "call-1", false, START);
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
    TransactionSynchronizationManager.clearSynchronization();

    service.callEnded(16L, COUNSELLOR, "call-1", false, START);

    verify(publisher, times(2)).sessionInteraction(any());
  }

  @Test
  void aCommittedCallEndKeepsItsClaim() {
    TransactionSynchronizationManager.initSynchronization();
    service.callEnded(16L, COUNSELLOR, "call-1", false, START);
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
    TransactionSynchronizationManager.clearSynchronization();

    service.callEnded(16L, COUNSELLOR, "call-1", false, START);

    verify(publisher, times(1)).sessionInteraction(any());
  }

  @Test
  void aFailureNeverReachesTheCaller() {
    doThrow(new IllegalStateException("database down")).when(sessionRepository).findById(16L);

    assertThatCode(() -> service.messageWritten(16L, COUNSELLOR, START)).doesNotThrowAnyException();
    verifyNoInteractions(publisher);
  }

  private void at(Instant now) {
    ReflectionTestUtils.setField(service, "clock", Clock.fixed(now, ZoneOffset.UTC));
  }

  private java.util.List<SessionInteraction> announced() {
    var captor = ArgumentCaptor.forClass(SessionInteraction.class);
    verify(publisher, org.mockito.Mockito.atLeastOnce()).sessionInteraction(captor.capture());
    return captor.getAllValues();
  }
}
