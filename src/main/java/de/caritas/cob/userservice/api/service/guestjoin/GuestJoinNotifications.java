package de.caritas.cob.userservice.api.service.guestjoin;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.ConsultantAgencyService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.consultingtype.TopicConsultantRoutingService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Existing waiting-room notification semantics, invoked only after successful local completion. */
@Service
@RequiredArgsConstructor
public class GuestJoinNotifications {
  private final SessionRepository sessions;
  private final TopicConsultantRoutingService routing;
  private final AgencyService agencies;
  private final ConsultantAgencyService consultantAgencies;
  private final EventNotificationService events;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void joined(Long sessionId) {
    var session = sessions.findById(sessionId).orElseThrow();
    List<String> recipients =
        session.getMainTopicId() == null
            ? List.of()
            : routing.findEligibleConsultantIds(session.getMainTopicId());
    if (recipients.isEmpty()) {
      var agencyIds =
          agencies.getAgenciesByConsultingType(session.getConsultingTypeId()).stream()
              .map(AgencyDTO::getId)
              .toList();
      recipients =
          consultantAgencies.getConsultantsOfAgencies(agencyIds).stream()
              .map(relation -> relation.getConsultant().getId())
              .distinct()
              .toList();
    }
    events.createWaitingRoomClientJoinedNotifications(session, recipients);
  }
}
