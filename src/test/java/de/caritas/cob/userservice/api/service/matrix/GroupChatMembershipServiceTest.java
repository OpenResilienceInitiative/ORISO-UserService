package de.caritas.cob.userservice.api.service.matrix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

@ExtendWith(MockitoExtension.class)
class GroupChatMembershipServiceTest {

  private static final String MATRIX_ROOM_ID = "!room:matrix.oriso.org";
  private static final String LEAVER_MATRIX_ID = "@leaver:matrix.oriso.org";
  private static final String CONSULTANT_MATRIX_ID = "@consultant:matrix.oriso.org";
  private static final String ASKER_MATRIX_ID = "@asker:matrix.oriso.org";
  private static final String AGENCY_BOT_MATRIX_ID = "@agency-1:matrix.oriso.org";
  private static final String SYSTEM_USER_MATRIX_ID = "@group-chat-system-1:matrix.oriso.org";

  @InjectMocks private GroupChatMembershipService groupChatMembershipService;

  @Mock private MatrixSynapseService matrixSynapseService;

  @Mock private ConsultantRepository consultantRepository;

  @Mock private UserRepository userRepository;

  @Mock private Consultant consultant;

  @Mock private User user;

  private Logger logger;
  private ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  void setUpLogging() {
    logger = (Logger) LoggerFactory.getLogger(GroupChatMembershipService.class);
    logAppender = new ListAppender<>();
    logAppender.start();
    logger.addAppender(logAppender);
  }

  @AfterEach
  void tearDownLogging() {
    logger.detachAppender(logAppender);
  }

  private Chat.ChatBuilder chatBuilder() {
    return Chat.builder()
        .id(1L)
        .topic("topic")
        .initialStartDate(java.time.LocalDateTime.now())
        .startDate(java.time.LocalDateTime.now())
        .duration(60);
  }

  private Chat chatWithMatrixRoom() {
    return chatBuilder().matrixRoomId(MATRIX_ROOM_ID).groupId(MATRIX_ROOM_ID).build();
  }

  @Test
  void hasRemainingHumanMembers_Should_ReturnTrue_When_AnotherConsultantIsInRoom() {
    when(matrixSynapseService.getRoomMembers(MATRIX_ROOM_ID))
        .thenReturn(Optional.of(List.of(LEAVER_MATRIX_ID, CONSULTANT_MATRIX_ID)));
    when(consultantRepository.findByMatrixUserIdAndDeleteDateIsNull(CONSULTANT_MATRIX_ID))
        .thenReturn(Optional.of(consultant));

    assertTrue(
        groupChatMembershipService.hasRemainingHumanMembers(
            chatWithMatrixRoom(), LEAVER_MATRIX_ID));
  }

  @Test
  void hasRemainingHumanMembers_Should_ReturnTrue_When_AnotherAskerIsInRoom() {
    when(matrixSynapseService.getRoomMembers(MATRIX_ROOM_ID))
        .thenReturn(Optional.of(List.of(LEAVER_MATRIX_ID, ASKER_MATRIX_ID)));
    when(consultantRepository.findByMatrixUserIdAndDeleteDateIsNull(ASKER_MATRIX_ID))
        .thenReturn(Optional.empty());
    when(userRepository.findByMatrixUserIdAndDeleteDateIsNull(ASKER_MATRIX_ID))
        .thenReturn(Optional.of(user));
    when(user.getUserId()).thenReturn("asker-user-id");

    assertTrue(
        groupChatMembershipService.hasRemainingHumanMembers(
            chatWithMatrixRoom(), LEAVER_MATRIX_ID));
  }

  @Test
  void hasRemainingHumanMembers_Should_ReturnFalse_When_OnlyTheLeaverIsInRoom() {
    when(matrixSynapseService.getRoomMembers(MATRIX_ROOM_ID))
        .thenReturn(Optional.of(List.of(LEAVER_MATRIX_ID)));

    assertFalse(
        groupChatMembershipService.hasRemainingHumanMembers(
            chatWithMatrixRoom(), LEAVER_MATRIX_ID));
  }

  @Test
  void hasRemainingHumanMembers_Should_ReturnFalse_When_RoomIsEmpty() {
    when(matrixSynapseService.getRoomMembers(MATRIX_ROOM_ID)).thenReturn(Optional.of(List.of()));

    assertFalse(
        groupChatMembershipService.hasRemainingHumanMembers(
            chatWithMatrixRoom(), LEAVER_MATRIX_ID));
  }

