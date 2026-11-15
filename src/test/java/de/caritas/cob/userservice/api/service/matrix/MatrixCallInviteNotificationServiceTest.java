package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService.CallRoomOrigin;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Security review 5901360577 (Shirloin), point 2: the plaintext invite creates a call binding just
 * as {@link MatrixSessionCallBindingService#bind} does, so it needs the same evidence that the call
 * room is a dedicated call room of this call: not one of ORISO's conversation rooms, and created
 * and bound by the inviting member.
 */
class MatrixCallInviteNotificationServiceTest {
  private static final String SOURCE = "!group-source:example";
  private static final String MEDIA = "!call-media:example";
  private static final String CALLER = "@counsellor:example";
  private static final String MEMBER = "@asker:example";
  private static final long TENANT = 7L;

  private final MatrixSynapseService matrix = mock(MatrixSynapseService.class);
  private final MatrixCallConversationResolver conversations =
      mock(MatrixCallConversationResolver.class);
  private final UserRepository users = mock(UserRepository.class);
  private final ConsultantRepository consultants = mock(ConsultantRepository.class);
  private final MatrixCallBindingService callBindings = mock(MatrixCallBindingService.class);
  private final MatrixCallLifecycleService lifecycle = mock(MatrixCallLifecycleService.class);
  private final OrisoRoomRegistry rooms = mock(OrisoRoomRegistry.class);
  private MatrixCallInviteNotificationService service;

  @BeforeEach
  void setUp() {
    service =
        new MatrixCallInviteNotificationService(
            matrix,
            conversations,
            users,
            consultants,
            mock(EventNotificationService.class),
            callBindings,
            lifecycle,
            mock(MatrixCallGuestService.class),
            mock(SessionCallAuthority.class),
            new DedicatedCallRoom(rooms, matrix));
    // A group chat: any member's invite carries the call (the 1:1 authority rule does not apply).
    when(conversations.resolveForWriting(SOURCE))
        .thenReturn(Optional.of(new MatrixCallConversation(SOURCE, TENANT, null, 3L)));
    when(conversations.lockCurrentForWriting(any())).thenReturn(true);
    when(matrix.getCallRoomMembers(SOURCE)).thenReturn(Optional.of(List.of(CALLER, MEMBER)));
    var consultant = new Consultant();
    consultant.setId("counsellor-id");
    consultant.setTenantId(TENANT);
    consultant.setMatrixUserId(CALLER);
    when(consultants.findByMatrixUserIdAndDeleteDateIsNull(CALLER))
        .thenReturn(Optional.of(consultant));
    when(users.findByMatrixUserIdAndDeleteDateIsNull(anyString())).thenReturn(Optional.empty());
    var asker = new User();
    asker.setUserId("asker-id");
    asker.setTenantId(TENANT);
    asker.setMatrixUserId(MEMBER);
    when(users.findByMatrixUserIdAndDeleteDateIsNull(MEMBER)).thenReturn(Optional.of(asker));
    when(lifecycle.isActiveRecipient(anyString())).thenReturn(true);
    when(matrix.getCallRoomBinding(anyString(), anyString()))
        .thenReturn(Optional.of(Map.of("call_id", "call", "source_room_id", SOURCE)));
    when(matrix.getCallRoomOrigin(MEDIA, CALLER))
        .thenReturn(Optional.of(new CallRoomOrigin(CALLER, CALLER)));
    when(callBindings.register(any())).thenReturn(true);
  }

  @Test
  void aDedicatedCallRoomTheCallerCreatedAndBoundIsRegistered() {
    assertThat(service.handle(SOURCE, invite(MEDIA))).isTrue();

    verify(callBindings).register(any());
  }

  @Test
  void anOrisoConversationRoomIsNeverRegisteredAsTheCallRoom() {
    // E.g. a 1:1 session room the member is also in: the observer would join it as admin and an
    // admitted guest would be invited into that conversation.
    when(rooms.isKnownConversationRoom(MEDIA)).thenReturn(true);

    assertThat(service.handle(SOURCE, invite(MEDIA))).isFalse();

    verify(callBindings, never()).register(any());
    verify(matrix, never()).ensureAdminInRoom(anyString(), anyString());
  }

  @Test
  void theConversationItselfIsNeverRegisteredAsTheCallRoom() {
    assertThat(service.handle(SOURCE, invite(SOURCE))).isFalse();

    verify(callBindings, never()).register(any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"creator", "binding sender", "both", "unreadable"})
  void aCallRoomTheCallerDidNotCreateAndBindIsNeverRegistered(String foreign) {
    when(matrix.getCallRoomOrigin(MEDIA, CALLER))
        .thenReturn(
            switch (foreign) {
              case "creator" -> Optional.of(new CallRoomOrigin(MEMBER, CALLER));
              case "binding sender" -> Optional.of(new CallRoomOrigin(CALLER, MEMBER));
              case "both" -> Optional.of(new CallRoomOrigin(MEMBER, MEMBER));
              default -> Optional.empty();
            });

    assertThat(service.handle(SOURCE, invite(MEDIA))).isFalse();

    verify(callBindings, never()).register(any());
  }

  private static Map<String, Object> invite(String callRoom) {
    return Map.of(
        "type",
        "org.oriso.call.invite",
        "sender",
        CALLER,
        "event_id",
        "$invite",
        "origin_server_ts",
        System.currentTimeMillis(),
        "content",
        Map.of("call_id", "call", "call_room_id", callRoom, "lifetime", 60000, "is_video", true));
  }
}
