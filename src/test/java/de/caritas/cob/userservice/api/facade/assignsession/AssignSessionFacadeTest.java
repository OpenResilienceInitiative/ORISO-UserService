package de.caritas.cob.userservice.api.facade.assignsession;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.facade.EmailNotificationFacade;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionAssignmentChatGateway;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.statistics.StatisticsService;
import de.caritas.cob.userservice.api.service.statistics.event.AssignSessionStatisticsEvent;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AssignSessionFacadeTest {

  private static final String ROOM_ID = "!session:matrix.example";

  @InjectMocks AssignSessionFacade assignSessionFacade;
  @Mock SessionAssignmentChatGateway sessionAssignmentChatGateway;
  @Mock SessionService sessionService;
  @Mock EmailNotificationFacade emailNotificationFacade;
  @Mock AuthenticatedUser authenticatedUser;
  @Mock SessionToConsultantVerifier sessionToConsultantVerifier;
  @Mock UnauthorizedMembersProvider unauthorizedMembersProvider;
  @Mock StatisticsService statisticsService;
  @Mock HttpServletRequest httpServletRequest;
  @Mock Session session;
  @Mock User user;
  @Mock Consultant consultantToAssign;
  @Mock Consultant authenticatedConsultant;
  @Mock Consultant consultantToRemove;

  @BeforeEach
  void setUp() {
    when(session.getId()).thenReturn(42L);
    when(session.getStatus()).thenReturn(SessionStatus.IN_PROGRESS);
    when(session.getMatrixRoomId()).thenReturn(ROOM_ID);
    when(session.getUser()).thenReturn(user);
    when(user.getUsername()).thenReturn("advice-seeker");
    when(consultantToAssign.getId()).thenReturn("new-consultant");
    when(authenticatedUser.getUserId()).thenReturn("assigning-consultant");
    when(sessionAssignmentChatGateway.findMemberIds(ROOM_ID))
        .thenReturn(List.of("@old:matrix.example", "@new:matrix.example"));
    when(unauthorizedMembersProvider.obtainConsultantsToRemove(
            ROOM_ID,
            session,
            consultantToAssign,
            List.of("@old:matrix.example", "@new:matrix.example"),
            authenticatedConsultant))
        .thenReturn(List.of(consultantToRemove));
  }

  @Test
  void assignSessionPreparesMatrixMembershipBeforePersistingTheAssignment() {
    assignSessionFacade.assignSession(session, consultantToAssign, authenticatedConsultant);

    var order = inOrder(sessionAssignmentChatGateway, sessionService);
    order.verify(sessionAssignmentChatGateway).prepareAssignment(session, consultantToAssign);
    order
        .verify(sessionService)
        .updateConsultantAndStatusForSession(
            session, consultantToAssign, SessionStatus.IN_PROGRESS);
  }

  @Test
  void assignSessionRemovesUnauthorizedMatrixConsultants() {
    assignSessionFacade.assignSession(session, consultantToAssign, authenticatedConsultant);

    verify(sessionAssignmentChatGateway).findMemberIds(ROOM_ID);
    verify(sessionAssignmentChatGateway)
        .removeConsultants(session, consultantToAssign, List.of(consultantToRemove));
  }

  @Test
  void assignSessionSendsTheConsultantChangeEmail() {
    assignSessionFacade.assignSession(session, consultantToAssign, authenticatedConsultant);

    verify(emailNotificationFacade)
        .sendAssignEnquiryEmailNotification(
            consultantToAssign, "assigning-consultant", "advice-seeker", null);
  }

  @Test
  void assignSessionFiresTheStatisticsEvent() {
    assignSessionFacade.assignSession(session, consultantToAssign, authenticatedConsultant);

    verify(statisticsService)
        .fireEvent(org.mockito.ArgumentMatchers.any(AssignSessionStatisticsEvent.class));
  }
}