  @Test
  void hasRemainingHumanMembers_Should_ReturnFalse_When_OnlyServiceAccountsRemain() {
    when(matrixSynapseService.getRoomMembers(MATRIX_ROOM_ID))
        .thenReturn(Optional.of(List.of(LEAVER_MATRIX_ID, AGENCY_BOT_MATRIX_ID)));
    when(consultantRepository.findByMatrixUserIdAndDeleteDateIsNull(AGENCY_BOT_MATRIX_ID))
        .thenReturn(Optional.empty());
    when(userRepository.findByMatrixUserIdAndDeleteDateIsNull(AGENCY_BOT_MATRIX_ID))
        .thenReturn(Optional.empty());

    assertFalse(
        groupChatMembershipService.hasRemainingHumanMembers(
            chatWithMatrixRoom(), LEAVER_MATRIX_ID));
  }

  @Test
  void hasRemainingHumanMembers_Should_ReturnFalse_When_OnlyGroupChatSystemUserRemains() {
    when(matrixSynapseService.getRoomMembers(MATRIX_ROOM_ID))
        .thenReturn(Optional.of(List.of(LEAVER_MATRIX_ID, SYSTEM_USER_MATRIX_ID)));
    when(consultantRepository.findByMatrixUserIdAndDeleteDateIsNull(SYSTEM_USER_MATRIX_ID))
        .thenReturn(Optional.empty());
    when(userRepository.findByMatrixUserIdAndDeleteDateIsNull(SYSTEM_USER_MATRIX_ID))
        .thenReturn(Optional.of(user));
    when(user.getUserId()).thenReturn("group-chat-system-1");

    assertFalse(
        groupChatMembershipService.hasRemainingHumanMembers(
            chatWithMatrixRoom(), LEAVER_MATRIX_ID));
  }

  @Test
  void hasRemainingHumanMembers_Should_FailSafeToTrue_When_MemberQueryFails() {
    when(matrixSynapseService.getRoomMembers(MATRIX_ROOM_ID)).thenReturn(Optional.empty());

    assertTrue(
        groupChatMembershipService.hasRemainingHumanMembers(
            chatWithMatrixRoom(), LEAVER_MATRIX_ID));
  }

  @Test
  void hasRemainingHumanMembers_Should_FailSafeToTrue_When_ChatHasNoMatrixRoom() {
    var legacyChat = chatBuilder().groupId("rcGroupId4711").build();

    assertTrue(groupChatMembershipService.hasRemainingHumanMembers(legacyChat, LEAVER_MATRIX_ID));
    verifyNoInteractions(matrixSynapseService);
  }

  @Test
  void hasRemainingHumanMembers_Should_FallBackToGroupId_When_ItIsAMatrixRoomId() {
    var chat = chatBuilder().groupId(MATRIX_ROOM_ID).build();
    when(matrixSynapseService.getRoomMembers(MATRIX_ROOM_ID))
        .thenReturn(Optional.of(List.of(LEAVER_MATRIX_ID)));

    assertFalse(groupChatMembershipService.hasRemainingHumanMembers(chat, LEAVER_MATRIX_ID));
  }

  @Test
  void hasRemainingHumanMembers_Should_CountAllHumans_When_LeaverIdIsUnknown() {
    when(matrixSynapseService.getRoomMembers(MATRIX_ROOM_ID))
        .thenReturn(Optional.of(List.of(CONSULTANT_MATRIX_ID)));
    when(consultantRepository.findByMatrixUserIdAndDeleteDateIsNull(CONSULTANT_MATRIX_ID))
        .thenReturn(Optional.of(consultant));

    assertTrue(groupChatMembershipService.hasRemainingHumanMembers(chatWithMatrixRoom(), null));
  }

  @Test
  void removeLeavingMemberFromRoom_Should_LeaveRoomWithLeaversOwnToken() {
    when(matrixSynapseService.loginAsUserAccessToken(LEAVER_MATRIX_ID)).thenReturn("token");
    when(matrixSynapseService.leaveRoom(MATRIX_ROOM_ID, "token")).thenReturn(true);

    groupChatMembershipService.removeLeavingMemberFromRoom(chatWithMatrixRoom(), LEAVER_MATRIX_ID);

    verify(matrixSynapseService).leaveRoom(MATRIX_ROOM_ID, "token");
  }

  @Test
  void removeLeavingMemberFromRoom_Should_DoNothing_When_LeaverHasNoMatrixId() {
    groupChatMembershipService.removeLeavingMemberFromRoom(chatWithMatrixRoom(), null);

    verifyNoInteractions(matrixSynapseService);
  }

