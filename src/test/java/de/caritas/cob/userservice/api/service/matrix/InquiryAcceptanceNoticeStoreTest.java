package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver;
import de.caritas.cob.userservice.api.model.*;
import de.caritas.cob.userservice.api.model.InquiryAcceptanceNotice.DeliveryState;
import de.caritas.cob.userservice.api.port.out.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class InquiryAcceptanceNoticeStoreTest {
  final SessionRepository sessions = mock(SessionRepository.class);
  final InquiryAcceptanceNoticeRepository notices = mock(InquiryAcceptanceNoticeRepository.class);
  final InquiryAcceptanceNoticeStore store =
      new InquiryAcceptanceNoticeStore(sessions, notices, new ConsultantDisplayNameResolver());

  InquiryAcceptanceNotice stored;

  Session accepted() {
    var consultant =
        Consultant.builder()
            .id("c")
            .username("pseudonym")
            .email("synthetic@example.test")
            .firstName("private first")
            .lastName("private last")
            .matrixUserId("@c:matrix.test")
            .build();
    var session =
        Session.builder()
            .id(231L)
            .registrationType(Session.RegistrationType.REGISTERED)
            .postcode("10965")
            .consultant(consultant)
            .matrixRoomId("!room:matrix.test")
            .conversationType(ConversationType.AGENCY_COUNSELLING)
            .status(Session.SessionStatus.IN_PROGRESS)
            .ownershipRevision(1)
            .user(
                User.builder()
                    .userId("asker")
                    .username("asker")
                    .email("asker@example.test")
                    .matrixUserId("@asker:matrix.test")
                    .build())
            .build();
    when(sessions.findByIdForUpdate(231L)).thenReturn(Optional.of(session));
    lenient().when(notices.findById(231L)).thenAnswer(i -> Optional.ofNullable(stored));
    lenient().when(notices.findByIdForUpdate(231L)).thenAnswer(i -> Optional.ofNullable(stored));
    lenient()
        .when(notices.save(any()))
        .thenAnswer(
            i -> {
              stored = i.getArgument(0);
              return stored;
            });
    return session;
  }

  @Test
  void storesPublicSnapshotAndImmutableAcceptanceTimeOnce() {
    var session = accepted();
    var before = LocalDateTime.now(ZoneOffset.UTC);
    var captor = org.mockito.ArgumentCaptor.forClass(InquiryAcceptanceNotice.class);
    store.prepare(session);
    assertThat(store.recordSuccessfulInitialAcceptance(session)).contains(231L);
    verify(notices, times(2)).save(captor.capture());
    var fact = captor.getValue();
    assertThat(fact.getAcceptedAtUtc()).isBetween(before, LocalDateTime.now(ZoneOffset.UTC));
    assertThat(fact.getPublicAdvisorName()).isEqualTo("pseudonym");
    when(notices.findById(231L)).thenReturn(Optional.of(fact));
    store.recordSuccessfulInitialAcceptance(session);
    verify(notices, times(2)).save(any());
    assertThat(fact.getAcceptedAtUtc()).isEqualTo(captor.getValue().getAcceptedAtUtc());
  }

  @Test
  void publicDisplayNameWinsAndPrivateNamesAreNeverConsidered() {
    var session = accepted();
    session.getConsultant().setDisplayName("Public advisor");
    store.prepare(session);
    verify(notices).save(argThat(n -> "Public advisor".equals(n.getPublicAdvisorName())));
  }

  @Test
  void encodedDisplayNameFallsBackToDecodedUsername() {
    var session = accepted();
    session.getConsultant().setDisplayName("enc.private-name");
    session
        .getConsultant()
        .setUsername(
            new de.caritas.cob.userservice.api.helper.UsernameTranscoder()
                .encodeUsername("public nickname"));
    store.prepare(session);
    verify(notices).save(argThat(n -> "public nickname".equals(n.getPublicAdvisorName())));
  }

  @ParameterizedTest
  @EnumSource(
      value = ConversationType.class,
      names = {"LIVE_CHAT", "INTERNAL_GROUP", "SELF_HELP"})
  void unsupportedModalitiesCreateNoFact(ConversationType type) {
    var session = accepted();
    session.setConversationType(type);
    assertThat(store.recordSuccessfulInitialAcceptance(session)).isEmpty();
    verify(notices, never()).save(any());
  }

  @Test
  void staleLosingOwnerCannotPublishAcceptance() {
    var current = accepted();
    var stale = accepted();
    stale.setConsultant(
        Consultant.builder()
            .id("loser")
            .username("loser")
            .firstName("synthetic")
            .lastName("synthetic")
            .email("loser@example.test")
            .build());
    when(sessions.findByIdForUpdate(231L)).thenReturn(Optional.of(current));
    store.prepare(current);
    clearInvocations(notices);
    assertThat(store.recordSuccessfulInitialAcceptance(stale)).isEmpty();
    verify(notices, never()).save(any());
  }

  @Test
  void staleOwnershipRevisionCannotPublishAcceptance() {
    var current = accepted();
    var stale = accepted();
    stale.setOwnershipRevision(0);
    when(sessions.findByIdForUpdate(231L)).thenReturn(Optional.of(current));
    store.prepare(current);
    clearInvocations(notices);
    assertThat(store.recordSuccessfulInitialAcceptance(stale)).isEmpty();
    verify(notices, never()).save(any());
  }

  @Test
  void missingRoomCreatesNoInventedFact() {
    var session = accepted();
    store.prepare(session);
    clearInvocations(notices);
    session.setMatrixRoomId(" ");
    assertThat(store.recordSuccessfulInitialAcceptance(session)).isEmpty();
    verify(notices, never()).save(any());
  }

  @Test
  void failedOrRolledBackAssignmentCreatesNoFact() {
    var session = accepted();
    store.prepare(session);
    clearInvocations(notices);
    session.setStatus(Session.SessionStatus.NEW);
    assertThat(store.recordSuccessfulInitialAcceptance(session)).isEmpty();
    verify(notices, never()).save(any());
  }

  @Test
  void missingSenderDoesNotFallBackToExtraBotOrPrivateIdentity() {
    var session = accepted();
    store.prepare(session);
    clearInvocations(notices);
    session.getConsultant().setMatrixUserId(null);
    assertThat(store.recordSuccessfulInitialAcceptance(session)).isEmpty();
    verify(notices, never()).save(any());
  }

  @Test
  void onlyOnePendingClaimCanCrossTransportBoundary() {
    var fact =
        InquiryAcceptanceNotice.builder()
            .sessionId(231L)
            .deliveryState(DeliveryState.PENDING)
            .build();
    when(notices.findByIdForUpdate(231L)).thenReturn(Optional.of(fact));
    assertThat(store.claimForSend(231L)).isTrue();
    assertThat(store.claimForSend(231L)).isFalse();
    assertThat(fact.getDeliveryState()).isEqualTo(DeliveryState.UNCERTAIN);
  }

  @Test
  void preparationIsNeverClaimableWithoutTerminalProvisioningSuccess() {
    var session = accepted();
    store.prepare(session);
    assertThat(store.claimForSend(231L)).isFalse();
    assertThat(stored.getDeliveryState()).isEqualTo(DeliveryState.PREPARING);
  }

  @Test
  void terminalActivationKeepsOriginalTimestampAndRejectsDuplicates() {
    var session = accepted();
    store.prepare(session);
    var at = stored.getAcceptedAtUtc();
    assertThat(store.recordSuccessfulInitialAcceptance(session)).contains(231L);
    assertThat(stored.getAcceptedAtUtc()).isEqualTo(at);
    assertThat(store.recordSuccessfulInitialAcceptance(session)).isEmpty();
  }

  @Test
  void revisedOwnerCannotActivateItsPredecessorsPreparation() {
    var session = accepted();
    store.prepare(session);
    session.setOwnershipRevision(2);
    assertThat(store.recordSuccessfulInitialAcceptance(session)).isEmpty();
    assertThat(stored.getDeliveryState()).isEqualTo(DeliveryState.PREPARING);
  }

  @Test
  void compensationDeletesOnlyItsMatchingPreparation() {
    var session = accepted();
    store.prepare(session);
    store.cancelPreparation(231L, 0);
    verify(notices, never()).delete(any());
    store.cancelPreparation(231L, 1);
    verify(notices).delete(stored);
  }

  @Test
  void compensationCannotDeleteAnAlreadyActivatedFact() {
    var session = accepted();
    store.prepare(session);
    store.recordSuccessfulInitialAcceptance(session);
    store.cancelPreparation(231L, 1);
    verify(notices, never()).delete(any());
  }

  @Test
  void acceptedFactSnapshotsPersistedLocaleAndNotCurrentAsyncLocale() {
    var session = accepted();
    session.setLanguageCode(com.neovisionaries.i18n.LanguageCode.fr);
    store.prepare(session);
    assertThat(stored.getTitle()).isEqualTo("Demande acceptée");
    assertThat(stored.getDescription())
        .isEqualTo("La demande a été acceptée. Vous pouvez commencer l'entretien.");
    session.setLanguageCode(com.neovisionaries.i18n.LanguageCode.en);
    store.recordSuccessfulInitialAcceptance(session);
    assertThat(stored.getTitle()).isEqualTo("Demande acceptée");
  }

  @Test
  void missingLocaleUsesExistingGermanConversationFallback() {
    var session = accepted();
    session.setLanguageCode(null);
    store.prepare(session);
    assertThat(stored.getTitle()).isEqualTo("Anfrage angenommen");
    assertThat(stored.getDescription()).doesNotContain("verschlüsselt", "Zugriff", "private first");
  }
}
