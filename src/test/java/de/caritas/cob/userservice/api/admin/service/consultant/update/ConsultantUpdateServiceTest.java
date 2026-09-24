package de.caritas.cob.userservice.api.admin.service.consultant.update;

import static org.assertj.core.api.Assertions.assertThat;
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
import de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver;
import de.caritas.cob.userservice.api.helper.MatrixRealNameGuard;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.IdentityProfileUpdate;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.ConsultantPublicSlugService;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
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

  // The real rule, not a mock: ConsultantDisplayNameResolver is the single place that decides
  // which name may reach Matrix (ADR-002 §2).
  @Spy
  private ConsultantDisplayNameResolver consultantDisplayNameResolver =
      new ConsultantDisplayNameResolver();

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
    // EasyRandom gives each consultant a random tenant; the standing assignment is tenant-scoped.
    consultant.setTenantId(1L);
    standingSupervisor.setTenantId(1L);
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
    consultant.setTenantId(1L);
    notASupervisor.setTenantId(1L);
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
  public void updateConsultant_Should_throwBadRequest_When_standingSupervisorIsFromAnotherTenant() {
    // A platform admin sees consultants across tenants, so nothing else here stops a cross-tenant
    // assignment. Storing one is worse than rejecting it: the attach is best-effort and swallows
    // its failure, so the case would run unsupervised while the admin board shows a supervisor.
    Consultant consultant = consultantWithId("counsellor-1");
    consultant.setTenantId(1L);
    Consultant foreignSupervisor = consultantWithId("supervisor-2");
    foreignSupervisor.setSupervisor(true);
    foreignSupervisor.setTenantId(2L);
    when(this.consultantService.getConsultant("counsellor-1")).thenReturn(Optional.of(consultant));
    when(this.consultantService.getConsultant("supervisor-2"))
        .thenReturn(Optional.of(foreignSupervisor));
    UpdateAdminConsultantDTO updateConsultant = updateDtoFor(consultant);
    updateConsultant.setAssignedSupervisorId("supervisor-2");

    assertThrows(
        BadRequestException.class,
        () -> this.consultantUpdateService.updateConsultant("counsellor-1", updateConsultant));
    verify(this.consultantService, Mockito.never()).saveConsultant(any());
  }

  @Test
  public void updateConsultant_Should_setStandingSupervisor_When_bothTenantIdsAreNull() {
    // Single-tenant deployments leave tenant_id null on every consultant. The cross-tenant guard
    // uses Objects.equals precisely so null == null still passes; a guard written with != would
    // block every standing-supervisor assignment on those installations.
    Consultant consultant = consultantWithId("counsellor-1");
    consultant.setTenantId(null);
    Consultant standingSupervisor = consultantWithId("supervisor-1");
    standingSupervisor.setSupervisor(true);
    standingSupervisor.setTenantId(null);
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
    // EasyRandom fills random topics; these fixtures model a consultant without any.
    consultant.setConsultantTopics(new HashSet<>());
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
    // EasyRandom fills random topics; these fixtures model a consultant without any.
    consultant.setConsultantTopics(new HashSet<>());
    consultant.setTenantId(1L);
    when(this.consultantService.getConsultant(any())).thenReturn(Optional.of(consultant));
    UpdateAdminConsultantDTO updateConsultant =
        new EasyRandom().nextObject(UpdateAdminConsultantDTO.class);
    updateConsultant.setIsGroupchatConsultant(null);
    keepDisplayNameUnchanged(consultant, updateConsultant);

    this.consultantUpdateService.updateConsultant("", updateConsultant);

    verify(this.keycloakService, Mockito.never())
        .updateRole(consultant.getId(), UserRole.GROUP_CHAT_CONSULTANT.getValue());
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
    // EasyRandom fills random topics; these fixtures model a consultant without any.
    consultant.setConsultantTopics(new HashSet<>());
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
    verify(this.keycloakService, Mockito.never()).updateRole(any(), any(String.class));
    verify(this.keycloakService, Mockito.never()).removeRoleIfPresent(any(), any());
    verify(this.appointmentService, Mockito.never()).syncConsultantData(any());
    verify(this.consultantPublicSlugService).requestSlug(consultant, "nikunnj-rohit");
    verify(this.consultantService, times(1)).saveConsultant(any());
  }

  @Test
  public void
      updateConsultant_Should_callServicesCorrectly_And_AddGroupChatConsultantRole_When_givenConsultantDataIsValidAndGroupChatFlagIsGiven() {
    Consultant consultant = new EasyRandom().nextObject(Consultant.class);
    // EasyRandom fills random topics; these fixtures model a consultant without any.
    consultant.setConsultantTopics(new HashSet<>());
    when(this.consultantService.getConsultant(any())).thenReturn(Optional.of(consultant));
    UpdateAdminConsultantDTO updateConsultant =
        new EasyRandom().nextObject(UpdateAdminConsultantDTO.class);
    updateConsultant.setIsGroupchatConsultant(true);
    keepDisplayNameUnchanged(consultant, updateConsultant);

    this.consultantUpdateService.updateConsultant("", updateConsultant);

    verify(this.keycloakService)
        .updateRole(consultant.getId(), UserRole.GROUP_CHAT_CONSULTANT.getValue());

    verify(this.keycloakService, times(1))
        .updateProfile(eq(consultant.getId()), any(IdentityProfileUpdate.class));
    verify(this.consultantService, times(1)).saveConsultant(any());
    verify(this.appointmentService, times(1)).syncConsultantData(any());
  }

  @Test
  public void
      updateConsultant_Should_callServicesCorrectly_And_RemoveGroupChatConsultantRole_When_givenConsultantDataIsValidAndGroupChatFlagIsGiven() {
    Consultant consultant = new EasyRandom().nextObject(Consultant.class);
    // EasyRandom fills random topics; these fixtures model a consultant without any.
    consultant.setConsultantTopics(new HashSet<>());
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
    // EasyRandom fills random topics; these fixtures model a consultant without any.
    consultant.setConsultantTopics(new HashSet<>());
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

  @Test
  public void updateConsultant_Should_refuseToRemoveTheLastTopic_When_theConsultantHasTopics() {
    Consultant consultant = new EasyRandom().nextObject(Consultant.class);
    consultant.setConsultantTopics(new HashSet<>());
    consultant.setTenantId(1L);
    consultant.replaceTopics(List.of(5L));
    when(this.consultantService.getConsultant(any())).thenReturn(Optional.of(consultant));
    UpdateAdminConsultantDTO updateConsultant =
        new EasyRandom().nextObject(UpdateAdminConsultantDTO.class);
    updateConsultant.setTopicIds(List.of());
    keepDisplayNameUnchanged(consultant, updateConsultant);

    assertThrows(
        BadRequestException.class,
        () -> this.consultantUpdateService.updateConsultant("", updateConsultant));

    verify(this.consultantService, Mockito.never()).saveConsultant(any());
  }

  // ---------------------------------------------------------------------------
  // ADR-002 §2 / #1200: renaming a counsellor must not push the real name to Matrix.
  // ---------------------------------------------------------------------------

  @Test
  public void updateConsultant_Should_pushThePublicDisplayNameToMatrix_When_theRealNameChanges() {
    Consultant consultant = matrixEnabledConsultant("Frau M.");
    when(this.consultantService.getConsultant("counsellor-1")).thenReturn(Optional.of(consultant));
    when(this.consultantService.saveConsultant(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    UpdateAdminConsultantDTO rename = renameTo("Angela", "Musterfrau");

    this.consultantUpdateService.updateConsultant("counsellor-1", rename);

    ArgumentCaptor<String> displayName = ArgumentCaptor.forClass(String.class);
    verify(this.matrixSynapseService)
        .updateUserDisplayName(eq("@beraterin1:matrix.oriso.org"), displayName.capture());
    MatrixRealNameGuard.assertNoRealNameReachedMatrix(
        this.matrixSynapseService, "Angela", "Musterfrau");
    assertThat(displayName.getValue()).isEqualTo("Frau M.");
  }

  @Test
  public void updateConsultant_Should_fallBackToTheUsername_When_noPublicDisplayNameIsSet() {
    Consultant consultant = matrixEnabledConsultant(null);
    when(this.consultantService.getConsultant("counsellor-1")).thenReturn(Optional.of(consultant));
    when(this.consultantService.saveConsultant(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    UpdateAdminConsultantDTO rename = renameTo("Angela", "Musterfrau");

    this.consultantUpdateService.updateConsultant("counsellor-1", rename);

    ArgumentCaptor<String> displayName = ArgumentCaptor.forClass(String.class);
    verify(this.matrixSynapseService)
        .updateUserDisplayName(eq("@beraterin1:matrix.oriso.org"), displayName.capture());
    MatrixRealNameGuard.assertNoRealNameReachedMatrix(
        this.matrixSynapseService, "Angela", "Musterfrau");
    assertThat(displayName.getValue()).isEqualTo("beraterin1");
  }

  @Test
  public void updateConsultant_Should_notFail_When_theMatrixDisplayNameUpdateFails() {
    Consultant consultant = matrixEnabledConsultant("Frau M.");
    when(this.consultantService.getConsultant("counsellor-1")).thenReturn(Optional.of(consultant));
    when(this.consultantService.saveConsultant(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    doThrow(new RuntimeException("synapse down"))
        .when(this.matrixSynapseService)
        .updateUserDisplayName(anyString(), anyString());
    UpdateAdminConsultantDTO rename = renameTo("Angela", "Musterfrau");

    Consultant updated = this.consultantUpdateService.updateConsultant("counsellor-1", rename);

    assertThat(updated).isNotNull();
    assertThat(updated.getFirstName()).isEqualTo("Angela");
    verify(this.consultantService).saveConsultant(any());
  }

  @Test
  public void updateConsultant_Should_pushTheNewPseudonym_When_onlyTheDisplayNameChanges() {
    // Two behaviours this PR relies on, both invisible when the real name changes as well:
    // (1) a pseudonym-only edit must still reach Matrix, even though no identity field moved, and
    // (2) the push happens AFTER the database write, so it carries the NEW pseudonym rather than
    //     the stale one. Moving the call back before the write, or gating it on identity changes
    //     only, each turn this test red.
    Consultant consultant = matrixEnabledConsultant("Frau Alt.");
    when(this.consultantService.getConsultant("counsellor-1")).thenReturn(Optional.of(consultant));
    when(this.consultantService.saveConsultant(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    UpdateAdminConsultantDTO renameOfPseudonymOnly = renameTo("Old", "Name");
    renameOfPseudonymOnly.setDisplayName("Frau Neu.");
    renameOfPseudonymOnly.setAbsent(consultant.isAbsent());

    this.consultantUpdateService.updateConsultant("counsellor-1", renameOfPseudonymOnly);

    ArgumentCaptor<String> displayName = ArgumentCaptor.forClass(String.class);
    verify(this.matrixSynapseService)
        .updateUserDisplayName(eq("@beraterin1:matrix.oriso.org"), displayName.capture());
    assertThat(displayName.getValue()).isEqualTo("Frau Neu.");
    // No identity field moved, so nothing else may be pushed on the identity side.
    verify(this.keycloakService, Mockito.never())
        .updateProfile(anyString(), any(IdentityProfileUpdate.class));
  }

  // ---------------------------------------------------------------------------
  // ADR-002 §2 / #1201: the counselor.renamed timeline entry is pushed to the ADVICE SEEKER,
  // so it must carry no real name at all -- neither the old one nor the new one. And what the
  // advice seeker is told about is the name THEY see, so the trigger is a change of the published
  // pseudonym, not of the counsellor's real name.
  // ---------------------------------------------------------------------------

  @Test
  public void
      updateConsultant_Should_NotifyTheAdviceSeekerWithoutAnyName_When_ThePseudonymChanges() {
    Consultant consultant = matrixEnabledConsultant("Frau Alt.");
    when(this.consultantService.getConsultant("counsellor-1")).thenReturn(Optional.of(consultant));
    when(this.consultantService.saveConsultant(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    Session openCase = sessionOfAdviceSeeker("asker-1");
    when(this.sessionRepository.findByConsultantAndStatusIn(eq(consultant), any()))
        .thenReturn(List.of(openCase));
    UpdateAdminConsultantDTO rename = renameTo("Angela", "Musterfrau");
    rename.setDisplayName("Frau Neu.");

    this.consultantUpdateService.updateConsultant("counsellor-1", rename);

    verify(this.eventNotificationService)
        .createCounselorRenamedNotification(any(Session.class), eq("asker-1"));
    assertNoRealNameReachedTheAdviceSeeker("Angela", "Musterfrau");
    assertNoRealNameReachedTheAdviceSeeker("Frau", "Alt");
  }

  @Test
  public void updateConsultant_Should_NotNotifyTheAdviceSeeker_When_OnlyTheRealNameChanges() {
    // The advice seeker never saw the real name, so a real-name edit changes nothing for them.
    // Notifying here would announce that something they cannot see has moved -- and, before this
    // fix, would have spelled both real names out to explain it.
    Consultant consultant = matrixEnabledConsultant("Frau M.");
    when(this.consultantService.getConsultant("counsellor-1")).thenReturn(Optional.of(consultant));
    when(this.consultantService.saveConsultant(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    // An open case with an advice seeker in it, so a notification would have somewhere to go:
    // without this the assertion below would hold for the wrong reason.
    Session openCase = sessionOfAdviceSeeker("asker-1");
    Mockito.lenient()
        .when(this.sessionRepository.findByConsultantAndStatusIn(eq(consultant), any()))
        .thenReturn(List.of(openCase));
    UpdateAdminConsultantDTO rename = renameTo("Angela", "Musterfrau");

    this.consultantUpdateService.updateConsultant("counsellor-1", rename);

    Mockito.verifyNoInteractions(this.eventNotificationService);
  }

  @Test
  public void
      updateConsultant_Should_NotNotifyTheAdviceSeeker_When_NoPseudonymIsSetAndOnlyTheRealNameChanges() {
    // With no display name stored, the published name is the username -- which does not move when
    // the real name does, so there is still nothing to announce. Asserting only "no real name
    // reached the advice seeker" here would be vacuous: nothing reaches them at all. The absence
    // of the notification is the claim, so that is what is asserted.
    Consultant consultant = matrixEnabledConsultant(null);
    when(this.consultantService.getConsultant("counsellor-1")).thenReturn(Optional.of(consultant));
    when(this.consultantService.saveConsultant(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    Session openCase = sessionOfAdviceSeeker("asker-1");
    Mockito.lenient()
        .when(this.sessionRepository.findByConsultantAndStatusIn(eq(consultant), any()))
        .thenReturn(List.of(openCase));
    UpdateAdminConsultantDTO rename = renameTo("Angela", "Musterfrau");

    this.consultantUpdateService.updateConsultant("counsellor-1", rename);

    Mockito.verifyNoInteractions(this.eventNotificationService);
  }

  @Test
  public void
      updateConsultant_Should_NotNameTheCounsellor_When_ThePseudonymIsClearedToTheUsername() {
    // The no-pseudonym case where a notification IS emitted: clearing the display name moves the
    // published name from "Frau Alt." to the username, so the advice seeker is told -- and the
    // guard below then has real invocations to inspect rather than an empty log.
    Consultant consultant = matrixEnabledConsultant("Frau Alt.");
    when(this.consultantService.getConsultant("counsellor-1")).thenReturn(Optional.of(consultant));
    when(this.consultantService.saveConsultant(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    Session openCase = sessionOfAdviceSeeker("asker-1");
    when(this.sessionRepository.findByConsultantAndStatusIn(eq(consultant), any()))
        .thenReturn(List.of(openCase));
    UpdateAdminConsultantDTO clearPseudonym = renameTo("Angela", "Musterfrau");
    clearPseudonym.setDisplayName("");

    this.consultantUpdateService.updateConsultant("counsellor-1", clearPseudonym);

    verify(this.eventNotificationService)
        .createCounselorRenamedNotification(any(Session.class), eq("asker-1"));
    assertNoRealNameReachedTheAdviceSeeker("Angela", "Musterfrau");
    assertNoRealNameReachedTheAdviceSeeker("Frau", "Alt");
  }

  @Test
  public void updateConsultant_Should_NotifyTheAdviceSeeker_When_ONLY_ThePseudonymChanges() {
    // The gate, pinned on its own. Every other rename test here also moves the real name, which
    // sets identityDataChanged -- so re-gating the emission on that flag would pass them all. This
    // one touches nothing but the display name, and identityDataChanged never looks at it.
    Consultant consultant = matrixEnabledConsultant("Frau Alt.");
    when(this.consultantService.getConsultant("counsellor-1")).thenReturn(Optional.of(consultant));
    when(this.consultantService.saveConsultant(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    Session openCase = sessionOfAdviceSeeker("asker-1");
    when(this.sessionRepository.findByConsultantAndStatusIn(eq(consultant), any()))
        .thenReturn(List.of(openCase));
    // Same first name, last name and e-mail as the stored consultant: no identity field moves.
    UpdateAdminConsultantDTO pseudonymOnly = renameTo("Old", "Name");
    pseudonymOnly.setDisplayName("Frau Neu.");
    pseudonymOnly.setAbsent(consultant.isAbsent());

    this.consultantUpdateService.updateConsultant("counsellor-1", pseudonymOnly);

    verify(this.eventNotificationService)
        .createCounselorRenamedNotification(any(Session.class), eq("asker-1"));
    verify(this.keycloakService, Mockito.never())
        .updateProfile(anyString(), any(IdentityProfileUpdate.class));
  }

  /**
   * Lenient on purpose: two of the tests below stub an open case precisely so that "no
   * notification" cannot pass for the wrong reason, and in those the repository is never reached.
   */
  private Session sessionOfAdviceSeeker(String userId) {
    User adviceSeeker = Mockito.mock(User.class);
    Mockito.lenient().when(adviceSeeker.getUserId()).thenReturn(userId);
    Session session = Mockito.mock(Session.class);
    Mockito.lenient().when(session.getUser()).thenReturn(adviceSeeker);
    return session;
  }

  /**
   * Asserts on the whole invocation log of the notification service rather than on one expected
   * argument, so a NEW advice-seeker notification added next to this one cannot reintroduce the
   * leak unnoticed.
   */
  private void assertNoRealNameReachedTheAdviceSeeker(String firstName, String lastName) {
    for (var invocation : Mockito.mockingDetails(this.eventNotificationService).getInvocations()) {
      for (Object argument : invocation.getArguments()) {
        String rendered = String.valueOf(argument);
        assertThat(rendered).doesNotContain(firstName);
        assertThat(rendered).doesNotContain(lastName);
      }
    }
  }

  /** A counsellor whose Matrix account exists; username is the ADR-002 fallback source. */
  private Consultant matrixEnabledConsultant(String publicDisplayName) {
    Consultant consultant = consultantWithId("counsellor-1");
    consultant.setUsername("beraterin1");
    consultant.setDisplayName(publicDisplayName);
    consultant.setInternalDisplayName(null);
    consultant.setMatrixUserId("@beraterin1:matrix.oriso.org");
    consultant.setFirstName("Old");
    consultant.setLastName("Name");
    consultant.setEmail("old@address.de");
    return consultant;
  }

  private UpdateAdminConsultantDTO renameTo(String firstname, String lastname) {
    UpdateAdminConsultantDTO dto = new EasyRandom().nextObject(UpdateAdminConsultantDTO.class);
    dto.setIsGroupchatConsultant(null);
    dto.setAssignedSupervisorId(null);
    dto.setDisplayName(null);
    dto.setInternalDisplayName(null);
    dto.setFirstname(firstname);
    dto.setLastname(lastname);
    dto.setEmail("old@address.de");
    return dto;
  }

  private void keepDisplayNameUnchanged(
      Consultant consultant, UpdateAdminConsultantDTO updateConsultant) {
    updateConsultant.setFirstname(consultant.getFirstName());
    updateConsultant.setLastname(consultant.getLastName());
  }
}