  @Test
  void removeLeavingMemberFromRoom_Should_DoNothing_When_ChatHasNoMatrixRoom() {
    var legacyChat = chatBuilder().groupId("rcGroupId4711").build();

    groupChatMembershipService.removeLeavingMemberFromRoom(legacyChat, LEAVER_MATRIX_ID);

    verifyNoInteractions(matrixSynapseService);
  }

  @Test
  void removeLeavingMemberFromRoom_Should_NotThrow_When_TokenMintingFails() {
    when(matrixSynapseService.loginAsUserAccessToken(LEAVER_MATRIX_ID)).thenReturn(null);

    groupChatMembershipService.removeLeavingMemberFromRoom(chatWithMatrixRoom(), LEAVER_MATRIX_ID);

    verify(matrixSynapseService, never()).leaveRoom(anyString(), any());
  }

  @Test
  void removeLeavingMemberFromRoom_Should_NotThrow_When_MatrixCallFails() {
    when(matrixSynapseService.loginAsUserAccessToken(LEAVER_MATRIX_ID))
        .thenThrow(new RuntimeException("synapse down"));

    groupChatMembershipService.removeLeavingMemberFromRoom(chatWithMatrixRoom(), LEAVER_MATRIX_ID);

    verify(matrixSynapseService, never()).leaveRoom(anyString(), any());
  }

  // ── resolveHumanMembers ────────────────────────────────────────────────────

  @Test
  void resolveHumanMembers_Should_MapConsultantsAndAskers_AndFilterTechnicalAccounts() {
    when(matrixSynapseService.getRoomMembers(MATRIX_ROOM_ID))
        .thenReturn(
            Optional.of(
                List.of(
                    CONSULTANT_MATRIX_ID,
                    ASKER_MATRIX_ID,
                    AGENCY_BOT_MATRIX_ID,
                    SYSTEM_USER_MATRIX_ID)));
    when(consultantRepository.findByMatrixUserIdAndDeleteDateIsNull(CONSULTANT_MATRIX_ID))
        .thenReturn(Optional.of(consultant));
    when(consultant.getId()).thenReturn("consultant-id");
    when(consultant.getUsername()).thenReturn("consultantUsername");
    when(consultant.getDisplayName()).thenReturn("Consultant Display");

    when(consultantRepository.findByMatrixUserIdAndDeleteDateIsNull(ASKER_MATRIX_ID))
        .thenReturn(Optional.empty());
    when(userRepository.findByMatrixUserIdAndDeleteDateIsNull(ASKER_MATRIX_ID))
        .thenReturn(Optional.of(user));
    when(user.getUserId()).thenReturn("asker-id");
    when(user.getUsername()).thenReturn("askerUsername");

    // agency bot: unknown to both repositories -> filtered
    when(consultantRepository.findByMatrixUserIdAndDeleteDateIsNull(AGENCY_BOT_MATRIX_ID))
        .thenReturn(Optional.empty());
    when(userRepository.findByMatrixUserIdAndDeleteDateIsNull(AGENCY_BOT_MATRIX_ID))
        .thenReturn(Optional.empty());

    // group chat system user: a User whose userId has the system prefix -> filtered
    var systemUser =
        org.mockito.Mockito.mock(User.class, org.mockito.Mockito.withSettings().lenient());
    when(consultantRepository.findByMatrixUserIdAndDeleteDateIsNull(SYSTEM_USER_MATRIX_ID))
        .thenReturn(Optional.empty());
    when(userRepository.findByMatrixUserIdAndDeleteDateIsNull(SYSTEM_USER_MATRIX_ID))
        .thenReturn(Optional.of(systemUser));
    when(systemUser.getUserId()).thenReturn("group-chat-system-1");

    var resolved = groupChatMembershipService.resolveHumanMembers(MATRIX_ROOM_ID);

    assertEquals(2, resolved.size());
    var byId =
        resolved.stream().collect(java.util.stream.Collectors.toMap(m -> m.accountId(), m -> m));
    assertTrue(byId.containsKey("consultant-id"));
    assertTrue(byId.get("consultant-id").consultant());
    assertEquals("Consultant Display", byId.get("consultant-id").displayName());
    assertTrue(byId.containsKey("asker-id"));
    assertFalse(byId.get("asker-id").consultant());
    assertEquals("askerUsername", byId.get("asker-id").displayName());
  }

