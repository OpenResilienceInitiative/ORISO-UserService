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
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateAdminConsultantDTO;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.ConsultantTopicAgencyCompatibilityValidator;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.UserAccountInputValidator;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.IdentityProfileUpdate;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.ConsultantPublicSlugService;
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

  @Mock private ConsultantPublicSlugService consultantPublicSlugService;

  @Mock private UserAccountInputValidator userAccountInputValidator;

  @Mock private AppointmentService appointmentService;

  @Mock private MatrixSynapseService matrixSynapseService;

  @Mock private SessionRepository sessionRepository;

  @Mock private EventNotificationService eventNotificationService;

  @Mock
  private ConsultantTopicAgencyCompatibilityValidator consultantTopicAgencyCompatibilityValidator;

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

  // --- Supervision (auto-assigned): standing supervisor assignment (grill 2026-07-13) ---

  @Test
  public void updateConsultant_Should_setStandingSupervisor_When_targetIsASupervisor() {
    // The agency admin points a counsellor at their standing supervisor; from then on every case
    // that counsellor accepts auto-attaches this colleague.
    Consultant consultant = consultantWithId("counsellor-1");
    Consultant standingSupervisor = consultantWithId("supervisor-1");
    standingSupervisor.setSupervisor(true);
    when(this.consultantService.getConsultant("counsellor-1")).thenReturn(Optional.of(consultant));
    when(this.consultantService.getConsultant("supervisor-1"))
        .thenReturn(Optional.of(standingSupervisor));
    UpdateAdminConsultantDTO updateConsultant = updateDtoFor(consultant);
    updateConsultant.setAssignedSupervisorId("supervisor-1");

    this.consultantUpdateService.updateConsultant("counsellor-1", updateConsultant);

    ArgumentCaptor<Consultant> saved = ArgumentCaptor.forClass(Consultant.class);
    verify(this.consultantService).saveConsultant(saved.capture());
    assertEquals("supervisor-1", saved.getValue().getAssignedSupervisorId());
  }

  @Test
  public void updateConsultant_Should_throwBadRequest_When_standingSupervisorIsNotASupervisor() {
    // is_supervisor is the capability gate: you cannot make an arbitrary colleague someone's
    // standing supervisor.
    Consultant consultant = consultantWithId("counsellor-1");
    Consultant notASupervisor = consultantWithId("colleague-1");
    notASupervisor.setSupervisor(false);
    when(this.consultantService.getConsultant("counsellor-1")).thenReturn(Optional.of(consultant));
    when(this.consultantService.getConsultant("colleague-1"))
        .thenReturn(Optional.of(notASupervisor));
    UpdateAdminConsultantDTO updateConsultant = updateDtoFor(consultant);
    updateConsultant.setAssignedSupervisorId("colleague-1");

    assertThrows(
        BadRequestException.class,
        () -> this.consultantUpdateService.updateConsultant("counsellor-1", updateConsultant));
    verify(this.consultantService, Mockito.never()).saveConsultant(any());
  }

  @Test
  public void updateConsultant_Should_throwBadRequest_When_assigningSelfAsStandingSupervisor() {
    // Supervision is oversight BY A COLLEAGUE; supervising yourself is meaningless and would let a
    // counsellor silently self-approve their own oversight.
    Consultant consultant = consultantWithId("counsellor-1");
    consultant.setSupervisor(true);
    when(this.consultantService.getConsultant("counsellor-1")).thenReturn(Optional.of(consultant));
    UpdateAdminConsultantDTO updateConsultant = updateDtoFor(consultant);
    updateConsultant.setAssignedSupervisorId("counsellor-1");

    assertThrows(
        BadRequestException.class,
        () -> this.consultantUpdateService.updateConsultant("counsellor-1", updateConsultant));
    verify(this.consultantService, Mockito.never()).saveConsultant(any());
  }

  @Test
  public void updateConsultant_Should_clearStandingSupervisor_When_assignedSupervisorIdIsBlank() {
    // Clearing the standing assignment stops future auto-attachment; it does not detach the
    // supervisors already on in-flight cases.
    Consultant consultant = consultantWithId("counsellor-1");
    consultant.setAssignedSupervisorId("supervisor-1");
    when(this.consultantService.getConsultant("counsellor-1")).thenReturn(Optional.of(consultant));
    UpdateAdminConsultantDTO updateConsultant = updateDtoFor(consultant);
    updateConsultant.setAssignedSupervisorId("");

    this.consultantUpdateService.updateConsultant("counsellor-1", updateConsultant);

    ArgumentCaptor<Consultant> saved = ArgumentCaptor.forClass(Consultant.class);
    verify(this.consultantService).saveConsultant(saved.capture());
    assertEquals(null, saved.getValue().getAssignedSupervisorId());
  }

  private Consultant consultantWithId(String id) {
    Consultant consultant = new EasyRandom().nextObject(Consultant.class);
    consultant.setId(id);
    consultant.setTenantId(1L);
    consultant.setAssignedSupervisorId(null);
    return consultant;
  }

  private UpdateAdminConsultantDTO updateDtoFor(Consultant consultant) {
    UpdateAdminConsultantDTO dto = new EasyRandom().nextObject(UpdateAdminConsultantDTO.class);
    dto.setIsGroupchatConsultant(null);
    dto.setAssignedSupervisorId(null);
    keepDisplayNameUnchanged(consultant, dto);
    return dto;
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
        .ensureRoles(
            consultant.getId(), java.util.Set.of(UserRole.GROUP_CHAT_CONSULTANT.getValue()));
    verify(this.keycloakService, Mockito.never())
        .removeRoleIfPresent(consultant.getId(), UserRole.GROUP_CHAT_CONSULTANT.getValue());

    ArgumentCaptor<IdentityProfileUpdate> profileCaptor =
        ArgumentCaptor.forClass(IdentityProfileUpdate.class);
    verify(this.keycloakService, times(1))
        .updateProfile(eq(consultant.getId()), profileCaptor.capture());
    assertEquals(profileCaptor.getValue().tenantId(), consultant.getTenantId());
    assertEquals(profileCaptor.getValue().firstName(), updateConsultant.getFirstname());
    assertEquals(profileCaptor.getValue().lastName(), updateConsultant.getLastname());
    verify(this.consultantService, times(1)).saveConsultant(any());
    verify(this.appointmentService, times(1)).syncConsultantData(any());
  }

  @Test
  public void
      updateConsultant_Should_skipIdentityAndAppointmentSync_When_selfServiceOnlyRequestsPublicSlug() {
    Consultant consultant = new EasyRandom().nextObject(Consultant.class);
    consultant.setTenantId(1L);
    consultant.setFirstName("Direct");
    consultant.setLastName("Consultant");
    consultant.setEmail("dev_direct_consultant_local@example.test");
    consultant.setAbsent(false);
    when(this.consultantService.getConsultant(any())).thenReturn(Optional.of(consultant));
    when(this.consultantService.saveConsultant(any())).thenReturn(consultant);

    UpdateAdminConsultantDTO updateConsultant =
        new UpdateAdminConsultantDTO()
            .firstname("Direct")
            .lastname("Consultant")
            .email("dev_direct_consultant_local@example.test")
            .absent(false)
            .formalLanguage(false)
            .languages(List.of("de"))
            .topicIds(List.of())
            .publicSlug("nikunnj-rohit");

    this.consultantUpdateService.updateConsultant(consultant.getId(), updateConsultant, false);

    verify(this.keycloakService, Mockito.never()).updateProfile(any(), any());
    verify(this.keycloakService, Mockito.never()).ensureRoles(any(), any());
    verify(this.keycloakService, Mockito.never()).removeRoleIfPresent(any(), any());
    verify(this.appointmentService, Mockito.never()).syncConsultantData(any());
    verify(this.consultantPublicSlugService).requestSlug(consultant, "nikunnj-rohit");
    verify(this.consultantService, times(1)).saveConsultant(any());
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
        .ensureRoles(
            consultant.getId(), java.util.Set.of(UserRole.GROUP_CHAT_CONSULTANT.getValue()));

    verify(this.keycloakService, times(1))
        .updateProfile(eq(consultant.getId()), any(IdentityProfileUpdate.class));
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
        .updateProfile(eq(consultant.getId()), any(IdentityProfileUpdate.class));
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
        .updateProfile(anyString(), any(IdentityProfileUpdate.class));
    verify(this.consultantService, Mockito.never()).saveConsultant(any());
    verify(this.appointmentService, Mockito.never()).syncConsultantData(any());
  }

  private void keepDisplayNameUnchanged(
      Consultant consultant, UpdateAdminConsultantDTO updateConsultant) {
    updateConsultant.setFirstname(consultant.getFirstName());
    updateConsultant.setLastname(consultant.getLastName());
  }
}
