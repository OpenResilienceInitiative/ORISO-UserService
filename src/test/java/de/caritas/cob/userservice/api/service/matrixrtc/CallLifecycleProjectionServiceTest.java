package de.caritas.cob.userservice.api.service.matrixrtc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.model.CallAttendanceInterval;
import de.caritas.cob.userservice.api.model.CallLifecycleProjection;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserChat;
import de.caritas.cob.userservice.api.port.out.CallAttendanceIntervalRepository;
import de.caritas.cob.userservice.api.port.out.CallLifecycleProjectionRepository;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserChatRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.notification.CallLifecycleEmailNotificationService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CallLifecycleProjectionServiceTest {

  private static final String ROOM_ID = "!room:matrix.example";
  private static final String CALL_ROOM_ID = "!call:matrix.example";
  private static final String MATRIX_USER_ID = "@user:matrix.example";

  @Mock private CallLifecycleProjectionRepository lifecycleRepository;
  @Mock private CallAttendanceIntervalRepository attendanceRepository;
  @Mock private SessionRepository sessionRepository;
  @Mock private ChatRepository chatRepository;
  @Mock private GroupChatParticipantRepository groupChatParticipantRepository;
  @Mock private UserChatRepository userChatRepository;
  @Mock private UserRepository userRepository;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private EventNotificationService eventNotificationService;
  @Mock private CallLifecycleEmailNotificationService emailNotificationService;

  private CallLifecycleProjectionService service;

  @BeforeEach
  void setUp() {
    service =
        new CallLifecycleProjectionService(
            lifecycleRepository,
            attendanceRepository,
            sessionRepository,
            chatRepository,
            groupChatParticipantRepository,
            userChatRepository,
            userRepository,
            consultantRepository,
            eventNotificationService,
            emailNotificationService,
            new ObjectMapper());
    Session session = session();
    lenient().when(sessionRepository.findByMatrixRoomId(ROOM_ID)).thenReturn(Optional.of(session));
    when(lifecycleRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void inviteEmitsDeduplicatedNotificationWithStableCallContract() throws Exception {
    Consultant caller = mock(Consultant.class);
    when(caller.getId()).thenReturn("consultant-1");
    when(consultantRepository.findByMatrixUserIdAndDeleteDateIsNull("@consultant:matrix.example"))
        .thenReturn(Optional.of(caller));
    when(eventNotificationService.createEventOnce(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(true);
    var event =
        Map.<String, Object>of(
            "type",
            "m.call.invite",
            "sender",
            "@consultant:matrix.example",
            "origin_server_ts",
            1_788_912_000_000L,
            "content",
            Map.of("call_id", "call-1", "is_video", true, "lifetime", 30_000));

    assertThat(service.project(ROOM_ID, event)).isTrue();

    ArgumentCaptor<String> params = ArgumentCaptor.forClass(String.class);
    verify(eventNotificationService, atLeastOnce())
        .createEventOnce(
            any(),
            any(),
            eq("call.invited"),
            eq(EventNotificationService.CATEGORY_SYSTEM),
            any(),
            any(),
            params.capture(),
            any(),
            eq(42L),
            eq(9L));
    var json = new ObjectMapper().readTree(params.getValue());
    assertThat(json.get("callId").asText()).isEqualTo("call-1");
    assertThat(json.get("callType").asText()).isEqualTo("video");
    assertThat(json.get("isVideo").asBoolean()).isTrue();
    assertThat(json.get("roomRef").asText()).isEqualTo(ROOM_ID);
    verify(emailNotificationService).sendInvitation("user-1", 9L);
    verify(emailNotificationService, never()).sendInvitation("consultant-1", 9L);
  }

  @Test
  void matrixRtcMembershipStartsCallAndPersistsOpenAttendanceWithoutJoinNotification() {
    User domainUser = mock(User.class);
    when(domainUser.getUserId()).thenReturn("user-1");
    when(userRepository.findByMatrixUserIdAndDeleteDateIsNull(MATRIX_USER_ID))
        .thenReturn(Optional.of(domainUser));
    when(lifecycleRepository.findByMatrixRoomId(ROOM_ID)).thenReturn(List.of());

    var event =
        Map.<String, Object>of(
            "type",
            "org.matrix.msc3401.call.member",
            "state_key",
            MATRIX_USER_ID,
            "origin_server_ts",
            1_788_912_000_000L,
            "content",
            Map.of(
                "memberships",
                List.of(
                    Map.of(
                        "m.call_id",
                        "call-2",
                        "m.device_id",
                        "device-1",
                        "m.expires",
                        1_788_915_600_000L,
                        "call_type",
                        "audio"))));

    assertThat(service.project(ROOM_ID, event)).isTrue();

    verify(attendanceRepository)
        .save(
            org.mockito.ArgumentMatchers.argThat(
                interval ->
                    interval.getMatrixUserId().equals(MATRIX_USER_ID)
                        && interval.getDomainUserId().equals("user-1")
                        && interval.getLeftAt() == null));
    verify(eventNotificationService, atLeastOnce())
        .createEventOnce(
            any(), any(), eq("call.started"), any(), any(), any(), any(), any(), any(), any());
    verify(eventNotificationService, never())
        .createEventOnce(
            any(), any(), eq("call.joined"), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void hangupClosesAttendanceAndEmitsEndedPlusMissedForNonAttendee() {
    var lifecycle =
        CallLifecycleProjection.builder()
            .id(1L)
            .matrixRoomId(ROOM_ID)
            .callRoomId(ROOM_ID)
            .callId("call-3")
            .callType("audio")
            .status("STARTED")
            .sourceSessionId(42L)
            .tenantId(9L)
            .startedAt(LocalDateTime.of(2026, 9, 10, 9, 0))
            .createDate(LocalDateTime.of(2026, 9, 10, 9, 0))
            .updateDate(LocalDateTime.of(2026, 9, 10, 9, 0))
            .build();
    var attendee =
        CallAttendanceInterval.builder()
            .callLifecycle(lifecycle)
            .matrixUserId(MATRIX_USER_ID)
            .domainUserId("user-1")
            .deviceId("device-1")
            .joinedAt(LocalDateTime.of(2026, 9, 10, 9, 0))
            .build();
    when(lifecycleRepository.findByMatrixRoomIdAndCallId(ROOM_ID, "call-3"))
        .thenReturn(Optional.of(lifecycle));
    when(attendanceRepository.findByCallLifecycleAndLeftAtIsNull(lifecycle))
        .thenReturn(List.of(attendee));
    when(attendanceRepository.findByCallLifecycle(lifecycle)).thenReturn(List.of(attendee));
    when(eventNotificationService.createEventOnce(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(true);

    assertThat(
            service.project(
                ROOM_ID,
                Map.of(
                    "type",
                    "m.call.hangup",
                    "origin_server_ts",
                    1_788_915_600_000L,
                    "content",
                    Map.of("call_id", "call-3"))))
        .isTrue();

    assertThat(attendee.getLeftAt()).isNotNull();
    verify(eventNotificationService, atLeastOnce())
        .createEventOnce(
            any(), any(), eq("call.ended"), any(), any(), any(), any(), any(), any(), any());
    verify(eventNotificationService)
        .createEventOnce(
            any(),
            eq("consultant-1"),
            eq("call.missed"),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any());
    verify(emailNotificationService).sendMissed("consultant-1", 9L);
  }

  @Test
  void groupCallFansOutToOwnerModeratorsAndMembers() {
    Consultant owner = mock(Consultant.class);
    User member = mock(User.class);
    when(owner.getId()).thenReturn("owner-1");
    when(owner.getTenantId()).thenReturn(9L);
    when(member.getUserId()).thenReturn("member-1");
    Chat chat =
        Chat.builder()
            .id(84L)
            .topic("Group")
            .initialStartDate(LocalDateTime.of(2026, 9, 10, 9, 0))
            .startDate(LocalDateTime.of(2026, 9, 10, 9, 0))
            .chatOwner(owner)
            .build();
    when(sessionRepository.findByMatrixRoomId(ROOM_ID)).thenReturn(Optional.empty());
    when(chatRepository.findByMatrixRoomId(ROOM_ID)).thenReturn(Optional.of(chat));
    when(groupChatParticipantRepository.findBySeriesId(84L))
        .thenReturn(
            List.of(
                GroupChatParticipant.builder().seriesId(84L).consultantId("moderator-1").build()));
    when(userChatRepository.findByChat(chat))
        .thenReturn(List.of(UserChat.builder().chat(chat).user(member).build()));

    assertThat(
            service.project(
                ROOM_ID,
                Map.of(
                    "type",
                    "org.oriso.call.invited",
                    "content",
                    Map.of("call_id", "group-call", "call_type", "video"))))
        .isTrue();

    for (String recipient : List.of("owner-1", "moderator-1", "member-1")) {
      verify(eventNotificationService)
          .createEventOnce(
              any(),
              eq(recipient),
              eq("call.invited"),
              any(),
              any(),
              any(),
              any(),
              any(),
              eq(84L),
              eq(9L));
    }
  }

  @Test
  void signallingRoomMembershipUsesLifecycleCallRoomMapping() {
    var lifecycle =
        CallLifecycleProjection.builder()
            .id(2L)
            .matrixRoomId(ROOM_ID)
            .callRoomId(CALL_ROOM_ID)
            .callId("mapped-call")
            .callType("video")
            .status("INVITED")
            .sourceSessionId(42L)
            .tenantId(9L)
            .createDate(LocalDateTime.of(2026, 9, 10, 9, 0))
            .updateDate(LocalDateTime.of(2026, 9, 10, 9, 0))
            .build();
    User domainUser = mock(User.class);
    when(domainUser.getUserId()).thenReturn("user-1");
    when(userRepository.findByMatrixUserIdAndDeleteDateIsNull(MATRIX_USER_ID))
        .thenReturn(Optional.of(domainUser));
    when(lifecycleRepository.findByCallRoomIdAndCallId(CALL_ROOM_ID, "mapped-call"))
        .thenReturn(Optional.of(lifecycle));

    assertThat(
            service.project(
                CALL_ROOM_ID,
                Map.of(
                    "type",
                    "m.call.member",
                    "state_key",
                    MATRIX_USER_ID,
                    "content",
                    Map.of(
                        "memberships",
                        List.of(Map.of("call_id", "mapped-call", "device_id", "device-1"))))))
        .isTrue();

    verify(attendanceRepository)
        .save(
            org.mockito.ArgumentMatchers.argThat(
                interval -> interval.getCallLifecycle() == lifecycle));
  }

  private Session session() {
    User user = mock(User.class);
    Consultant consultant = mock(Consultant.class);
    lenient().when(user.getUserId()).thenReturn("user-1");
    lenient().when(consultant.getId()).thenReturn("consultant-1");
    Session session = mock(Session.class);
    lenient().when(session.getId()).thenReturn(42L);
    lenient().when(session.getTenantId()).thenReturn(9L);
    lenient().when(session.getUser()).thenReturn(user);
    lenient().when(session.getConsultant()).thenReturn(consultant);
    return session;
  }
}