  @Test
  void resolveHumanMembers_Should_UseFullName_When_ConsultantHasNoDisplayName() {
    when(matrixSynapseService.getRoomMembers(MATRIX_ROOM_ID))
        .thenReturn(Optional.of(List.of(CONSULTANT_MATRIX_ID)));
    when(consultantRepository.findByMatrixUserIdAndDeleteDateIsNull(CONSULTANT_MATRIX_ID))
        .thenReturn(Optional.of(consultant));
    when(consultant.getId()).thenReturn("consultant-id");
    when(consultant.getDisplayName()).thenReturn(null);
    when(consultant.getFullName()).thenReturn("Jane Doe");

    var resolved = groupChatMembershipService.resolveHumanMembers(MATRIX_ROOM_ID);

    assertEquals(1, resolved.size());
    assertEquals("Jane Doe", resolved.get(0).displayName());
  }

  @Test
  void resolveHumanMembers_Should_ReturnEmpty_When_RoomStateUnknown() {
    when(matrixSynapseService.getRoomMembers(MATRIX_ROOM_ID)).thenReturn(Optional.empty());

    assertTrue(groupChatMembershipService.resolveHumanMembers(MATRIX_ROOM_ID).isEmpty());
  }

  @Test
  void resolveHumanMembers_Should_ReturnEmpty_When_RoomIdIsBlank() {
    assertTrue(groupChatMembershipService.resolveHumanMembers("  ").isEmpty());
    verifyNoInteractions(matrixSynapseService);
  }

  // ── removeMemberFromRoom ───────────────────────────────────────────────────

  @Test
  void removeMemberFromRoom_Should_LeaveRoomWithMembersOwnToken() {
    when(matrixSynapseService.loginAsUserAccessToken(CONSULTANT_MATRIX_ID)).thenReturn("token");
    when(matrixSynapseService.leaveRoom(MATRIX_ROOM_ID, "token")).thenReturn(true);

    groupChatMembershipService.removeMemberFromRoom(MATRIX_ROOM_ID, CONSULTANT_MATRIX_ID);

    verify(matrixSynapseService).leaveRoom(MATRIX_ROOM_ID, "token");
  }

  @Test
  void removeMemberFromRoom_Should_DoNothing_When_RoomOrMemberBlank() {
    groupChatMembershipService.removeMemberFromRoom("", CONSULTANT_MATRIX_ID);
    groupChatMembershipService.removeMemberFromRoom(MATRIX_ROOM_ID, "");

    verifyNoInteractions(matrixSynapseService);
  }

  // ── resolveMatrixRoomId(Session) ───────────────────────────────────────────

  @Test
  void resolveMatrixRoomId_Should_PreferSessionMatrixRoomIdColumn() {
    var session = new de.caritas.cob.userservice.api.model.Session();
    session.setMatrixRoomId(MATRIX_ROOM_ID);
    session.setGroupId("rcGroupId");

    assertEquals(MATRIX_ROOM_ID, groupChatMembershipService.resolveMatrixRoomId(session));
  }

  @Test
  void resolveMatrixRoomId_Should_FallBackToSessionGroupId_When_ItIsAMatrixRoomId() {
    var session = new de.caritas.cob.userservice.api.model.Session();
    session.setGroupId(MATRIX_ROOM_ID);

    assertEquals(MATRIX_ROOM_ID, groupChatMembershipService.resolveMatrixRoomId(session));
  }

  @Test
  void resolveMatrixRoomId_Should_ReturnNull_When_SessionHasOnlyLegacyGroupId() {
    var session = new de.caritas.cob.userservice.api.model.Session();
    session.setGroupId("rcGroupId4711");

    assertNull(groupChatMembershipService.resolveMatrixRoomId(session));
  }

  // ── resolveMatrixRoomId(Chat) gaps ─────────────────────────────────────────

  @Test
  void resolveMatrixRoomId_Should_ReturnNull_When_ChatIsNull() {
    // Callers may pass a null chat during teardown — room resolution must not NPE.
    assertNull(groupChatMembershipService.resolveMatrixRoomId((Chat) null));
  }

  @Test
  void resolveMatrixRoomId_Should_PreferMatrixRoomIdColumn_onChat() {
    // Dedicated matrix_room_id column is authoritative over legacy group_id values.
    var chat = chatBuilder().matrixRoomId(MATRIX_ROOM_ID).groupId("rcGroupId").build();
    assertEquals(MATRIX_ROOM_ID, groupChatMembershipService.resolveMatrixRoomId(chat));
  }

  @Test
  void resolveMatrixRoomId_Should_FallBackToGroupId_onChat_When_MatrixRoomIdNull() {
    // Legacy chats may only store the Matrix room id in group_id — still resolvable.
    var chat = chatBuilder().groupId(MATRIX_ROOM_ID).build();
    assertEquals(MATRIX_ROOM_ID, groupChatMembershipService.resolveMatrixRoomId(chat));
  }

