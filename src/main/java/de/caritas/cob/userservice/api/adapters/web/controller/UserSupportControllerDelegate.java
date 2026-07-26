package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.apache.commons.lang3.BooleanUtils.isTrue;

import de.caritas.cob.userservice.api.adapters.web.dto.NewMessageNotificationDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ReassignmentNotificationDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.RocketChatGroupIdDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionDataDTO;
import de.caritas.cob.userservice.api.facade.EmailNotificationFacade;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.ConsultantImportService;
import de.caritas.cob.userservice.api.service.SessionDataService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
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
  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull ConsultantImportService consultantImportService;
  private final @NonNull EmailNotificationFacade emailNotificationFacade;
  private final @NonNull SessionDataService sessionDataService;
  private final @NonNull EventNotificationService eventNotificationService;

  ResponseEntity<Void> importConsultants() {
    consultantImportService.startImport();
    return new ResponseEntity<>(HttpStatus.OK);
  }

  ResponseEntity<Void> sendNewMessageNotification(
      NewMessageNotificationDTO newMessageNotificationDTO) {
    emailNotificationFacade.sendNewMessageNotification(
        newMessageNotificationDTO.getRcGroupId(),
        authenticatedUser.getRoles(),
        authenticatedUser.getUserId(),
        TenantContext.getCurrentTenantData());
    eventNotificationService.createMessageNotificationFromRoom(
        newMessageNotificationDTO.getRcGroupId(), authenticatedUser.getUserId(), null, false);

    return new ResponseEntity<>(HttpStatus.OK);
  }

  ResponseEntity<Void> sendReassignmentNotification(
      ReassignmentNotificationDTO reassignmentNotificationDTO) {
    if (isTrue(reassignmentNotificationDTO.getIsConfirmed())) {
      emailNotificationFacade.sendReassignConfirmationNotification(
          reassignmentNotificationDTO, TenantContext.getCurrentTenantData());
    } else {
      emailNotificationFacade.sendReassignRequestNotification(
          reassignmentNotificationDTO.getRcGroupId(), TenantContext.getCurrentTenantData());
    }

    return new ResponseEntity<>(HttpStatus.OK);
  }

  ResponseEntity<Void> updateSessionData(Long sessionId, SessionDataDTO sessionDataDTO) {
    sessionDataService.saveSessionData(sessionId, sessionDataDTO);
    return new ResponseEntity<>(HttpStatus.OK);
  }

  ResponseEntity<RocketChatGroupIdDTO> getRocketChatGroupId(String consultantId, String askerId) {
    String groupId = sessionService.findGroupIdByConsultantAndUser(consultantId, askerId);
    return new ResponseEntity<>(new RocketChatGroupIdDTO().groupId(groupId), HttpStatus.OK);
  }
}
