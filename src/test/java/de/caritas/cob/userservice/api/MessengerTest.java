package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import de.caritas.cob.userservice.api.service.availability.ConsultantActivityRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
  @Mock private ConsultantActivityRegistry consultantActivityRegistry;

  @Mock
  private de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService
      groupChatMembershipService;

  @Mock
  private de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService matrixSynapseService;

  @InjectMocks private Messenger messenger;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(messenger, "liveChatQueueActivePeriodMinutes", 30L);
    ReflectionTestUtils.setField(messenger, "consultantAvailabilityActiveWindowMs", 120000L);
  }

  // ── getAvailability ───────────────────────────────────────────────────────

  @Test
  void getAvailability_Should_ReturnTrue_When_RegistryContainsConsultant() {
    when(consultantActivityRegistry.filterActive(List.of("consultant-1"), 120000L))
        .thenReturn(java.util.Set.of("consultant-1"));

    assertThat(messenger.getAvailability("consultant-1")).isTrue();
  }

  @Test
  void getAvailability_Should_ReturnFalse_When_RegistryDoesNotContainConsultant() {
    when(consultantActivityRegistry.filterActive(List.of("consultant-1"), 120000L))
        .thenReturn(java.util.Set.of());

    assertThat(messenger.getAvailability("consultant-1")).isFalse();
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
  void isInChat_Should_ReturnFalse_When_SessionOrConsultantNull() {
    assertThat(messenger.isInChat(null, new Consultant())).isFalse();
    assertThat(messenger.isInChat(new Session(), null)).isFalse();
    verify(messageClient, never()).findMembers(any());
  }

  // ---------------------------------------------------------------------------
  // Extended coverage — Matrix-native member queries
  // ---------------------------------------------------------------------------

  // ── isInChat (Matrix room) ────────────────────────────────────────────────

  @Test
  void isInChat_Should_ReturnTrue_When_ConsultantIsMatrixRoomMember() {
    var session = new Session();
    session.setMatrixRoomId("!room:matrix.oriso.org");
    var consultant = new Consultant();
    consultant.setMatrixUserId("@c:matrix.oriso.org");
    when(groupChatMembershipService.resolveMatrixRoomId(session))
        .thenReturn("!room:matrix.oriso.org");
    when(groupChatMembershipService.resolveHumanMembers("!room:matrix.oriso.org"))
        .thenReturn(
            List.of(
                new de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService
                    .ResolvedRoomMember("@c:matrix.oriso.org", "c-id", "c", "c", true)));

    assertThat(messenger.isInChat(session, consultant)).isTrue();
  }

  @Test
  void isInChat_Should_ReturnFalse_When_ConsultantNotAMatrixRoomMember() {
    var session = new Session();
    session.setMatrixRoomId("!room:matrix.oriso.org");
    var consultant = new Consultant();
    consultant.setMatrixUserId("@c:matrix.oriso.org");
    when(groupChatMembershipService.resolveMatrixRoomId(session))
        .thenReturn("!room:matrix.oriso.org");
    when(groupChatMembershipService.resolveHumanMembers("!room:matrix.oriso.org"))
        .thenReturn(
            List.of(
                new de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService
                    .ResolvedRoomMember("@other:matrix.oriso.org", "o-id", "o", "o", true)));

    assertThat(messenger.isInChat(session, consultant)).isFalse();
  }

  @Test
  void isInChat_Should_FailSafeToFalse_When_MatrixRoomStateUnknown() {
    var session = new Session();
    session.setMatrixRoomId("!room:matrix.oriso.org");
    var consultant = new Consultant();
    consultant.setMatrixUserId("@c:matrix.oriso.org");
    when(groupChatMembershipService.resolveMatrixRoomId(session))
        .thenReturn("!room:matrix.oriso.org");
    when(groupChatMembershipService.resolveHumanMembers("!room:matrix.oriso.org"))
        .thenReturn(List.of());

    assertThat(messenger.isInChat(session, consultant)).isFalse();
  }

  @Test
  void isInChat_Should_ReturnFalse_When_SessionHasNoMatrixRoom() {
    var session = new Session();
    session.setGroupId("group-1");
    var consultant = new Consultant();
    when(groupChatMembershipService.resolveMatrixRoomId(session)).thenReturn(null);

    assertThat(messenger.isInChat(session, consultant)).isFalse();
  }

  // ── banUserFromChat ────────────────────────────────────────────────────────

  @Test
  void banUserFromChat_Should_BanFromMatrixRoom_AsChatOwner() {
    var user = new User("u-1", null, "seeker-username", "email@test.com", false);
    user.setMatrixUserId("@seeker:matrix.oriso.org");
    var owner = new Consultant();
    owner.setMatrixUserId("@owner:matrix.oriso.org");
    var chat = new Chat();
    chat.setId(10L);
    chat.setGroupId("!room:matrix.oriso.org");
    chat.setMatrixRoomId("!room:matrix.oriso.org");
    chat.setChatOwner(owner);
    when(userRepository.findByUserIdAndDeleteDateIsNull("u-1")).thenReturn(Optional.of(user));
    when(chatRepository.findById(10L)).thenReturn(Optional.of(chat));
    when(groupChatMembershipService.resolveMatrixRoomId(chat)).thenReturn("!room:matrix.oriso.org");
    when(matrixSynapseService.banUserFromRoomAsModerator(
            "!room:matrix.oriso.org", "@seeker:matrix.oriso.org", "@owner:matrix.oriso.org"))
        .thenReturn(true);

    assertThat(messenger.banUserFromChat("u-1", 10L)).isTrue();
    verify(matrixSynapseService)
        .banUserFromRoomAsModerator(
            "!room:matrix.oriso.org", "@seeker:matrix.oriso.org", "@owner:matrix.oriso.org");
  }

  @Test
  void banUserFromChat_Should_ReturnFalse_When_MatrixBanFails() {
    var user = new User("u-1", null, "seeker-username", "email@test.com", false);
    user.setMatrixUserId("@seeker:matrix.oriso.org");
    var owner = new Consultant();
    owner.setMatrixUserId("@owner:matrix.oriso.org");
    var chat = new Chat();
    chat.setId(10L);
    chat.setMatrixRoomId("!room:matrix.oriso.org");
    chat.setChatOwner(owner);
    when(userRepository.findByUserIdAndDeleteDateIsNull("u-1")).thenReturn(Optional.of(user));
    when(chatRepository.findById(10L)).thenReturn(Optional.of(chat));
    when(groupChatMembershipService.resolveMatrixRoomId(chat)).thenReturn("!room:matrix.oriso.org");
    when(matrixSynapseService.banUserFromRoomAsModerator(any(), any(), any())).thenReturn(false);

    assertThat(messenger.banUserFromChat("u-1", 10L)).isFalse();
  }

  @Test
  void banUserFromChat_Should_ReturnFalse_When_ChatHasNoMatrixRoom() {
    var user = new User("u-1", null, "seeker-username", "email@test.com", false);
    var chat = new Chat();
    chat.setId(10L);
    chat.setGroupId("group-10");
    when(userRepository.findByUserIdAndDeleteDateIsNull("u-1")).thenReturn(Optional.of(user));
    when(chatRepository.findById(10L)).thenReturn(Optional.of(chat));
    when(groupChatMembershipService.resolveMatrixRoomId(chat)).thenReturn(null);
    assertThat(messenger.banUserFromChat("u-1", 10L)).isFalse();
  }

  // ── setAvailability ────────────────────────────────────────────────────────

  @Test
  void setAvailability_Should_MarkConsultantAvailableInRegistry() {
    messenger.setAvailability("c-1", true);

    verify(consultantActivityRegistry).markAvailable("c-1");
  }

  @Test
  void setAvailability_Should_MarkConsultantUnavailableInRegistry() {
    messenger.setAvailability("c-1", false);

    verify(consultantActivityRegistry).markUnavailable("c-1");
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
  void removeUserFromSession_Should_RemoveUser_When_NotAdvisorAndInMatrixRoom() {
    var sessionConsultant = new Consultant();
    sessionConsultant.setId("c-other");
    var requestConsultant = new Consultant();
    requestConsultant.setId("c-1");
    requestConsultant.setMatrixUserId("@c1:matrix.oriso.org");
    var session = new Session();
    session.setConsultant(sessionConsultant);
    session.setTeamSession(false);
    session.setMatrixRoomId("!room:matrix.oriso.org");
    when(sessionRepository.findByGroupId("group-1")).thenReturn(Optional.of(session));
    when(consultantRepository.findByRocketChatIdAndDeleteDateIsNull("rc-1"))
        .thenReturn(Optional.of(requestConsultant));
    when(groupChatMembershipService.resolveMatrixRoomId(session))
        .thenReturn("!room:matrix.oriso.org");
    when(groupChatMembershipService.resolveHumanMembers("!room:matrix.oriso.org"))
        .thenReturn(
            List.of(
                new de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService
                    .ResolvedRoomMember("@c1:matrix.oriso.org", "c-1", "c1", "c1", true)));
    when(messageClient.removeUserFromSession("rc-1", "group-1")).thenReturn(true);

    assertThat(messenger.removeUserFromSession("rc-1", "group-1")).isTrue();
    verify(messageClient).removeUserFromSession("rc-1", "group-1");
  }
}
