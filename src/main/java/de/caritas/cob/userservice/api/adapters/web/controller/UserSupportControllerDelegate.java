package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.apache.commons.lang3.BooleanUtils.isTrue;

import de.caritas.cob.userservice.api.adapters.web.dto.ReassignmentNotificationDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionDataDTO;
import de.caritas.cob.userservice.api.facade.EmailNotificationFacade;
import de.caritas.cob.userservice.api.service.ConsultantImportService;
import de.caritas.cob.userservice.api.service.SessionDataService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class UserSupportControllerDelegate {

  private final @NonNull SessionService sessionService;
  private final @NonNull ConsultantImportService consultantImportService;
  private final @NonNull EmailNotificationFacade emailNotificationFacade;
  private final @NonNull SessionDataService sessionDataService;

  @org.springframework.beans.factory.annotation.Autowired
  private org.springframework.core.env.Environment importEnvironment;

  @org.springframework.beans.factory.annotation.Autowired
  private org.springframework.beans.factory.ObjectProvider<de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker> importBrokers;

  @org.springframework.beans.factory.annotation.Autowired
  private jakarta.servlet.http.HttpServletRequest importRequest;

  ResponseEntity<Void> importConsultants() {
    if (importEnvironment != null && "FIVE_ACTORS".equals(importEnvironment.getProperty("TASK_IDENTITY_MODEL", "LEGACY"))) {
      if (!(importRequest.getUserPrincipal() instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken authentication)
          || !authentication.isAuthenticated() || !"POST".equals(importRequest.getMethod())
          || !(importRequest.getContextPath() + "/users/consultants/import").equals(importRequest.getRequestURI())
          || !java.util.Objects.equals(importRequest.getHeader("Authorization"), "Bearer " + authentication.getToken().getTokenValue()))
        throw new org.springframework.security.access.AccessDeniedException("Configured import trigger is unavailable");
      final de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker.VerifiedConsultantImportTrigger verified;
      try { verified = importBrokers.getObject().verifyConsultantImportTrigger(authentication.getToken().getTokenValue()); }
      catch (RuntimeException unavailable) { throw new org.springframework.security.access.AccessDeniedException("Configured import trigger is unavailable"); }
      try (verified) { consultantImportService.startImport(verified); }
      return new ResponseEntity<>(HttpStatus.OK);
    }
    consultantImportService.startImport();
    return new ResponseEntity<>(HttpStatus.OK);
  }

  ResponseEntity<Void> sendReassignmentNotification(
      ReassignmentNotificationDTO reassignmentNotificationDTO) {
    if (isTrue(reassignmentNotificationDTO.getIsConfirmed())) {
      emailNotificationFacade.sendReassignConfirmationNotification(
          reassignmentNotificationDTO, TenantContext.getCurrentTenantData());
    } else {
      emailNotificationFacade.sendReassignRequestNotification(
          reassignmentNotificationDTO.getMatrixRoomId(), TenantContext.getCurrentTenantData());
    }

    return new ResponseEntity<>(HttpStatus.OK);
  }

  ResponseEntity<Void> updateSessionData(Long sessionId, SessionDataDTO sessionDataDTO) {
    sessionDataService.saveSessionData(sessionId, sessionDataDTO);
    return new ResponseEntity<>(HttpStatus.OK);
  }
}