  @Test
  void removeMemberFromRoom_Should_WarnAndNotThrow_When_LeaveRoomReturnsFalse() {
    // A failed Matrix leave must not abort the chat-leave flow — warn and continue.
    when(matrixSynapseService.loginAsUserAccessToken(CONSULTANT_MATRIX_ID)).thenReturn("token");
    when(matrixSynapseService.leaveRoom(MATRIX_ROOM_ID, "token")).thenReturn(false);

    org.junit.jupiter.api.Assertions.assertDoesNotThrow(
        () ->
            groupChatMembershipService.removeMemberFromRoom(MATRIX_ROOM_ID, CONSULTANT_MATRIX_ID));

    verify(matrixSynapseService).leaveRoom(MATRIX_ROOM_ID, "token");
    assertTrue(
        logAppender.list.stream()
            .anyMatch(
                e ->
                    e.getLevel().toString().equals("WARN")
                        && e.getFormattedMessage().contains("Could not remove member")));
  }

  @Test
  void resolveHumanMembers_Should_IncludeConsultant_When_DisplayNameAndFullNameBlank() {
    // Consultants without display metadata are still real members and must appear in member lists.
    when(matrixSynapseService.getRoomMembers(MATRIX_ROOM_ID))
        .thenReturn(Optional.of(List.of(CONSULTANT_MATRIX_ID)));
    when(consultantRepository.findByMatrixUserIdAndDeleteDateIsNull(CONSULTANT_MATRIX_ID))
        .thenReturn(Optional.of(consultant));
    when(consultant.getId()).thenReturn("consultant-id");
    when(consultant.getUsername()).thenReturn("consultantUsername");
    when(consultant.getDisplayName()).thenReturn("  ");
    when(consultant.getFullName()).thenReturn(null);

    var resolved = groupChatMembershipService.resolveHumanMembers(MATRIX_ROOM_ID);

    assertEquals(1, resolved.size());
    assertEquals("consultant-id", resolved.get(0).accountId());
    assertTrue(resolved.get(0).consultant());
  }

  @Test
  void resolveMatrixRoomId_Should_ReturnNull_When_SessionIsNull() {
    assertNull(groupChatMembershipService.resolveMatrixRoomId((de.caritas.cob.userservice.api.model.Session) null));
  }

  @Test
  void removeMemberFromRoom_Should_WarnAndNotThrow_When_TokenMintingFails() {
    when(matrixSynapseService.loginAsUserAccessToken(CONSULTANT_MATRIX_ID)).thenReturn(null);

    org.junit.jupiter.api.Assertions.assertDoesNotThrow(
        () -> groupChatMembershipService.removeMemberFromRoom(MATRIX_ROOM_ID, CONSULTANT_MATRIX_ID));

    verify(matrixSynapseService, never()).leaveRoom(anyString(), any());
    assertTrue(
        logAppender.list.stream()
            .anyMatch(
                e ->
                    e.getLevel().toString().equals("WARN")
                        && e.getFormattedMessage().contains("Could not mint Matrix token")));
  }

  @Test
  void removeMemberFromRoom_Should_NotThrow_When_MatrixCallFails() {
    when(matrixSynapseService.loginAsUserAccessToken(CONSULTANT_MATRIX_ID))
        .thenThrow(new RuntimeException("synapse down"));

    org.junit.jupiter.api.Assertions.assertDoesNotThrow(
        () -> groupChatMembershipService.removeMemberFromRoom(MATRIX_ROOM_ID, CONSULTANT_MATRIX_ID));

    verify(matrixSynapseService, never()).leaveRoom(anyString(), any());
  }

  @Test
  void hasRemainingHumanMembers_Should_FilterExactGroupChatSystemPrefix() {
    when(matrixSynapseService.getRoomMembers(MATRIX_ROOM_ID))
        .thenReturn(Optional.of(List.of(LEAVER_MATRIX_ID, "@group-chat-system:matrix.oriso.org")));
    when(consultantRepository.findByMatrixUserIdAndDeleteDateIsNull(
            "@group-chat-system:matrix.oriso.org"))
        .thenReturn(Optional.empty());
    when(userRepository.findByMatrixUserIdAndDeleteDateIsNull("@group-chat-system:matrix.oriso.org"))
        .thenReturn(Optional.of(user));
    when(user.getUserId()).thenReturn("group-chat-system");

    assertFalse(
        groupChatMembershipService.hasRemainingHumanMembers(
            chatWithMatrixRoom(), LEAVER_MATRIX_ID));
  }
}
