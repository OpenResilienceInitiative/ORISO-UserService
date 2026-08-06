package de.caritas.cob.userservice.api.service.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.NotificationRoomLevel;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.TeamDiscussion;
import de.caritas.cob.userservice.api.model.TeamDiscussionParticipant;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.NotificationRoomLevelRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionParticipantRepository;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionRepository;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fan-out for Team-Besprechung posts (US#473, decided 2026-07-18).
 *
 * <p><b>Hybrid rule:</b> the first post in a discussion notifies the whole eligible circle (every
 * consultant of the enquiry's agency — "the team is discussing this enquiry"); every later post
 * notifies only participants (who opened or wrote) plus explicitly mentioned colleagues.
 *
 * <p><b>Level respect:</b> each recipient's per-conversation notification level (server mirror in
 * {@code notification_room_level}) is honoured — MUTED and active snoozes drop the notification,
 * MENTIONS only lets mentions through. Active-view suppression applies on top: whoever has the
 * discussion open gets nothing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamDiscussionNotificationService {

  static final String EVENT_TYPE_TEAM_DISCUSSION_NEW = "team.discussion.new";

  private final @NonNull TeamDiscussionRepository teamDiscussionRepository;
  private final @NonNull TeamDiscussionParticipantRepository participantRepository;
  private final @NonNull NotificationRoomLevelRepository notificationRoomLevelRepository;
  private final @NonNull ConsultantAgencyRepository consultantAgencyRepository;
  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull EventNotificationService eventNotificationService;
  private final ObjectMapper paramsObjectMapper = new ObjectMapper();

  /**
   * Called from the frontend's post-send producer ({@code POST
   * /users/event-notifications/message-events} with {@code teamDiscussion=true}). The sender
   * becomes a participant; recipients follow the hybrid rule.
   */
  @Transactional
  public void createTeamDiscussionNotification(
      String roomId, String senderUserId, String senderDisplayName, List<String> mentionedUserIds) {
    if (roomId == null || roomId.isBlank() || senderUserId == null) {
      return;
    }
    Optional<TeamDiscussion> discussionOpt = teamDiscussionRepository.findByMatrixRoomId(roomId);
    if (discussionOpt.isEmpty()) {
      return;
    }
    TeamDiscussion discussion = discussionOpt.get();
    if (discussion.getStatus() == TeamDiscussion.Status.ARCHIVED) {
      return;
    }
    Optional<Session> sessionOpt = sessionRepository.findById(discussion.getSessionId());
    if (sessionOpt.isEmpty() || sessionOpt.get().getAgencyId() == null) {
      return;
    }
    Session session = sessionOpt.get();
    if (session.getConsultant() != null) {
      // Create/accept race guard: once the case is accepted, an OPEN row must not keep
      // notifying — the facade lazily archives it on next access.
      return;
    }

    Set<String> eligible = eligibleConsultantIds(session.getAgencyId());
    if (!eligible.contains(senderUserId)) {
      // Sender-eligibility guard: the endpoint is reachable for askers too. A caller outside
      // the eligible circle must neither trigger a broadcast nor flip firstNotified.
      log.warn(
          "Ignoring team discussion notification from ineligible sender {} for room {}",
          senderUserId,
          roomId);
      return;
    }
    Set<String> mentioned = sanitizedMentions(mentionedUserIds, eligible);

    Set<String> recipients;
    if (!discussion.isFirstNotified()) {
      recipients = new HashSet<>(eligible);
      discussion.setFirstNotified(true);
      teamDiscussionRepository.save(discussion);
    } else {
      recipients =
          new HashSet<>(
              participantRepository.findByTeamDiscussionId(discussion.getId()).stream()
                  .map(TeamDiscussionParticipant::getConsultantId)
                  .filter(eligible::contains)
                  .toList());
      recipients.addAll(mentioned);
    }
    recipients.remove(senderUserId);

    recordSenderAsParticipant(discussion, senderUserId, eligible);

    for (String recipientId : recipients) {
      if (dropForConversationLevel(recipientId, roomId, mentioned.contains(recipientId))) {
        continue;
      }
      if (eventNotificationService.isNotificationSuppressed(recipientId, roomId)) {
        continue;
      }
      eventNotificationService.createEvent(
          recipientId,
          EVENT_TYPE_TEAM_DISCUSSION_NEW,
          EventNotificationService.CATEGORY_MESSAGE,
          "Team discussion",
          buildText(senderDisplayName),
          buildParams(session, discussion, senderDisplayName, mentioned.contains(recipientId)),
          buildActionPath(session, discussion),
          session.getId(),
          session.getTenantId());
    }
  }

  /** Upserts the server-side mirror of the per-conversation level for one user and room. */
  @Transactional
  public void updateConversationLevel(
      String userId, String roomId, NotificationRoomLevel.Level level, LocalDateTime snoozedUntil) {
    NotificationRoomLevel entry =
        notificationRoomLevelRepository
            .findByUserIdAndRoomId(userId, roomId)
            .orElseGet(() -> NotificationRoomLevel.builder().userId(userId).roomId(roomId).build());
    entry.setLevel(level != null ? level : NotificationRoomLevel.Level.ALL);
    entry.setSnoozedUntil(snoozedUntil);
    entry.setUpdateDate(LocalDateTime.now());
    notificationRoomLevelRepository.save(entry);
  }

  private Set<String> eligibleConsultantIds(Long agencyId) {
    List<ConsultantAgency> consultantAgencies =
        consultantAgencyRepository.findByAgencyIdAndDeleteDateIsNull(agencyId);
    Set<String> ids = new HashSet<>();
    for (ConsultantAgency consultantAgency : consultantAgencies) {
      if (consultantAgency.getConsultant() != null
          && consultantAgency.getConsultant().getId() != null) {
        ids.add(consultantAgency.getConsultant().getId());
      }
    }
    return ids;
  }

  private Set<String> sanitizedMentions(List<String> mentionedUserIds, Set<String> eligible) {
    Set<String> mentioned = new HashSet<>();
    if (mentionedUserIds != null) {
      for (String userId : mentionedUserIds) {
        if (userId != null && eligible.contains(userId)) {
          mentioned.add(userId);
        }
      }
    }
    return mentioned;
  }

  private void recordSenderAsParticipant(
      TeamDiscussion discussion, String senderUserId, Set<String> eligible) {
    if (!eligible.contains(senderUserId)) {
      return;
    }
    if (participantRepository.existsByTeamDiscussionIdAndConsultantId(
        discussion.getId(), senderUserId)) {
      return;
    }
    participantRepository.save(
        TeamDiscussionParticipant.builder()
            .teamDiscussionId(discussion.getId())
            .consultantId(senderUserId)
            .joinDate(LocalDateTime.now())
            .build());
  }

  /** True when the recipient's per-conversation level says: drop this notification. */
  private boolean dropForConversationLevel(String recipientId, String roomId, boolean isMentioned) {
    Optional<NotificationRoomLevel> levelOpt =
        notificationRoomLevelRepository.findByUserIdAndRoomId(recipientId, roomId);
    if (levelOpt.isEmpty()) {
      return false;
    }
    NotificationRoomLevel entry = levelOpt.get();
    if (entry.getSnoozedUntil() != null && entry.getSnoozedUntil().isAfter(LocalDateTime.now())) {
      return true;
    }
    return switch (entry.getLevel()) {
      case MUTED -> true;
      case MENTIONS -> !isMentioned;
      case ALL -> false;
    };
  }

  private String buildText(String senderDisplayName) {
    return senderDisplayName != null && !senderDisplayName.isBlank()
        ? senderDisplayName + " posted in the team discussion"
        : "New post in the team discussion";
  }

  private String buildParams(
      Session session, TeamDiscussion discussion, String senderDisplayName, boolean mentioned) {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("sessionId", session.getId());
    params.put("roomId", discussion.getMatrixRoomId());
    params.put("senderDisplayName", senderDisplayName != null ? senderDisplayName : "");
    params.put("mentioned", mentioned);
    try {
      return paramsObjectMapper.writeValueAsString(params);
    } catch (JsonProcessingException ex) {
      log.warn("Could not serialize team discussion params: {}", ex.getMessage());
      return null;
    }
  }

  private String buildActionPath(Session session, TeamDiscussion discussion) {
    return "/sessions/consultant/sessionPreview?sessionId="
        + session.getId()
        + "&teamDiscussion=1&roomId="
        + discussion.getMatrixRoomId();
  }
}
