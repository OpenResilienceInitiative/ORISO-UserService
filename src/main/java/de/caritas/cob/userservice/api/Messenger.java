package de.caritas.cob.userservice.api;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static java.util.Objects.isNull;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.config.observability.LiveChatDiagnosticMetrics;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.in.Messaging;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.availability.ConsultantActivityRegistry;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService.ResolvedRoomMember;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class Messenger implements Messaging {

  private final UserRepository userRepository;
  private final ConsultantRepository consultantRepository;
  private final ChatRepository chatRepository;
  private final SessionRepository sessionRepository;
  private final UserServiceMapper mapper;
  private final GroupChatMembershipService groupChatMembershipService;
  private final MatrixSynapseService matrixSynapseService;
  private final ConsultantActivityRegistry consultantActivityRegistry;
  private final LiveChatDiagnosticMetrics diagnosticMetrics;

  /**
   * How long a live-chat queue entry stays visible without a sign of life from the guest who is
   * waiting behind it (ORISO-Frontend#1404).
   *
   * <p>Deliberately its own property rather than the deactivate-workflow period it used to borrow:
   * that one also deactivates the Keycloak account behind a session and covers IN_PROGRESS chats,
   * so shortening it would end running conversations after a few quiet minutes. This one only
   * decides what the queue shows.
   */
  @Value("${live.chat.queue.activePeriodMinutes:5}")
  private long liveChatQueueActivePeriodMinutes;

  /**
   * Don't rewrite {@code updateDate} on every single poll. The waiting room polls every 4s; one
   * write per guest per this many seconds is enough to keep an entry alive within a window measured
   * in minutes, and keeps the queue from turning into a write loop.
   */
  @Value("${live.chat.queue.heartbeatThrottleSeconds:30}")
  private long liveChatQueueHeartbeatThrottleSeconds;

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
   * ban power in the room). A Matrix ban both removes the user and blocks re-join.
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
      diagnosticMetrics.recordInvalidQueueRequest();
      return 0L;
    }
    /* Sessions store their dates in UTC (CustomLocalDateTime.nowInUtc), so the cutoff has to be
    computed in UTC too. With the old six-hour window a server running in a non-UTC zone still
    produced roughly the right answer; at five minutes the offset would empty the queue. */
    var minUpdateDate = nowInUtc().minusMinutes(liveChatQueueActivePeriodMinutes);
    var queueDepth =
        sessionRepository.countPendingEnquiriesAheadOf(
            SessionStatus.NEW,
            beforeDate,
            consultingTypeId,
            mainTopicId,
            agencyId,
            minUpdateDate,
            RegistrationType.ANONYMOUS);
    diagnosticMetrics.recordQueueDepth(queueDepth);
    return queueDepth;
  }

  /**
   * Mark a waiting live-chat enquiry as still wanted by its guest.
   *
   * <p>A queue entry is only as trustworthy as the last sign of life behind it. Closing the tab,
   * reloading, or walking away leaves the enquiry in NEW forever — the client cannot reliably say
   * goodbye, which is why {@code anonymousChatSessionCleanup} has no unload hook. So the queue asks
   * the opposite question: who is still here? The waiting room polls the enquiry details every 4s,
   * and that poll lands here; anything that stops polling for {@code
   * live.chat.queue.activePeriodMinutes} drops out of the count and out of the consultant's list.
   *
   * <p>Only ever touches an unassigned NEW session, so it cannot disturb a chat a consultant has
   * already taken. Callers must have established that the caller owns the session.
   */
  @Override
  public void touchLiveChatQueueHeartbeat(Long sessionId) {
    if (sessionId == null) {
      return;
    }
    var now = nowInUtc();
    sessionRepository.touchLiveChatQueueHeartbeat(
        sessionId, SessionStatus.NEW, now, now.minusSeconds(liveChatQueueHeartbeatThrottleSeconds));
  }

  @Override
  public boolean removeConsultantFromSession(Long sessionId, String consultantId) {
    var session = sessionRepository.findById(sessionId).orElseThrow();
    var consultant = consultantRepository.findByIdAndDeleteDateIsNull(consultantId).orElseThrow();

    if (!session.isAdvisedBy(consultant) && !isResponsible(session, consultant)) {
      if (isInChat(session, consultant)) {
        var matrixRoomId = groupChatMembershipService.resolveMatrixRoomId(session);
        groupChatMembershipService.removeMemberFromRoom(matrixRoomId, consultant.getMatrixUserId());
      }
    }

    return true;
  }

  private boolean isResponsible(Session session, Consultant consultant) {
    return session.isTeamSession() && consultant.isInAgency(session.getAgencyId());
  }

  /**
   * Whether the consultant is currently a member of the session's chat room.
   *
   * <p>Membership comes from the session's Matrix room.
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
