package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.caritas.cob.userservice.api.adapters.web.dto.ReassignmentNotificationDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionDataDTO;
import de.caritas.cob.userservice.api.facade.EmailNotificationFacade;
import de.caritas.cob.userservice.api.service.ConsultantImportService;
import de.caritas.cob.userservice.api.service.SessionDataService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.tenant.TenantData;
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
  private static final String CONSULTANT_ID = "consultant-id";
  private static final String ASKER_ID = "asker-id";
  private static final UUID CONSULTANT_UUID =
      UUID.fromString("11111111-1111-1111-1111-111111111111");

  @Mock private SessionService sessionService;
  @Mock private ConsultantImportService consultantImportService;
  @Mock private EmailNotificationFacade emailNotificationFacade;
  @Mock private SessionDataService sessionDataService;

  @InjectMocks private UserSupportControllerDelegate delegate;

  @Test
  void importConsultantsShouldStartImportAndReturnOk() {
    var response = delegate.importConsultants();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(consultantImportService).startImport();
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
}
