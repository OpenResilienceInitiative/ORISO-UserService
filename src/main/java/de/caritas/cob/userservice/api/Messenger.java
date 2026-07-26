package de.caritas.cob.userservice.api;

import static java.util.Objects.isNull;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.in.Messaging;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.MessageClient;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.StringConverter;
import de.caritas.cob.userservice.api.service.availability.ConsultantActivityRegistry;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService.ResolvedRoomMember;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class Messenger implements Messaging {

  private final MessageClient messageClient;
  private final UserRepository userRepository;
  private final ConsultantRepository consultantRepository;
  private final ChatRepository chatRepository;
  private final SessionRepository sessionRepository;
  private final UserServiceMapper mapper;
  private final StringConverter stringConverter;
  private final GroupChatMembershipService groupChatMembershipService;
  private final MatrixSynapseService matrixSynapseService;
  private final ConsultantActivityRegistry consultantActivityRegistry;

  @Value("${user.anonymous.deactivateworkflow.periodMinutes}")
  private long liveChatQueueActivePeriodMinutes;

  @Value("${consultant.availability.activeWindowMs:120000}")
  private long consultantAvailabilityActiveWindowMs;

  @Override
  public boolean banUserFromChat(String adviceSeekerId, long chatId) {
    var adviceSeeker = userRepository.findByUserIdAndDeleteDateIsNull(adviceSeekerId).orElseThrow();
    var chat = chatRepository.findById(chatId).orElseThrow();

    var matrixRoomId = groupChatMembershipService.resolveMatrixRoomId(chat);
    if (StringUtils.isNotBlank(matrixRoomId)) {
      return banUserFromMatrixRoom(adviceSeeker, chat, matrixRoomId);
    }

    log.warn("Cannot ban user from chat {}: no Matrix room id", chatId);
    return false;
  }

  /**
   * Bans the adviceseeker from the chat's Matrix room, acting as the chat owner (a consultant with
   * ban power in the room). A Matrix ban both removes the user and blocks re-join, which is the
   * Matrix-native replacement for the former Rocket.Chat mute/ban.
   *
   * @return true when the ban succeeded (or the user was already gone), false when it could not be
   *     performed — the controller maps false to "user not found in chat".
   */
  private boolean banUserFromMatrixRoom(User adviceSeeker, Chat chat, String matrixRoomId) {
    if (StringUtils.isBlank(adviceSeeker.getMatrixUserId())) {
      log.warn(
          "Cannot ban adviceseeker {} from Matrix room {}: no Matrix user id",
          adviceSeeker.getUserId(),
          matrixRoomId);
      return false;
    }
    var chatOwner = chat.getChatOwner();
    if (chatOwner == null || StringUtils.isBlank(chatOwner.getMatrixUserId())) {
      log.warn(
          "Cannot ban adviceseeker {} from Matrix room {}: chat {} has no moderator with a Matrix"
              + " user id",
          adviceSeeker.getUserId(),
          matrixRoomId,
          chat.getId());
      return false;
    }
    return matrixSynapseService.banUserFromRoomAsModerator(
        matrixRoomId, adviceSeeker.getMatrixUserId(), chatOwner.getMatrixUserId());
  }

  @Override
  public void setAvailability(String consultantId, boolean available) {
    if (available) {
      consultantActivityRegistry.markAvailable(consultantId);
    } else {
      consultantActivityRegistry.markUnavailable(consultantId);
    }
  }

  @Override
  public boolean getAvailability(String consultantId) {
    return consultantActivityRegistry
        .filterActive(List.of(consultantId), consultantAvailabilityActiveWindowMs)
        .contains(consultantId);
  }

  @Override
  public long countPendingEnquiriesAheadOf(
      Long agencyId, Integer consultingTypeId, Long mainTopicId, LocalDateTime beforeDate) {
    if (beforeDate == null || consultingTypeId == null) {
      return 0L;
    }
    var minUpdateDate = LocalDateTime.now().minusMinutes(liveChatQueueActivePeriodMinutes);
    return sessionRepository.countPendingEnquiriesAheadOf(
        SessionStatus.NEW,
        beforeDate,
        consultingTypeId,
        mainTopicId,
        agencyId,
        minUpdateDate,
        RegistrationType.ANONYMOUS);
  }

  @Override
  public Boolean updateE2eKeys(String chatUserId, String publicKey) {
    var allUpdated = new AtomicReference<>(true);

    messageClient
        .findAllChats(chatUserId)
        .ifPresent(
            chats -> {
              var masterKey = stringConverter.hashOf(chatUserId);
              for (var chat : chats) {
                var userId = mapper.userIdOf(chat);
                var roomId = mapper.roomIdOf(chat);
                if (mapper.e2eKeyOf(chat).isPresent()) {
                  var roomKeyId = mapper.e2eKeyOf(chat).orElseThrow();
                  var updatedE2eKey = createE2eKey(publicKey, masterKey, roomKeyId);
                  if (!messageClient.updateChatE2eKey(userId, roomId, updatedE2eKey)) {
                    allUpdated.set(false);
                    break;
                  }
                } else {
                  log.info("Ignoring non-temp chat ({}) of user ({})", roomId, userId);
                }
              }
            });

    return allUpdated.get();
  }

  private String createE2eKey(String publicKey, String masterKey, String roomKeyId) {
    var keyId = roomKeyId.substring(4, 16);
    var encryptedRoomKey = roomKeyId.substring(16);
    var roomKey = stringConverter.aesDecrypt(encryptedRoomKey, masterKey);
    var rsaEncrypted = stringConverter.rsaEncrypt(roomKey, publicKey);
    var intArray = stringConverter.int8Array(rsaEncrypted);
    var jsonStringified = stringConverter.jsonStringify(intArray);

    return keyId + stringConverter.base64AsciiEncode(jsonStringified);
  }

  @Override
  public boolean removeUserFromSession(String chatUserId, String chatId) {
    var session = sessionRepository.findByGroupId(chatId).orElseThrow();
    var consultant =
        consultantRepository.findByRocketChatIdAndDeleteDateIsNull(chatUserId).orElseThrow();
    var removedOrIgnored = new AtomicBoolean(true);

    if (!session.isAdvisedBy(consultant) && !isResponsible(session, consultant)) {
      if (isInChat(session, consultant)) {
        removedOrIgnored.set(messageClient.removeUserFromSession(chatUserId, chatId));
      }
    }

    return removedOrIgnored.get();
  }

  private boolean isResponsible(Session session, Consultant consultant) {
    return session.isTeamSession() && consultant.isInAgency(session.getAgencyId());
  }

  /**
   * Whether the consultant is currently a member of the session's chat room.
   *
   * <p>Membership comes from the session's Matrix room (the only chat backend since Rocket.Chat was
   * disabled, ADR-004). Previously this read Rocket.Chat members via {@code
   * findMembers(...).orElseThrow()}, which threw once Rocket.Chat returned nothing — breaking the
   * remove-from-session path entirely.
   *
   * <p>Fail-safe: when the room state cannot be determined we return {@code false}, so an uncertain
   * lookup never triggers the downstream removal.
   */
  boolean isInChat(Session session, Consultant consultant) {
    if (session == null || consultant == null) {
      return false;
    }
    var matrixRoomId = groupChatMembershipService.resolveMatrixRoomId(session);
    if (isNull(matrixRoomId) || isNull(consultant.getMatrixUserId())) {
      return false;
    }

    return groupChatMembershipService.resolveHumanMembers(matrixRoomId).stream()
        .map(ResolvedRoomMember::matrixUserId)
        .anyMatch(id -> id.equals(consultant.getMatrixUserId()));
  }

  @Override
  public boolean markAsDirectConsultant(Long sessionId) {
    return sessionRepository
        .findById(sessionId)
        .map(
            session -> {
              session.setIsConsultantDirectlySet(true);
              var updatedSession = sessionRepository.save(session);
              return Boolean.TRUE.equals(updatedSession.getIsConsultantDirectlySet());
            })
        .orElse(false);
  }

  @Override
  public Optional<Map<String, Object>> findSession(Long sessionId) {
    var session = sessionRepository.findById(sessionId);

    return mapper.mapOf(session);
  }

  @Override
  public boolean existsChat(long chatId) {
    return findChat(chatId).isPresent();
  }

  @Override
  public Optional<Chat> findChat(long chatId) {
    return chatRepository.findById(chatId);
  }
}
