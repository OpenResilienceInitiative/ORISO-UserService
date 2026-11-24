package de.caritas.cob.userservice.api.conversation.service;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.ConsultantAgencyService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.consultingtype.TopicConsultantRoutingService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import java.util.List;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Service to create anonymous user conversations (sessions). */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnonymousConversationCreatorService {

  private final @NonNull SessionService sessionService;
  private final @NonNull AgencyService agencyService;
  private final @NonNull ConsultantAgencyService consultantAgencyService;
  private final @NonNull EventNotificationService eventNotificationService;
  private final @NonNull TopicConsultantRoutingService topicConsultantRoutingService;

  /**
   * Creates the waiting session of a live-chat guest. Its Matrix room is provisioned by the
   * assignment workflow. Called by the registration entry only, which undoes the guest account when
   * this fails — there is no rollback of its own here.
   *
   * @param userDTO the guest registration
   * @param user the guest account the registration entry just created
   * @return {@link Session}
   */
  public Session createAnonymousConversation(UserDTO userDTO, User user) {
    List<ConsultantAgency> consultantAgencies;
    Session session;

    try {
      session =
          sessionService.initializeSession(
              user, userDTO, false, RegistrationType.ANONYMOUS, SessionStatus.NEW);
      session.setConversationType(ConversationType.LIVE_CHAT);
      consultantAgencies = obtainConsultants(session);
      sessionService.saveSession(session);
    } catch (Exception ex) {
      throw new InternalServerErrorException("Could not create the live-chat session", ex);
    }

    persistWaitingRoomNotifications(session, consultantAgencies);

    return session;
  }

  private List<ConsultantAgency> obtainConsultants(Session session) {
    if (session.getMainTopicId() != null) {
      List<String> topicConsultantIds =
          topicConsultantRoutingService.findEligibleConsultantIds(session.getMainTopicId());
      if (!topicConsultantIds.isEmpty()) {
        return consultantAgencyService.getConsultantAgenciesByConsultantIds(topicConsultantIds);
      }
    }

    return obtainConsultantsByConsultingType(session.getConsultingTypeId());
  }

  private List<ConsultantAgency> obtainConsultantsByConsultingType(int consultingTypeId) {
    List<Long> agencyList =
        agencyService.getAgenciesByConsultingType(consultingTypeId).stream()
            .map(AgencyDTO::getId)
            .collect(Collectors.toList());

    return consultantAgencyService.getConsultantsOfAgencies(agencyList);
  }

  private void persistWaitingRoomNotifications(
      Session session, List<ConsultantAgency> consultantAgencies) {
    List<String> consultantIds =
        session.getMainTopicId() != null
            ? topicConsultantRoutingService.findEligibleConsultantIds(session.getMainTopicId())
            : consultantAgencies.stream()
                .map(agency -> agency.getConsultant().getId())
                .collect(Collectors.toList());

    if (consultantIds.isEmpty() && !consultantAgencies.isEmpty()) {
      consultantIds =
          consultantAgencies.stream()
              .map(agency -> agency.getConsultant().getId())
              .collect(Collectors.toList());
    }

    // WP-06 Slice 3: persist a `waiting_room.client.joined` timeline card for the eligible
    // consultants. Best-effort — must never break anonymous-conversation creation.
    try {
      eventNotificationService.createWaitingRoomClientJoinedNotifications(session, consultantIds);
    } catch (Exception ex) {
      log.warn("Could not persist waiting-room notifications for session {}", session.getId(), ex);
    }
  }
}
