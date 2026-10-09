package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.InquiryAcceptanceNotice;
import de.caritas.cob.userservice.api.model.InquiryAcceptanceNotice.DeliveryState;
import de.caritas.cob.userservice.api.port.out.InquiryAcceptanceNoticeRepository;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InquiryAcceptanceNoticeDeliveryTest {
  final InquiryAcceptanceNoticeRepository notices = mock(InquiryAcceptanceNoticeRepository.class);
  final InquiryAcceptanceNoticeStore store = mock(InquiryAcceptanceNoticeStore.class);
  final MatrixSynapseService matrix = mock(MatrixSynapseService.class);
  final InquiryAcceptanceNoticeDelivery delivery =
      new InquiryAcceptanceNoticeDelivery(notices, store, matrix);

  InquiryAcceptanceNotice notice() {
    var n =
        InquiryAcceptanceNotice.builder()
            .sessionId(231L)
            .acceptedAtUtc(LocalDateTime.of(2026, 10, 9, 10, 20))
            .matrixRoomId("!r:test")
            .senderMatrixId("@c:test")
            .publicAdvisorName("Public advisor")
            .title("Request accepted")
            .description("The request was accepted. You can start the conversation.")
            .deliveryState(DeliveryState.PENDING)
            .build();
    when(notices.findById(231L)).thenReturn(Optional.of(n));
    return n;
  }

  @Test
  void realEnvelopeContainsStoredTimeAndDistinctEventIdentity() throws Exception {
    var n = notice();
    var body = delivery.body(n);
    var json =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(
                body.substring(
                    MatrixSessionSystemMessageService.SYSTEM_NOTIFICATION_PREFIX.length()));
    assertThat(json.get("type").asText()).isEqualTo("INQUIRY_ACCEPTED");
    assertThat(json.get("title").asText()).isEqualTo("Request accepted");
    assertThat(json.get("description").asText())
        .isEqualTo("The request was accepted. You can start the conversation.");
    assertThat(json.path("acceptance").path("sessionId").asLong()).isEqualTo(231L);
    assertThat(json.path("acceptance").path("acceptedAt").asText())
        .isEqualTo("2026-10-09T10:20:00Z");
    assertThat(body)
        .doesNotContain("CASE_HANDOVER_GRANTED", "FIRST_RESPONSE", "clientConsent", "grant");
  }

  @Test
  void acknowledgedSendUsesStableTransactionAndExistingSender() {
    notice();
    when(matrix.loginAsUserAccessToken("@c:test")).thenReturn("test-token");
    when(store.claimForSend(231L)).thenReturn(true);
    when(matrix.sendMessage(
            eq("!r:test"), anyString(), eq("test-token"), eq("inquiry-accepted-231")))
        .thenReturn(Map.of("event_id", "$event"));
    delivery.dispatch(231L);
    var ordered = inOrder(matrix, store);
    ordered.verify(matrix).loginAsUserAccessToken("@c:test");
    ordered.verify(store).claimForSend(231L);
    ordered
        .verify(matrix)
        .sendMessage(eq("!r:test"), anyString(), eq("test-token"), eq("inquiry-accepted-231"));
    ordered.verify(store).acknowledge(231L, "$event");
  }

  @Test
  void preSendTokenFailureIsRetryableWithoutClaimOrEvent() {
    notice();
    when(matrix.loginAsUserAccessToken("@c:test")).thenReturn(null, "test-token");
    when(store.claimForSend(231L)).thenReturn(true);
    delivery.dispatch(231L);
    verify(store).deferPending(231L);
    verify(store, never()).claimForSend(any());
    delivery.dispatch(231L);
    verify(matrix, times(1)).sendMessage(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void concurrentLosingClaimDoesNotSend() {
    notice();
    when(matrix.loginAsUserAccessToken("@c:test")).thenReturn("test-token");
    when(store.claimForSend(231L)).thenReturn(false);
    delivery.dispatch(231L);
    verify(matrix, never()).sendMessage(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void ambiguousResponseRemainsUncertainAndIsNeverBlindlyResent() {
    var n = notice();
    when(matrix.loginAsUserAccessToken("@c:test")).thenReturn("test-token");
    when(store.claimForSend(231L))
        .thenAnswer(
            i -> {
              n.setDeliveryState(DeliveryState.UNCERTAIN);
              return true;
            });
    when(matrix.sendMessage(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(Map.of("error", "timeout"));
    delivery.dispatch(231L);
    delivery.dispatch(231L);
    verify(matrix, times(1)).sendMessage(anyString(), anyString(), anyString(), anyString());
    verify(store, never()).acknowledge(any(), anyString());
  }

  @Test
  void exceptionAfterClaimCannotEscapeAcceptance() {
    notice();
    when(matrix.loginAsUserAccessToken("@c:test")).thenReturn("test-token");
    when(store.claimForSend(231L)).thenReturn(true);
    when(matrix.sendMessage(anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new IllegalStateException("private backend detail"));
    assertThatCode(() -> delivery.dispatchSafely(231L)).doesNotThrowAnyException();
    verify(store, never()).acknowledge(any(), anyString());
  }

  @Test
  void oldInFlightPreparationWithPreexistingMembersNeverBecomesAnAcceptance() {
    var n = notice();
    n.setDeliveryState(DeliveryState.PREPARING);
    n.setOwnerId("winner");
    n.setOwnershipRevision(1);
    n.setAcceptedAtUtc(LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(20));

    when(notices.findByIdForUpdate(231L)).thenReturn(Optional.of(n));
    var sessions = mock(de.caritas.cob.userservice.api.port.out.SessionRepository.class);
    var winner = new de.caritas.cob.userservice.api.model.Consultant();
    winner.setId("winner");
    winner.setMatrixUserId("@c:test");
    var asker = new de.caritas.cob.userservice.api.model.User();
    asker.setMatrixUserId("@a:test");
    var session = new de.caritas.cob.userservice.api.model.Session();
    session.setId(231L);
    session.setStatus(de.caritas.cob.userservice.api.model.Session.SessionStatus.IN_PROGRESS);
    session.setConversationType(
        de.caritas.cob.userservice.api.model.ConversationType.AGENCY_COUNSELLING);
    session.setConsultant(winner);
    session.setUser(asker);
    session.setOwnershipRevision(1);
    session.setMatrixRoomId("!r:test");
    when(sessions.findByIdForUpdate(231L)).thenReturn(Optional.of(session));
    var actualStore =
        new InquiryAcceptanceNoticeStore(
            sessions,
            notices,
            new de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver());
    var actualDelivery = new InquiryAcceptanceNoticeDelivery(notices, actualStore, matrix);
    when(matrix.getRoomMembers("!r:test"))
        .thenReturn(Optional.of(java.util.List.of("@a:test", "@c:test")));
    when(matrix.loginAsUserAccessToken("@c:test")).thenReturn("test-token");
    when(matrix.sendMessage(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(Map.of("event_id", "$false-acceptance"));
    actualDelivery.dispatch(231L);
    assertThat(n.getDeliveryState()).isEqualTo(DeliveryState.PREPARING);
    verify(matrix, never()).sendMessage(anyString(), anyString(), anyString(), anyString());
    // The original operation can still fail after a delivery attempt.
    actualStore.cancelPreparation(231L, 1);
    verify(notices).delete(n);
  }
}
