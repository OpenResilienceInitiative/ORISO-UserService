package de.caritas.cob.userservice.api.facade.assignsession;

import de.caritas.cob.userservice.api.facade.EmailNotificationFacade;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.SessionAssignmentChatGateway;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.statistics.StatisticsService;
import de.caritas.cob.userservice.api.service.statistics.event.AssignSessionStatisticsEvent;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.statisticsservice.generated.web.model.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

/**
 * Facade to encapsulate the steps for accepting an enquiry and/or assigning a session to a
 * consultant.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AssignSessionFacade {

  private final @NonNull SessionService sessionService;
  private final @NonNull SessionAssignmentChatGateway sessionAssignmentChatGateway;
  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull EmailNotificationFacade emailNotificationFacade;
  private final @NonNull SessionToConsultantVerifier sessionToConsultantVerifier;
  private final @NonNull UnauthorizedMembersProvider unauthorizedMembersProvider;
  private final @NonNull StatisticsService statisticsService;
  private final @NonNull HttpServletRequest httpServletRequest;

  /**
   * Assigns the given {@link Session} session to the given {@link Consultant}. Removes consultants
   * from the Matrix room when they no longer have the right to view this session.
   *
   * <p>If the statistics function is enabled, the assignment of the session is processed as a
   * statistical event.
   */
  public void assignSession(
      Session session, Consultant consultantToAssign, Consultant authConsultant) {
    var consultantSessionDTO =
        ConsultantSessionDTO.builder().consultant(consultantToAssign).session(session).build();
    sessionToConsultantVerifier.verifyPreconditionsForAssignment(consultantSessionDTO);

    sessionAssignmentChatGateway.prepareAssignment(session, consultantToAssign);
    updateSessionInDatabase(session, consultantToAssign);
    removeUnauthorizedMembersFromGroups(session, consultantToAssign, authConsultant);
    if (!authenticatedUser.isAdviceSeeker()) {
      sendEmailForConsultantChange(session, consultantToAssign);
    }

    var event =
        new AssignSessionStatisticsEvent(
            consultantToAssign.getId(), UserRole.CONSULTANT, session.getId());
    event.setRequestUri(httpServletRequest.getRequestURI());
    event.setRequestReferer(httpServletRequest.getHeader(HttpHeaders.REFERER));
    event.setRequestUserId(authenticatedUser.getUserId());
    statisticsService.fireEvent(event);
  }

  private void updateSessionInDatabase(Session session, Consultant consultant) {
    var initialStatus = session.getStatus();
    sessionService.updateConsultantAndStatusForSession(
        session,
        consultant,
        initialStatus == SessionStatus.NEW ? SessionStatus.IN_PROGRESS : initialStatus);
  }

  private void removeUnauthorizedMembersFromGroups(
      Session session, Consultant consultant, Consultant consultantToKeep) {
    var memberIds = sessionAssignmentChatGateway.findMemberIds(session.getMatrixRoomId());
    removeUnauthorizedMembersFromGroup(session, consultant, memberIds, consultantToKeep);
  }

  private void removeUnauthorizedMembersFromGroup(
      Session session, Consultant consultant, List<String> memberIds, Consultant consultantToKeep) {
    var consultantsToRemove =
        unauthorizedMembersProvider.obtainConsultantsToRemove(
            session.getMatrixRoomId(), session, consultant, memberIds, consultantToKeep);

    sessionAssignmentChatGateway.removeConsultants(session, consultant, consultantsToRemove);
  }

  private void sendEmailForConsultantChange(Session session, Consultant consultant) {
    if (!authenticatedUser.getUserId().equals(consultant.getId())) {
      emailNotificationFacade.sendAssignEnquiryEmailNotification(
          consultant,
          authenticatedUser.getUserId(),
          session.getUser().getUsername(),
          TenantContext.getCurrentTenantData());
    }
  }
}
