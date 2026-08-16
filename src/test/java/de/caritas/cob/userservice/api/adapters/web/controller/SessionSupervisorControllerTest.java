package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.facade.SessionSupervisorFacade;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.SessionSupervisor;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.notification.SupervisorAddedEmailNotificationService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;

@ExtendWith(MockitoExtension.class)
class SessionSupervisorControllerTest {

  @Mock private SessionSupervisorFacade sessionSupervisorFacade;
  @Mock private AuthenticatedUser authenticatedUser;
  @Mock private UserAccountService userAccountService;
  @Mock private EventNotificationService eventNotificationService;
  @Mock private SupervisorAddedEmailNotificationService supervisorAddedEmailNotificationService;
  @Mock private SessionService sessionService;

  private SessionSupervisorController controller;

  @BeforeEach
  void setUp() {
    controller =
        new SessionSupervisorController(
            sessionSupervisorFacade,
            authenticatedUser,
            userAccountService,
            eventNotificationService,
            supervisorAddedEmailNotificationService,
            sessionService);
  }

  @Test
  void addSupervisor_happyPath_returnsCreatedAndDelegates() {
    // Business reason: adding a supervisor must persist and return created supervisor identity
    // data.
    var request = new SessionSupervisorController.AddSupervisorRequestDTO();
    request.setSupervisorConsultantId("sup-1");
    request.setNotes("observe");
    var current = consultant("current-1", "Current");
    var created = supervisor(15L, "sup-1", "added-by", "Display Name", "Full Name", user("u-1"));
    when(userAccountService.retrieveValidatedConsultant()).thenReturn(current);
    when(authenticatedUser.getAccessToken()).thenReturn("token");
    when(sessionSupervisorFacade.addSupervisor(55L, "sup-1", current, null, "observe"))
        .thenReturn(created);

    var response = controller.addSupervisor(55L, request);

    assertEquals(HttpStatus.CREATED, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals(15L, response.getBody().getId());
    assertEquals("sup-1", response.getBody().getSupervisorConsultantId());
    verify(sessionSupervisorFacade).addSupervisor(55L, "sup-1", current, null, "observe");
    verify(eventNotificationService)
        .createSupervisorAddedNotification(any(), eq("u-1"), eq("Display Name"));
    verify(eventNotificationService).createSupervisorAssignedNotification(any(), eq("sup-1"));
  }

  @Test
  void addSupervisor_consultantMissing_returnsForbidden() {
    // Business reason: only validated consultants may manage supervisors for a session.
    when(userAccountService.retrieveValidatedConsultant()).thenReturn(null);
    var request = new SessionSupervisorController.AddSupervisorRequestDTO();
    request.setSupervisorConsultantId("sup-1");

    var response = controller.addSupervisor(55L, request);

    assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    verify(sessionSupervisorFacade, never()).addSupervisor(any(), any(), any(), any(), any());
  }

  @Test
  void addSupervisor_optionalDataMissing_noThrowAndUsesFullNameFallback() {
    // Business reason: notification side effects must not crash when optional user/session data is
    // absent.
    var request = new SessionSupervisorController.AddSupervisorRequestDTO();
    request.setSupervisorConsultantId("sup-1");
    var current = consultant("current-1", "Current");
    var created =
        supervisor(
            16L,
            "sup-1",
            "added-by",
            null,
            "Fallback Full Name",
            null /* session user intentionally null */);
    when(userAccountService.retrieveValidatedConsultant()).thenReturn(current);
    when(authenticatedUser.getAccessToken()).thenReturn("token");
    when(sessionSupervisorFacade.addSupervisor(56L, "sup-1", current, null, null))
        .thenReturn(created);

    var response = controller.addSupervisor(56L, request);

    assertEquals(HttpStatus.CREATED, response.getStatusCode());
    verify(eventNotificationService, never())
        .createSupervisorAddedNotification(any(), any(), any());
    verify(supervisorAddedEmailNotificationService)
        .notifySupervisorAdded(
            eq(null), any(Consultant.class), eq("Fallback Name"), eq(77L), eq(null), eq("token"));
  }

  @Test
  void removeSupervisor_happyPath_returnsNoContentAndTriggersSideEffects() {
    // Business reason: removing a supervisor must trigger user-facing removal notification and
    // email.
    var current = consultant("current-1", "Current");
    var existing =
        supervisor(20L, "sup-2", "added-by", "Supervisor Display", "Supervisor Full", user("u-2"));
    when(userAccountService.retrieveValidatedConsultant()).thenReturn(current);
    when(authenticatedUser.getAccessToken()).thenReturn("token");
    when(sessionSupervisorFacade.getSupervisors(90L)).thenReturn(List.of(existing));
    when(sessionService.getSession(90L)).thenReturn(Optional.of(existing.getSession()));

    var response = controller.removeSupervisor(90L, 20L);

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    verify(sessionSupervisorFacade).removeSupervisor(90L, 20L, current);
    verify(eventNotificationService)
        .createSupervisorRemovedNotification(any(), eq("u-2"), eq("Supervisor Display"));
    verify(supervisorAddedEmailNotificationService)
        .notifySupervisorRemoved(
            any(User.class),
            any(Consultant.class),
            eq("Supervisor Display"),
            eq(77L),
            eq(null),
            eq("token"));
  }

  @Test
  void removeSupervisor_nonExistingSupervisor_returnsNoContentWithoutEmail() {
    // Business reason: removing an already-absent supervisor should be idempotent and not send
    // ghost emails.
    var current = consultant("current-1", "Current");
    when(userAccountService.retrieveValidatedConsultant()).thenReturn(current);
    when(authenticatedUser.getAccessToken()).thenReturn("token");
    when(sessionSupervisorFacade.getSupervisors(91L)).thenReturn(List.of());
    when(sessionService.getSession(91L)).thenReturn(Optional.empty());

    var response = controller.removeSupervisor(91L, 999L);

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    verify(sessionSupervisorFacade).removeSupervisor(91L, 999L, current);
    verify(supervisorAddedEmailNotificationService, never())
        .notifySupervisorRemoved(any(), any(), any(), any(), any(), any());
  }

  @Test
  void getSupervisors_happyPath_mapsAllFields() {
    // Business reason: supervisor list response must preserve all DTO fields for frontend
    // rendering.
    var first = supervisor(30L, "sup-3", "added-by-3", "Display Three", "Full Three", user("u-3"));
    when(sessionSupervisorFacade.getSupervisors(77L)).thenReturn(List.of(first));

    var response = controller.getSupervisors(77L);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(1, response.getBody().size());
    var dto = response.getBody().get(0);
    assertEquals(30L, dto.getId());
    assertEquals(77L, dto.getSessionId());
    assertEquals("sup-3", dto.getSupervisorConsultantId());
    assertEquals("Display Three", dto.getSupervisorUsername());
    assertEquals("added-by-3", dto.getAddedByConsultantId());
    assertEquals("room-77", dto.getMatrixRoomId());
    assertEquals("note-30", dto.getNotes());
  }

  @Test
  void getSupervisors_emptyList_returnsEmptyArrayNotNull() {
    // Business reason: frontend expects stable empty arrays instead of null for list endpoints.
    when(sessionSupervisorFacade.getSupervisors(78L)).thenReturn(List.of());

    var response = controller.getSupervisors(78L);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals(0, response.getBody().size());
  }

  @Test
  void setSupervisionOptedOut_delegates_when_callerOwnsSession() {
    // Business reason: only the session's own ratsuchende may toggle their supervision opt-out.
    var owner = user("owner-1");
    var session = sessionOwnedBy(70L, owner);
    var request = new SessionSupervisorController.SupervisionOptOutDTO();
    request.setOptedOut(true);
    when(userAccountService.retrieveValidatedUser()).thenReturn(owner);
    when(sessionService.getSession(70L)).thenReturn(Optional.of(session));

    var response = controller.setSupervisionOptedOut(70L, request);

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    verify(sessionSupervisorFacade).setSupervisionOptedOut(70L, true);
  }

  @Test
  void setSupervisionOptedOut_returnsForbidden_when_callerIsNotSessionOwner() {
    var caller = user("intruder-1");
    var session = sessionOwnedBy(71L, user("owner-1"));
    var request = new SessionSupervisorController.SupervisionOptOutDTO();
    request.setOptedOut(true);
    when(userAccountService.retrieveValidatedUser()).thenReturn(caller);
    when(sessionService.getSession(71L)).thenReturn(Optional.of(session));

    var response = controller.setSupervisionOptedOut(71L, request);

    assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    verify(sessionSupervisorFacade, never())
        .setSupervisionOptedOut(any(), org.mockito.ArgumentMatchers.anyBoolean());
  }

  @Test
  void setTeamAccess_mapsAllowedToTheBackwardCompatibleOptOutState() {
    var owner = user("owner-1");
    var session = sessionOwnedBy(72L, owner);
    var request = new SessionSupervisorController.TeamAccessDTO();
    request.setAllowed(false);
    when(userAccountService.retrieveValidatedUser()).thenReturn(owner);
    when(sessionService.getSession(72L)).thenReturn(Optional.of(session));

    var response = controller.setTeamAccess(72L, request);

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    verify(sessionSupervisorFacade).setSupervisionOptedOut(72L, true);
  }

  @Test
  void setTeamAccess_returnsForbidden_whenCallerDoesNotOwnSession() {
    var caller = user("intruder-1");
    var request = new SessionSupervisorController.TeamAccessDTO();
    request.setAllowed(true);
    when(userAccountService.retrieveValidatedUser()).thenReturn(caller);
    when(sessionService.getSession(73L))
        .thenReturn(Optional.of(sessionOwnedBy(73L, user("owner-1"))));

    var response = controller.setTeamAccess(73L, request);

    assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    verify(sessionSupervisorFacade, never())
        .setSupervisionOptedOut(any(), org.mockito.ArgumentMatchers.anyBoolean());
  }

  @Test
  void controllerMethods_doNotDeclarePreAuthorizeAnnotation() throws Exception {
    // Business reason: test explicitly documents current security annotation state for future
    // hardening.
    Method add =
        SessionSupervisorController.class.getMethod(
            "addSupervisor", Long.class, SessionSupervisorController.AddSupervisorRequestDTO.class);
    Method remove =
        SessionSupervisorController.class.getMethod("removeSupervisor", Long.class, Long.class);
    assertNull(add.getAnnotation(PreAuthorize.class));
    assertNull(remove.getAnnotation(PreAuthorize.class));
  }

  private SessionSupervisor supervisor(
      Long id,
      String supervisorId,
      String addedById,
      String displayName,
      String fullName,
      User user) {
    var session =
        Session.builder()
            .id(77L)
            .user(user)
            .consultingTypeId(1)
            .registrationType(Session.RegistrationType.REGISTERED)
            .postcode("12345")
            .status(Session.SessionStatus.IN_PROGRESS)
            .teamSession(false)
            .matrixRoomId("room-77")
            .build();
    var supervisor = consultant(supervisorId, displayName != null ? displayName : fullName);
    supervisor.setDisplayName(displayName);
    supervisor.setFirstName("Fallback");
    supervisor.setLastName("Name");
    var addedBy = consultant(addedById, "Adder");
    return SessionSupervisor.builder()
        .id(id)
        .session(session)
        .supervisorConsultant(supervisor)
        .addedByConsultant(addedBy)
        .addedDate(LocalDateTime.now())
        .isActive(true)
        .matrixRoomId("room-77")
        .notes("note-" + id)
        .build();
  }

  private Session sessionOwnedBy(Long id, User owner) {
    return Session.builder()
        .id(id)
        .user(owner)
        .consultingTypeId(1)
        .registrationType(Session.RegistrationType.REGISTERED)
        .postcode("12345")
        .status(Session.SessionStatus.IN_PROGRESS)
        .teamSession(false)
        .build();
  }

  private Consultant consultant(String id, String username) {
    return Consultant.builder()
        .id(id)
        .matrixUserId("rc-" + id)
        .username(username)
        .firstName("First")
        .lastName("Last")
        .email(id + "@example.org")
        .build();
  }

  private User user(String userId) {
    return User.builder()
        .userId(userId)
        .username("user-" + userId)
        .email(userId + "@example.org")
        .languageFormal(false)
        .build();
  }
}
