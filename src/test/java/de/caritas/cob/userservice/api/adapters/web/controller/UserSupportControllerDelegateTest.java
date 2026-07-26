package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.NewMessageNotificationDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ReassignmentNotificationDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionDataDTO;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.facade.EmailNotificationFacade;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.ConsultantImportService;
import de.caritas.cob.userservice.api.service.SessionDataService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.tenant.TenantData;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class UserSupportControllerDelegateTest {

  private static final String RC_GROUP_ID = "rc-group-id";
  private static final String USER_ID = "user-id";
  private static final String CONSULTANT_ID = "consultant-id";
  private static final String ASKER_ID = "asker-id";
  private static final UUID CONSULTANT_UUID =
      UUID.fromString("11111111-1111-1111-1111-111111111111");

  @Mock private SessionService sessionService;
  @Mock private AuthenticatedUser authenticatedUser;
  @Mock private ConsultantImportService consultantImportService;
  @Mock private EmailNotificationFacade emailNotificationFacade;
  @Mock private SessionDataService sessionDataService;
  @Mock private EventNotificationService eventNotificationService;

  @InjectMocks private UserSupportControllerDelegate delegate;

  @Test
  void importConsultantsShouldStartImportAndReturnOk() {
    var response = delegate.importConsultants();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(consultantImportService).startImport();
  }

  @Test
  void sendNewMessageNotificationShouldSendEmailAndCreateEvent() {
    var roles = Set.of(UserRole.USER.getValue());
    when(authenticatedUser.getRoles()).thenReturn(roles);
    when(authenticatedUser.getUserId()).thenReturn(USER_ID);

    var response = delegate.sendNewMessageNotification(new NewMessageNotificationDTO(RC_GROUP_ID));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(emailNotificationFacade).sendNewMessageNotification(RC_GROUP_ID, roles, USER_ID, null);
    verify(eventNotificationService)
        .createMessageNotificationFromRoom(RC_GROUP_ID, USER_ID, null, false);
  }

  @Test
  void sendReassignmentNotificationShouldSendConfirmationWhenConfirmed() {
    var reassignmentNotification =
        new ReassignmentNotificationDTO(RC_GROUP_ID, CONSULTANT_UUID)
            .fromConsultantName("Consultant")
            .isConfirmed(true);

    var response = delegate.sendReassignmentNotification(reassignmentNotification);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(emailNotificationFacade)
        .sendReassignConfirmationNotification(reassignmentNotification, null);
    verify(emailNotificationFacade, never())
        .sendReassignRequestNotification(any(), any(TenantData.class));
  }

  @Test
  void sendReassignmentNotificationShouldSendRequestWhenNotConfirmed() {
    var reassignmentNotification =
        new ReassignmentNotificationDTO(RC_GROUP_ID, CONSULTANT_UUID).isConfirmed(false);

    var response = delegate.sendReassignmentNotification(reassignmentNotification);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(emailNotificationFacade).sendReassignRequestNotification(RC_GROUP_ID, null);
    verify(emailNotificationFacade, never())
        .sendReassignConfirmationNotification(any(), any(TenantData.class));
  }

  @Test
  void sendReassignmentNotificationShouldSendRequestWhenConfirmationIsNull() {
    var reassignmentNotification = new ReassignmentNotificationDTO(RC_GROUP_ID, CONSULTANT_UUID);

    var response = delegate.sendReassignmentNotification(reassignmentNotification);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(emailNotificationFacade).sendReassignRequestNotification(RC_GROUP_ID, null);
    verify(emailNotificationFacade, never())
        .sendReassignConfirmationNotification(any(), any(TenantData.class));
  }

  @Test
  void updateSessionDataShouldSaveDataAndReturnOk() {
    var sessionData = new SessionDataDTO().age("17").state("8");

    var response = delegate.updateSessionData(1L, sessionData);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(sessionDataService).saveSessionData(1L, sessionData);
  }

  @Test
  void getRocketChatGroupIdShouldReturnGroupId() {
    when(sessionService.findGroupIdByConsultantAndUser(CONSULTANT_ID, ASKER_ID))
        .thenReturn(RC_GROUP_ID);

    var response = delegate.getRocketChatGroupId(CONSULTANT_ID, ASKER_ID);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getGroupId()).isEqualTo(RC_GROUP_ID);
  }
}
