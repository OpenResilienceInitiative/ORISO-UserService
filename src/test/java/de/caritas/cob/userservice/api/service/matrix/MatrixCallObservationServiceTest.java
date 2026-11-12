package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService.CallRoomOrigin;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.MatrixCallBinding;
import de.caritas.cob.userservice.api.model.MatrixCallObservation;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallBindingRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallObservationRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallObservationService.Outcome;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MatrixCallObservationServiceTest {
  private static final String SOURCE = "!group:example";
  private static final String MEDIA = "!media:example";
  private static final String CREATOR = "@creator:example";
  private static final String OTHER = "@other:example";

  private final MatrixCallObservationRepository repo = mock(MatrixCallObservationRepository.class);
  private final MatrixCallBindingRepository bindings = mock(MatrixCallBindingRepository.class);
  private final MatrixCallConversationResolver conversations =
      mock(MatrixCallConversationResolver.class);
  private final UserRepository users = mock(UserRepository.class);
  private final ConsultantRepository consultants = mock(ConsultantRepository.class);
  private final MatrixSynapseService matrix = mock(MatrixSynapseService.class);
  private final EventNotificationService notifications = mock(EventNotificationService.class);
  private final MatrixCallLifecycleService lifecycle = mock(MatrixCallLifecycleService.class);
  private final java.util.List<MatrixCallObservation> saved = new java.util.ArrayList<>();
  private MatrixCallObservationService service;
  private AuthenticatedUser actor;

  @BeforeEach
  void setUp() {
    service =
        new MatrixCallObservationService(
            repo, bindings, conversations, users, consultants, matrix, notifications, lifecycle);
    actor = new AuthenticatedUser();
    actor.setUserId("creator");
    actor.setTenantId(7L);
    var conversation = new MatrixCallConversation(SOURCE, 7L, null, 45L);
    when(conversations.resolveForWriting(SOURCE)).thenReturn(Optional.of(conversation));
    when(conversations.resolve(SOURCE)).thenReturn(Optional.of(conversation));
    when(conversations.lockCurrentForWriting(any())).thenReturn(true);
    var creatorUser = user("creator", CREATOR);
    var otherUser = user("other", OTHER);
    when(users.findByUserIdAndDeleteDateIsNull("creator")).thenReturn(Optional.of(creatorUser));
    when(users.findByMatrixUserIdAndDeleteDateIsNull(CREATOR)).thenReturn(Optional.of(creatorUser));
    when(users.findByMatrixUserIdAndDeleteDateIsNull(OTHER)).thenReturn(Optional.of(otherUser));
    when(consultants.findByMatrixUserIdAndDeleteDateIsNull(anyString()))
        .thenReturn(Optional.empty());
    when(lifecycle.isActiveRecipient(anyString())).thenReturn(true);
    when(matrix.getCallRoomMembers(SOURCE)).thenReturn(Optional.of(List.of(CREATOR, OTHER)));
    when(matrix.getCallRoomBinding(MEDIA, CREATOR))
        .thenReturn(Optional.of(Map.of("call_id", "call", "source_room_id", SOURCE)));
    when(matrix.getCallRoomOrigin(MEDIA, CREATOR))
        .thenReturn(Optional.of(new CallRoomOrigin(CREATOR, CREATOR)));
    when(repo.findForUpdateBySourceRoomIdAndCallId(anyString(), anyString()))
        .thenReturn(Optional.empty());
    when(repo.findByMediaRoomId(anyString())).thenReturn(Optional.empty());
    when(repo.saveAndFlush(any()))
        .thenAnswer(
            i -> {
              saved.add(i.getArgument(0));
              return i.getArgument(0);
            });
  }

  @Test
  void creatorRegistrationStoresObservationDistinctFromInvitationAndNotifiesOthersOnly() {
    assertThat(service.register(actor, SOURCE, "call", MEDIA, true)).isEqualTo(Outcome.OK);
    var row = saved.get(0);
    assertThat(row.getOrigin()).isEqualTo(MatrixCallObservation.ORIGIN_CREATOR_REGISTRATION);
    assertThat(row.getInvitedAt()).isNull();
    assertThat(row.getRegisteredAt()).isPositive();
    verify(notifications)
        .createCallInvitationNotification(
            any(), eq("other"), eq(false), eq("creator"), eq("call"), eq(MEDIA), eq(true));
    verify(notifications, never())
        .createCallInvitationNotification(
            any(), eq("creator"), anyBoolean(), any(), any(), any(), anyBoolean());
  }

  @Test
  void aCallThatAlreadyHasAnAuthoritativeBindingIsNotObserved() {
    // Observation and binding notifications share one dedupe key per (room, call). A member
    // must not be able to register a look-alike first and suppress the real call's entries.
    when(bindings.findBySourceRoomIdAndCallId(SOURCE, "call"))
        .thenReturn(Optional.of(MatrixCallBinding.builder().sourceRoomId(SOURCE).build()));

    assertThat(service.register(actor, SOURCE, "call", MEDIA, true)).isEqualTo(Outcome.NOT_FOUND);
    verify(repo, never()).saveAndFlush(any());
    verify(notifications, never())
        .createCallInvitationNotification(
            any(), any(), anyBoolean(), any(), any(), any(), anyBoolean());
  }

  @Test
  void callerOutsideTheSourceRoomIsRejected() {
    when(matrix.getCallRoomMembers(SOURCE)).thenReturn(Optional.of(List.of(OTHER)));
    assertThat(service.register(actor, SOURCE, "call", MEDIA, true)).isEqualTo(Outcome.NOT_FOUND);
    verify(repo, never()).saveAndFlush(any());
  }

  @Test
  void mediaRoomCreatedByAnotherPersonIsRejected() {
    when(matrix.getCallRoomOrigin(MEDIA, CREATOR))
        .thenReturn(Optional.of(new CallRoomOrigin(OTHER, CREATOR)));
    assertThat(service.register(actor, SOURCE, "call", MEDIA, true)).isEqualTo(Outcome.NOT_FOUND);
    verify(repo, never()).saveAndFlush(any());
  }

  @Test
  void bindingSentByAnotherPersonIsRejected() {
    when(matrix.getCallRoomOrigin(MEDIA, CREATOR))
        .thenReturn(Optional.of(new CallRoomOrigin(CREATOR, OTHER)));
    assertThat(service.register(actor, SOURCE, "call", MEDIA, true)).isEqualTo(Outcome.NOT_FOUND);
  }

  @Test
  void bindingForAnotherCallOrRoomIsRejected() {
    when(matrix.getCallRoomBinding(MEDIA, CREATOR))
        .thenReturn(Optional.of(Map.of("call_id", "x", "source_room_id", SOURCE)));
    assertThat(service.register(actor, SOURCE, "call", MEDIA, true)).isEqualTo(Outcome.NOT_FOUND);
    when(matrix.getCallRoomBinding(MEDIA, CREATOR))
        .thenReturn(Optional.of(Map.of("call_id", "call", "source_room_id", "!elsewhere:x")));
    assertThat(service.register(actor, SOURCE, "call", MEDIA, true)).isEqualTo(Outcome.NOT_FOUND);
  }

  @Test
  void groupRegistrationStoresTheChatAndNoSession() {
    assertThat(service.register(actor, SOURCE, "call", MEDIA, true)).isEqualTo(Outcome.OK);
    assertThat(saved.get(0).getChatId()).isEqualTo(45L);
    assertThat(saved.get(0).getSessionId()).isNull();
  }

  @Test
  void sessionCallIsObservedWithTheSessionAsOnlySource() {
    // #2237: a 1:1 session call invite is end-to-end encrypted, so no binding exists.
    var session = new MatrixCallConversation(SOURCE, 7L, 9L, null);
    when(conversations.resolveForWriting(SOURCE)).thenReturn(Optional.of(session));

    assertThat(service.register(actor, SOURCE, "call", MEDIA, false)).isEqualTo(Outcome.OK);

    var row = saved.get(0);
    assertThat(row.getSessionId()).isEqualTo(9L);
    assertThat(row.getChatId()).isNull();
    assertThat(row.getTenantId()).isEqualTo(7L);
    verify(conversations).lockCurrentForWriting(session);
    verify(notifications)
        .createCallInvitationNotification(
            eq(session), eq("other"), eq(false), eq("creator"), eq("call"), eq(MEDIA), eq(false));
  }

  @Test
  void sessionCallKeepsAllRegistrationChecks() {
    when(conversations.resolveForWriting(SOURCE))
        .thenReturn(Optional.of(new MatrixCallConversation(SOURCE, 7L, 9L, null)));
    when(matrix.getCallRoomMembers(SOURCE)).thenReturn(Optional.of(List.of(OTHER)));
    assertThat(service.register(actor, SOURCE, "call", MEDIA, true)).isEqualTo(Outcome.NOT_FOUND);
    when(matrix.getCallRoomMembers(SOURCE)).thenReturn(Optional.of(List.of(CREATOR, OTHER)));
    when(conversations.lockCurrentForWriting(any())).thenReturn(false); // rejected session
    assertThat(service.register(actor, SOURCE, "call", MEDIA, true)).isEqualTo(Outcome.NOT_FOUND);
    actor.setTenantId(8L);
    assertThat(service.register(actor, SOURCE, "call", MEDIA, true)).isEqualTo(Outcome.NOT_FOUND);
    verify(repo, never()).saveAndFlush(any());
  }

  @Test
  void sessionMemberReadsARunningSessionCall() {
    long now = System.currentTimeMillis();
    when(repo.findBySourceRoomIdAndCallId(SOURCE, "call"))
        .thenReturn(Optional.of(sessionRow(9L, now - 1000, now + 60_000)));
    var state = service.readState(new MatrixCallConversation(SOURCE, 7L, 9L, null), "call");
    assertThat(state.orElseThrow().state()).isEqualTo("running");
    assertThat(state.orElseThrow().callRoomId()).isEqualTo(MEDIA);
  }

  @Test
  void sessionRowIsNotReadableThroughAnotherSessionOrAChatWithTheSameId() {
    long now = System.currentTimeMillis();
    when(repo.findBySourceRoomIdAndCallId(SOURCE, "call"))
        .thenReturn(Optional.of(sessionRow(9L, now - 1000, now + 60_000)));
    assertThat(service.readState(new MatrixCallConversation(SOURCE, 7L, 10L, null), "call"))
        .isEmpty();
    assertThat(service.readState(new MatrixCallConversation(SOURCE, 7L, null, 9L), "call"))
        .isEmpty();
  }

  @Test
  void groupRowIsNotReadableThroughASessionWithTheSameId() {
    long now = System.currentTimeMillis();
    when(repo.findBySourceRoomIdAndCallId(SOURCE, "call"))
        .thenReturn(Optional.of(row(now - 1000, now + 60_000, null)));
    assertThat(service.readState(new MatrixCallConversation(SOURCE, 7L, 45L, null), "call"))
        .isEmpty();
    assertThat(service.readState(new MatrixCallConversation(SOURCE, 7L, null, 45L), "call"))
        .isPresent();
  }

  @Test
  void anotherTenantIsRejected() {
    actor.setTenantId(8L);
    assertThat(service.register(actor, SOURCE, "call", MEDIA, true)).isEqualTo(Outcome.NOT_FOUND);
  }

  @Test
  void expiredOrEndedRegistrationCannotBeRevived() {
    var row = row(1000, 2000, null);
    when(repo.findForUpdateBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(row));
    assertThat(service.register(actor, SOURCE, "call", MEDIA, true)).isEqualTo(Outcome.NOT_FOUND);
    assertThat(row.getExpiresAt()).isEqualTo(2000);
  }

  @Test
  void endedRegistrationCannotBeRevived() {
    long now = System.currentTimeMillis();
    var row = row(now - 1000, now + 60_000, now - 500);
    when(repo.findForUpdateBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(row));
    assertThat(service.register(actor, SOURCE, "call", MEDIA, true)).isEqualTo(Outcome.NOT_FOUND);
    assertThat(row.getExpiresAt()).isEqualTo(now + 60_000);
  }

  @Test
  void liveRegistrationIsRenewedWithoutNewRowOrNotifications() {
    long now = System.currentTimeMillis();
    var row = row(now - 1000, now + 1_000, null);
    when(repo.findForUpdateBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(row));
    assertThat(service.register(actor, SOURCE, "call", MEDIA, true)).isEqualTo(Outcome.OK);
    assertThat(row.getExpiresAt()).isGreaterThan(now + 60_000);
    verify(repo, never()).saveAndFlush(any());
    verify(notifications, never())
        .createCallInvitationNotification(
            any(), anyString(), anyBoolean(), any(), any(), any(), anyBoolean());
  }

  @Test
  void rejectedRegistrationsNeverSaveOrNotify() {
    when(matrix.getCallRoomOrigin(MEDIA, CREATOR))
        .thenReturn(Optional.of(new CallRoomOrigin(CREATOR, OTHER)));
    service.register(actor, SOURCE, "call", MEDIA, true);
    actor.setTenantId(8L);
    service.register(actor, SOURCE, "call", MEDIA, true);
    verify(repo, never()).saveAndFlush(any());
    verify(repo, never()).save(any());
    verify(notifications, never())
        .createCallInvitationNotification(
            any(), anyString(), anyBoolean(), any(), any(), any(), anyBoolean());
  }

  @Test
  void unknownAudienceDoesNotRecordTheEndSoARetryCanNotify() {
    long now = System.currentTimeMillis();
    var row = row(now - 1000, now + 60_000, null);
    when(repo.findForUpdateBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(row));
    when(matrix.getCallRoomMembers(SOURCE)).thenReturn(Optional.empty());
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.end(actor, SOURCE, "call"))
        .isInstanceOf(MatrixSynapseService.CallLookupUnavailableException.class);
    assertThat(row.getEndedAt()).isNull();
  }

  @Test
  void endingTwiceNotifiesOnce() {
    long now = System.currentTimeMillis();
    var row = row(now - 1000, now + 60_000, null);
    when(repo.findForUpdateBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(row));
    assertThat(service.end(actor, SOURCE, "call")).isEqualTo(Outcome.OK);
    Long firstEnd = row.getEndedAt();
    assertThat(firstEnd).isNotNull();
    assertThat(service.end(actor, SOURCE, "call")).isEqualTo(Outcome.OK);
    assertThat(row.getEndedAt()).isEqualTo(firstEnd);
    verify(notifications, org.mockito.Mockito.times(1))
        .createCallEndedNotification(
            any(), eq("other"), eq(false), eq("call"), eq(MEDIA), anyLong(), anyLong());
  }

  @Test
  void inactiveRecipientsGetNoInvitation() {
    when(lifecycle.isActiveRecipient("other")).thenReturn(false);
    assertThat(service.register(actor, SOURCE, "call", MEDIA, true)).isEqualTo(Outcome.OK);
    verify(lifecycle).isActiveRecipient("other");
    verify(notifications, never())
        .createCallInvitationNotification(
            any(), anyString(), anyBoolean(), any(), any(), any(), anyBoolean());
  }

  @Test
  void expiredObservationReadsAsEndedNeverRunning() {
    when(repo.findBySourceRoomIdAndCallId(SOURCE, "call"))
        .thenReturn(Optional.of(row(1000, 2000, null)));
    var state = service.readState(new MatrixCallConversation(SOURCE, 7L, null, 45L), "call");
    assertThat(state.orElseThrow().state()).isEqualTo("ended");
    assertThat(state.orElseThrow().endedAt()).isEqualTo(2000L);
  }

  @Test
  void liveObservationReadsRunning() {
    long now = System.currentTimeMillis();
    when(repo.findBySourceRoomIdAndCallId(SOURCE, "call"))
        .thenReturn(Optional.of(row(now - 1000, now + 60_000, null)));
    var state = service.readState(new MatrixCallConversation(SOURCE, 7L, null, 45L), "call");
    assertThat(state.orElseThrow().state()).isEqualTo("running");
  }

  @Test
  void otherTenantRowIsNotReadable() {
    long now = System.currentTimeMillis();
    when(repo.findBySourceRoomIdAndCallId(SOURCE, "call"))
        .thenReturn(Optional.of(row(now, now + 60_000, null)));
    assertThat(service.readState(new MatrixCallConversation(SOURCE, 9L, null, 45L), "call"))
        .isEmpty();
  }

  @Test
  void onlyTheCreatorEndsAndEndingNotifiesMembers() {
    long now = System.currentTimeMillis();
    var row = row(now - 1000, now + 60_000, null);
    when(repo.findForUpdateBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(row));
    var stranger = new AuthenticatedUser();
    stranger.setUserId("other");
    stranger.setTenantId(7L);
    var otherUser = user("other", OTHER);
    when(users.findByUserIdAndDeleteDateIsNull("other")).thenReturn(Optional.of(otherUser));
    assertThat(service.end(stranger, SOURCE, "call")).isEqualTo(Outcome.NOT_FOUND);
    assertThat(row.getEndedAt()).isNull();
    assertThat(service.end(actor, SOURCE, "call")).isEqualTo(Outcome.OK);
    assertThat(row.getEndedAt()).isNotNull();
    verify(notifications)
        .createCallEndedNotification(
            any(), eq("other"), eq(false), eq("call"), eq(MEDIA), anyLong(), anyLong());
  }

  private static MatrixCallObservation row(long registered, long expires, Long ended) {
    return MatrixCallObservation.builder()
        .origin(MatrixCallObservation.ORIGIN_CREATOR_REGISTRATION)
        .sourceRoomId(SOURCE)
        .callId("call")
        .mediaRoomId(MEDIA)
        .creatorMatrixId(CREATOR)
        .tenantId(7L)
        .chatId(45L)
        .registeredAt(registered)
        .lastSeenAt(registered)
        .expiresAt(expires)
        .endedAt(ended)
        .build();
  }

  private static MatrixCallObservation sessionRow(long sessionId, long registered, long expires) {
    return MatrixCallObservation.builder()
        .origin(MatrixCallObservation.ORIGIN_CREATOR_REGISTRATION)
        .sourceRoomId(SOURCE)
        .callId("call")
        .mediaRoomId(MEDIA)
        .creatorMatrixId(CREATOR)
        .tenantId(7L)
        .sessionId(sessionId)
        .registeredAt(registered)
        .lastSeenAt(registered)
        .expiresAt(expires)
        .build();
  }

  private static User user(String id, String matrixId) {
    return User.builder()
        .userId(id)
        .username(id)
        .email(id + "@example.invalid")
        .tenantId(7L)
        .matrixUserId(matrixId)
        .build();
  }
}
