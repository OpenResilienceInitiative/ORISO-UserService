package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.MessageClient;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.StringConverter;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MessengerTest {

  @Mock private MessageClient messageClient;
  @Mock private UserRepository userRepository;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private ChatRepository chatRepository;
  @Mock private SessionRepository sessionRepository;
  @Mock private UserServiceMapper mapper;
  @Mock private StringConverter stringConverter;
  @Mock private AgencyService agencyService;

  @InjectMocks private Messenger messenger;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(messenger, "liveChatQueueActivePeriodMinutes", 30L);
  }

  // ── getAvailability ───────────────────────────────────────────────────────

  @Test
  void getAvailability_Should_ReturnTrue_Always() {
    assertThat(messenger.getAvailability("consultant-1")).isTrue();
  }

  // ── countPendingEnquiriesAheadOf ─────────────────────────────────────────

  @Test
  void countPendingEnquiriesAheadOf_Should_ReturnZero_When_BeforeDateIsNull() {
    long result = messenger.countPendingEnquiriesAheadOf(1L, 1, 1L, null);

    assertThat(result).isZero();
    verify(sessionRepository, never())
        .countPendingEnquiriesAheadOf(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void countPendingEnquiriesAheadOf_Should_ReturnZero_When_ConsultingTypeIsNull() {
    long result = messenger.countPendingEnquiriesAheadOf(1L, null, 1L, LocalDateTime.now());

    assertThat(result).isZero();
  }

  @Test
  void countPendingEnquiriesAheadOf_Should_DelegateToRepository_When_ParamsValid() {
    LocalDateTime before = LocalDateTime.now();
    when(sessionRepository.countPendingEnquiriesAheadOf(
            any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(5L);

    long result = messenger.countPendingEnquiriesAheadOf(10L, 2, 3L, before);

    assertThat(result).isEqualTo(5L);
  }

  // ── markAsDirectConsultant ────────────────────────────────────────────────

  @Test
  void markAsDirectConsultant_Should_ReturnFalse_When_SessionNotFound() {
    when(sessionRepository.findById(99L)).thenReturn(Optional.empty());

    assertThat(messenger.markAsDirectConsultant(99L)).isFalse();
  }

  @Test
  void markAsDirectConsultant_Should_SetFlagAndReturnTrue_When_SessionFound() {
    Session session = new Session();
    session.setId(1L);
    session.setIsConsultantDirectlySet(false);
    when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
    when(sessionRepository.save(session)).thenReturn(session);

    // After save, session.getIsConsultantDirectlySet() is true because save returns the same object
    session.setIsConsultantDirectlySet(true);

    assertThat(messenger.markAsDirectConsultant(1L)).isTrue();
    verify(sessionRepository).save(session);
  }

  // ── existsChat / findChat ─────────────────────────────────────────────────

  @Test
  void existsChat_Should_ReturnFalse_When_ChatNotFound() {
    when(chatRepository.findById(10L)).thenReturn(Optional.empty());

    assertThat(messenger.existsChat(10L)).isFalse();
  }

  @Test
  void existsChat_Should_ReturnTrue_When_ChatExists() {
    when(chatRepository.findById(10L)).thenReturn(Optional.of(new Chat()));

    assertThat(messenger.existsChat(10L)).isTrue();
  }

  @Test
  void findChat_Should_ReturnEmpty_When_NotFound() {
    when(chatRepository.findById(20L)).thenReturn(Optional.empty());

    assertThat(messenger.findChat(20L)).isEmpty();
  }

  @Test
  void findChat_Should_ReturnChat_When_Found() {
    Chat chat = new Chat();
    when(chatRepository.findById(20L)).thenReturn(Optional.of(chat));

    assertThat(messenger.findChat(20L)).contains(chat);
  }

  // ── findSession ───────────────────────────────────────────────────────────

  @Test
  void findSession_Should_ReturnMappedResult_When_SessionFound() {
    Session session = new Session();
    Optional<Session> opt = Optional.of(session);
    Map<String, Object> mapped = Map.of("id", 1L);
    when(sessionRepository.findById(1L)).thenReturn(opt);
    when(mapper.mapOf(opt)).thenReturn(Optional.of(mapped));

    assertThat(messenger.findSession(1L)).contains(mapped);
  }

  @Test
  void findSession_Should_ReturnEmpty_When_SessionNotFound() {
    Optional<Session> empty = Optional.empty();
    when(sessionRepository.findById(99L)).thenReturn(empty);
    when(mapper.mapOf(empty)).thenReturn(Optional.empty());

    assertThat(messenger.findSession(99L)).isEmpty();
  }

  // ── isInChat ──────────────────────────────────────────────────────────────

  @Test
  void isInChat_Should_ReturnFalse_When_ChatIdIsNull() {
    assertThat(messenger.isInChat(null, "user-1")).isFalse();
    verify(messageClient, never()).findMembers(any());
  }

  // ---------------------------------------------------------------------------
  // Extended coverage — 2026-07-02
  // ---------------------------------------------------------------------------

  // ── isInChat (non-null chatId) ────────────────────────────────────────────

  @Test
  void isInChat_Should_ReturnTrue_When_UserIsMember() {
    List<Map<String, String>> membersPayload = List.of(Map.of("_id", "user-1"));
    when(messageClient.findMembers("group-1")).thenReturn(Optional.of(membersPayload));
    when(mapper.chatUserIdOf(membersPayload)).thenReturn(List.of("user-1", "user-2"));

    assertThat(messenger.isInChat("group-1", "user-1")).isTrue();
  }

  @Test
  void isInChat_Should_ReturnFalse_When_UserNotMember() {
    List<Map<String, String>> membersPayload = List.of(Map.of("_id", "other-user"));
    when(messageClient.findMembers("group-1")).thenReturn(Optional.of(membersPayload));
    when(mapper.chatUserIdOf(membersPayload)).thenReturn(List.of("other-user"));

    assertThat(messenger.isInChat("group-1", "user-1")).isFalse();
  }

  // ── banUserFromChat ────────────────────────────────────────────────────────

  @Test
  void banUserFromChat_Should_MuteUserAndReturnResult() {
    var user = new User("u-1", null, "seeker-username", "email@test.com", false);
    var chat = new Chat();
    chat.setGroupId("group-10");
    when(userRepository.findByUserIdAndDeleteDateIsNull("u-1")).thenReturn(Optional.of(user));
    when(chatRepository.findById(10L)).thenReturn(Optional.of(chat));
    when(messageClient.muteUserInChat("seeker-username", "group-10")).thenReturn(true);

    assertThat(messenger.banUserFromChat("u-1", 10L)).isTrue();
    verify(messageClient).muteUserInChat("seeker-username", "group-10");
  }

  // ── unbanUsersInChat ───────────────────────────────────────────────────────

  @Test
  void unbanUsersInChat_Should_UnmuteUsers_When_MetaInfoPresent() {
    var chat = new Chat();
    chat.setGroupId("group-10");
    Map<String, Object> metaInfo = Map.of("channels", List.of());
    when(chatRepository.findById(10L)).thenReturn(Optional.of(chat));
    when(messageClient.getChatInfo("group-10")).thenReturn(Optional.of(metaInfo));
    when(mapper.bannedUsernamesOfMap(metaInfo)).thenReturn(List.of("banned-user1"));

    messenger.unbanUsersInChat(10L, "consultant-1");

    verify(messageClient).unmuteUserInChat("banned-user1", "group-10");
  }

  @Test
  void unbanUsersInChat_Should_DoNothing_When_MetaInfoAbsent() {
    var chat = new Chat();
    chat.setGroupId("group-10");
    when(chatRepository.findById(10L)).thenReturn(Optional.of(chat));
    // Matrix room — findChatMetaInfo returns empty
    chat.setGroupId("!matrix:room");
    when(chatRepository.findById(10L)).thenReturn(Optional.of(chat));

    messenger.unbanUsersInChat(10L, "consultant-1");

    verify(messageClient, never()).unmuteUserInChat(anyString(), anyString());
  }

  // ── setAvailability ────────────────────────────────────────────────────────

  @Test
  void setAvailability_Should_SetPresenceViaClient() {
    var consultant = new Consultant();
    consultant.setId("c-1");
    consultant.setRocketChatId("rc-c-1");
    when(consultantRepository.findByIdAndDeleteDateIsNull("c-1"))
        .thenReturn(Optional.of(consultant));
    when(mapper.statusOf(true)).thenReturn("online");

    messenger.setAvailability("c-1", true);

    verify(messageClient).setUserPresence("rc-c-1", "online");
  }

  // ── findAvailableConsultants ───────────────────────────────────────────────

  @Test
  void findAvailableConsultants_Should_ReturnEmpty_When_NoPresentUsers() {
    when(messageClient.findAllAvailableUserIds()).thenReturn(new java.util.HashSet<>());

    assertThat(messenger.findAvailableConsultants(1)).isEmpty();
    verify(agencyService, never()).getAgenciesByConsultingType(anyInt());
  }

  @Test
  void findAvailableConsultants_Should_ReturnIntersection_When_PresentUsersExist() {
    var agency = new AgencyDTO();
    agency.setId(100L);
    when(messageClient.findAllAvailableUserIds())
        .thenReturn(new java.util.HashSet<>(Set.of("rc-1", "rc-2", "rc-99")));
    when(agencyService.getAgenciesByConsultingType(1)).thenReturn(List.of(agency));
    when(consultantRepository.findAllByAgencyIds(Set.of(100L))).thenReturn(Set.of("rc-1", "rc-2"));

    assertThat(messenger.findAvailableConsultants(1)).containsExactlyInAnyOrder("rc-1", "rc-2");
  }

  // ── updateE2eKeys ──────────────────────────────────────────────────────────

  @Test
  void updateE2eKeys_Should_ReturnTrue_When_NoChatsFound() {
    when(messageClient.findAllChats("rc-u1")).thenReturn(Optional.empty());

    assertThat(messenger.updateE2eKeys("rc-u1", "pub-key")).isTrue();
  }

  @Test
  void updateE2eKeys_Should_ReturnTrue_When_AllChatsUpdated() {
    Map<String, String> chat1 = Map.of("rid", "room-1", "userId", "u-1");
    when(messageClient.findAllChats("rc-u1")).thenReturn(Optional.of(List.of(chat1)));
    when(stringConverter.hashOf("rc-u1")).thenReturn("master-key");
    when(mapper.userIdOf(chat1)).thenReturn("u-1");
    when(mapper.roomIdOf(chat1)).thenReturn("room-1");
    when(mapper.e2eKeyOf(chat1)).thenReturn(Optional.of("e2eKey:0123456789abcdefENCRYPTED"));
    when(stringConverter.aesDecrypt(any(), any())).thenReturn("decrypted-room-key");
    when(stringConverter.rsaEncrypt(any(), any())).thenReturn(new byte[] {1, 2, 3});
    when(stringConverter.int8Array(any())).thenReturn(new int[] {1, 2, 3});
    when(stringConverter.jsonStringify(any())).thenReturn("json-string");
    when(stringConverter.base64AsciiEncode(any())).thenReturn("base64");
    when(messageClient.updateChatE2eKey(eq("u-1"), eq("room-1"), any())).thenReturn(true);

    assertThat(messenger.updateE2eKeys("rc-u1", "pub-key")).isTrue();
  }

  @Test
  void updateE2eKeys_Should_ReturnFalse_When_OneUpdateFails() {
    Map<String, String> chat1 = Map.of("rid", "room-1");
    when(messageClient.findAllChats("rc-u1")).thenReturn(Optional.of(List.of(chat1)));
    when(stringConverter.hashOf("rc-u1")).thenReturn("master-key");
    when(mapper.userIdOf(chat1)).thenReturn("u-1");
    when(mapper.roomIdOf(chat1)).thenReturn("room-1");
    when(mapper.e2eKeyOf(chat1)).thenReturn(Optional.of("e2eKey:0123456789abcdefENCRYPTED"));
    when(stringConverter.aesDecrypt(any(), any())).thenReturn("decrypted");
    when(stringConverter.rsaEncrypt(any(), any())).thenReturn(new byte[] {4, 5, 6});
    when(stringConverter.int8Array(any())).thenReturn(new int[] {4, 5, 6});
    when(stringConverter.jsonStringify(any())).thenReturn("json");
    when(stringConverter.base64AsciiEncode(any())).thenReturn("b64");
    when(messageClient.updateChatE2eKey(any(), any(), any())).thenReturn(false);

    assertThat(messenger.updateE2eKeys("rc-u1", "pub-key")).isFalse();
  }

  // ── removeUserFromSession ──────────────────────────────────────────────────

  @Test
  void removeUserFromSession_Should_SkipRemoval_When_ConsultantIsAdvisor() {
    var consultant = new Consultant();
    consultant.setId("c-1");
    var session = new Session();
    session.setConsultant(consultant);
    when(sessionRepository.findByGroupId("group-1")).thenReturn(Optional.of(session));
    when(consultantRepository.findByRocketChatIdAndDeleteDateIsNull("rc-1"))
        .thenReturn(Optional.of(consultant));

    assertThat(messenger.removeUserFromSession("rc-1", "group-1")).isTrue();
    verify(messageClient, never()).removeUserFromSession(anyString(), anyString());
  }

  @Test
  void removeUserFromSession_Should_RemoveUser_When_NotAdvisorAndInChat() {
    var sessionConsultant = new Consultant();
    sessionConsultant.setId("c-other");
    var requestConsultant = new Consultant();
    requestConsultant.setId("c-1");
    var session = new Session();
    session.setConsultant(sessionConsultant);
    session.setTeamSession(false);
    List<Map<String, String>> membersPayload = List.of(Map.of("_id", "rc-1"));
    when(sessionRepository.findByGroupId("group-1")).thenReturn(Optional.of(session));
    when(consultantRepository.findByRocketChatIdAndDeleteDateIsNull("rc-1"))
        .thenReturn(Optional.of(requestConsultant));
    when(messageClient.findMembers("group-1")).thenReturn(Optional.of(membersPayload));
    when(mapper.chatUserIdOf(membersPayload)).thenReturn(List.of("rc-1"));
    when(messageClient.removeUserFromSession("rc-1", "group-1")).thenReturn(true);

    assertThat(messenger.removeUserFromSession("rc-1", "group-1")).isTrue();
    verify(messageClient).removeUserFromSession("rc-1", "group-1");
  }

  // ── findChatMetaInfo ───────────────────────────────────────────────────────

  @Test
  void findChatMetaInfo_Should_ReturnEmpty_When_GroupIsMatrixRoom() {
    var chat = new Chat();
    chat.setGroupId("!matrix:server.org");
    when(chatRepository.findById(5L)).thenReturn(Optional.of(chat));

    assertThat(messenger.findChatMetaInfo(5L, "user-1")).isEmpty();
    verify(messageClient, never()).getChatInfo(anyString());
  }

  @Test
  void findChatMetaInfo_Should_DelegateToClient_When_GroupIsLegacyRcRoom() {
    var chat = new Chat();
    chat.setGroupId("GENERAL");
    Map<String, Object> metaInfo = Map.of("_id", "GENERAL");
    when(chatRepository.findById(5L)).thenReturn(Optional.of(chat));
    when(messageClient.getChatInfo("GENERAL")).thenReturn(Optional.of(metaInfo));

    assertThat(messenger.findChatMetaInfo(5L, "user-1")).contains(metaInfo);
  }
}
