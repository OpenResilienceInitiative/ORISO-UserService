package de.caritas.cob.userservice.api.admin.service.consultant.update;

import static org.junit.Assert.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakService;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatService;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateAdminConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.ConsultantTopicAgencyCompatibilityValidator;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.UserAccountInputValidator;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import java.util.List;
import java.util.Optional;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class ConsultantUpdateServiceTest {

  @InjectMocks private ConsultantUpdateService consultantUpdateService;

  @Mock private KeycloakService keycloakService;

  @Mock private ConsultantService consultantService;

  @Mock private UserAccountInputValidator userAccountInputValidator;

  @Mock private RocketChatService rocketChatService;

  @Mock private AppointmentService appointmentService;

  @Mock private MatrixSynapseService matrixSynapseService;

  @Mock private SessionRepository sessionRepository;

  @Mock private EventNotificationService eventNotificationService;

  @Mock private ConsultantTopicAgencyCompatibilityValidator consultantTopicAgencyCompatibilityValidator;

  @Test
  public void
      updateConsultant_Should_throwBadRequestException_When_givenConsultantIdDoesNotExist() {
    assertThrows(
        BadRequestException.class,
        () -> {
          when(this.consultantService.getConsultant(any())).thenReturn(Optional.empty());

          this.consultantUpdateService.updateConsultant("", mock(UpdateAdminConsultantDTO.class));
        });
  }

  @Test
  public void updateConsultant_Should_callServicesCorrectly_When_givenConsultantDataIsValid() {
    Consultant consultant = new EasyRandom().nextObject(Consultant.class);
    consultant.setTenantId(1L);
    when(this.consultantService.getConsultant(any())).thenReturn(Optional.of(consultant));
    UpdateAdminConsultantDTO updateConsultant =
        new EasyRandom().nextObject(UpdateAdminConsultantDTO.class);
    updateConsultant.setIsGroupchatConsultant(null);
    keepDisplayNameUnchanged(consultant, updateConsultant);

    this.consultantUpdateService.updateConsultant("", updateConsultant);

    verify(this.keycloakService, Mockito.never())
        .updateRole(consultant.getId(), UserRole.GROUP_CHAT_CONSULTANT.getValue());

    ArgumentCaptor<UserDTO> userDTOArgumentCaptor = ArgumentCaptor.forClass(UserDTO.class);
    verify(this.keycloakService, times(1))
        .updateUserData(
            eq(consultant.getId()),
            userDTOArgumentCaptor.capture(),
            eq(updateConsultant.getFirstname()),
            eq(updateConsultant.getLastname()));
    assertEquals(userDTOArgumentCaptor.getValue().getTenantId(), consultant.getTenantId());
    verify(this.consultantService, times(1)).saveConsultant(any());
    verify(this.appointmentService, times(1)).syncConsultantData(any());
  }

  @Test
  public void
      updateConsultant_Should_callServicesCorrectly_And_AddGroupChatConsultantRole_When_givenConsultantDataIsValidAndGroupChatFlagIsGiven() {
    Consultant consultant = new EasyRandom().nextObject(Consultant.class);
    when(this.consultantService.getConsultant(any())).thenReturn(Optional.of(consultant));
    UpdateAdminConsultantDTO updateConsultant =
        new EasyRandom().nextObject(UpdateAdminConsultantDTO.class);
    updateConsultant.setIsGroupchatConsultant(true);
    keepDisplayNameUnchanged(consultant, updateConsultant);

    this.consultantUpdateService.updateConsultant("", updateConsultant);

    verify(this.keycloakService)
        .updateRole(consultant.getId(), UserRole.GROUP_CHAT_CONSULTANT.getValue());

    verify(this.keycloakService, times(1))
        .updateUserData(
            eq(consultant.getId()),
            any(UserDTO.class),
            eq(updateConsultant.getFirstname()),
            eq(updateConsultant.getLastname()));
    verify(this.consultantService, times(1)).saveConsultant(any());
    verify(this.appointmentService, times(1)).syncConsultantData(any());
  }

  @Test
  public void
      updateConsultant_Should_callServicesCorrectly_And_RemoveGroupChatConsultantRole_When_givenConsultantDataIsValidAndGroupChatFlagIsGiven() {
    Consultant consultant = new EasyRandom().nextObject(Consultant.class);
    when(this.consultantService.getConsultant(any())).thenReturn(Optional.of(consultant));
    UpdateAdminConsultantDTO updateConsultant =
        new EasyRandom().nextObject(UpdateAdminConsultantDTO.class);
    updateConsultant.setIsGroupchatConsultant(false);
    keepDisplayNameUnchanged(consultant, updateConsultant);

    this.consultantUpdateService.updateConsultant("", updateConsultant);

    verify(this.keycloakService)
        .removeRoleIfPresent(consultant.getId(), UserRole.GROUP_CHAT_CONSULTANT.getValue());

    verify(this.keycloakService, times(1))
        .updateUserData(
            eq(consultant.getId()),
            any(UserDTO.class),
            eq(updateConsultant.getFirstname()),
            eq(updateConsultant.getLastname()));
    verify(this.consultantService, times(1)).saveConsultant(any());
    verify(this.appointmentService, times(1)).syncConsultantData(any());
  }

  @Test
  public void
      updateConsultant_Should_stopBeforeIdentityAndDatabaseUpdates_When_topicAgencyValidationFails() {
    Consultant consultant = new EasyRandom().nextObject(Consultant.class);
    consultant.setTenantId(1L);
    when(this.consultantService.getConsultant(any())).thenReturn(Optional.of(consultant));
    UpdateAdminConsultantDTO updateConsultant =
        new EasyRandom().nextObject(UpdateAdminConsultantDTO.class);
    updateConsultant.setTopicIds(List.of(99L));
    keepDisplayNameUnchanged(consultant, updateConsultant);
    doThrow(new BadRequestException("topic not covered"))
        .when(consultantTopicAgencyCompatibilityValidator)
        .validateTopicUpdateAgainstAssignedAgencies(
            eq(consultant.getId()), eq(List.of(99L)), eq(consultant.getTenantId()));

    assertThrows(
        BadRequestException.class,
        () -> this.consultantUpdateService.updateConsultant("", updateConsultant));

    verify(this.keycloakService, Mockito.never())
        .updateUserData(anyString(), any(UserDTO.class), anyString(), anyString());
    verify(this.consultantService, Mockito.never()).saveConsultant(any());
    verify(this.appointmentService, Mockito.never()).syncConsultantData(any());
  }

  private void keepDisplayNameUnchanged(
      Consultant consultant, UpdateAdminConsultantDTO updateConsultant) {
    updateConsultant.setFirstname(consultant.getFirstName());
    updateConsultant.setLastname(consultant.getLastName());
  }
}
