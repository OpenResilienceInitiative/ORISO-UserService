package de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hibernate.search.util.impl.CollectionHelper.asSet;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantAgencyDTO;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.ConsultantTopicAgencyCompatibilityValidator;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.ConsultantStatus;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityRoleLookup;
import de.caritas.cob.userservice.api.port.out.IdentityRoleUpdater;
import de.caritas.cob.userservice.api.service.ConsultantAgencyService;
import de.caritas.cob.userservice.api.service.LogService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.RolesDTO;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class ConsultantAgencyRelationCreatorServiceTest {

  private final EasyRandom easyRandom = new EasyRandom();

  @InjectMocks
  private ConsultantAgencyRelationCreatorService consultantAgencyRelationCreatorService;

  @Mock private ConsultantAgencyService consultantAgencyService;

  @Mock private ConsultantRepository consultantRepository;

  @Mock private AgencyService agencyService;

  @Mock private IdentityClient identityClient;
  @Mock private IdentityRoleUpdater identityRoleUpdater;

  @Mock private IdentityRoleLookup identityRoleLookup;

  @Mock private ConsultantAgencyRelationFinalizer consultantAgencyRelationFinalizer;

  @Mock private ConsultingTypeManager consultingTypeManager;

  @Mock
  private ConsultantTopicAgencyCompatibilityValidator consultantTopicAgencyCompatibilityValidator;

  @Test
  public void
      createNewConsultantAgency_Should_notThrowNullPointerException_When_agencyTypeIsU25AndConsultantHasNoAgencyAssigned() {
    AgencyDTO agencyDTO = new AgencyDTO().consultingType(1).id(2L);

    when(this.consultantRepository.findByIdAndDeleteDateIsNull(anyString()))
        .thenReturn(Optional.of(new Consultant()));
    when(agencyService.getAgency(eq(2L))).thenReturn(agencyDTO);

    CreateConsultantAgencyDTO createConsultantAgencyDTO =
        new CreateConsultantAgencyDTO().roleSetKey("valid role set").agencyId(2L);

    final var response =
        easyRandom.nextObject(
            de.caritas.cob.userservice.consultingtypeservice.generated.web.model
                .ExtendedConsultingTypeResponseDTO.class);
    when(consultingTypeManager.getConsultingTypeSettings(1)).thenReturn(response);

    assertDoesNotThrow(
        () ->
            this.consultantAgencyRelationCreatorService.createNewConsultantAgency(
                "consultant Id", createConsultantAgencyDTO));
  }

  @Test
  public void createNewConsultantAgency_Should_notSaveRelation_When_topicAgencyValidationFails() {
    var consultant = new Consultant();
    consultant.setId("consultant Id");
    consultant.setTenantId(1L);
    AgencyDTO agencyDTO = new AgencyDTO().consultingType(1).id(2L);

    when(this.consultantRepository.findByIdAndDeleteDateIsNull(anyString()))
        .thenReturn(Optional.of(consultant));
    when(agencyService.getAgency(eq(2L))).thenReturn(agencyDTO);
    doThrow(new BadRequestException("topic not covered"))
        .when(consultantTopicAgencyCompatibilityValidator)
        .validateCurrentTopicsAgainstAssignedAndAdditionalAgencies(anyString(), any(), any());

    CreateConsultantAgencyDTO createConsultantAgencyDTO =
        new CreateConsultantAgencyDTO().roleSetKey("valid role set").agencyId(2L);

    assertThrows(
        BadRequestException.class,
        () ->
            this.consultantAgencyRelationCreatorService.createNewConsultantAgency(
                "consultant Id", createConsultantAgencyDTO));

    verify(consultantAgencyService, never()).saveConsultantAgency(any(ConsultantAgency.class));
  }

  @Test
  public void completeConsultantAgencyAssigment_Should_finalizeRelationSynchronously() {
    var consultant = new Consultant();
    consultant.setStatus(ConsultantStatus.CREATED);
    var agencyDTO = new AgencyDTO().id(2L).teamAgency(false);

    when(consultantRepository.findByIdAndDeleteDateIsNull(anyString()))
        .thenReturn(Optional.of(consultant));
    when(agencyService.getAgency(eq(2L))).thenReturn(agencyDTO);

    var input =
        new CreateConsultantAgencyDTOInputAdapter(
            "consultant Id", new CreateConsultantAgencyDTO().agencyId(2L));

    consultantAgencyRelationCreatorService.completeConsultantAgencyAssigment(
        input, LogService::logInfo);

    verify(consultantAgencyRelationFinalizer)
        .finalizeConsultantAgencyRelation(consultant, agencyDTO);
    assertThat(consultant.getStatus()).isEqualTo(ConsultantStatus.IN_PROGRESS);
    verify(consultantRepository).save(consultant);
  }

  @Test
  void createNewConsultantAgency_Should_finalizeThePersistedRelationWithoutRequeryingIt() {
    var consultant = new Consultant();
    consultant.setId("consultant Id");
    consultant.setTenantId(83L);
    consultant.setStatus(ConsultantStatus.CREATED);
    var agency = new AgencyDTO().id(280L).consultingType(0).teamAgency(false);
    when(consultantRepository.findByIdAndDeleteDateIsNull("consultant Id"))
        .thenReturn(Optional.of(consultant));
    when(agencyService.getAgency(280L)).thenReturn(agency);
    when(consultingTypeManager.getConsultingTypeSettings(0))
        .thenReturn(new ExtendedConsultingTypeResponseDTO());
    when(consultantAgencyService.saveConsultantAgency(any(ConsultantAgency.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    consultantAgencyRelationCreatorService.createNewConsultantAgency(
        "consultant Id", new CreateConsultantAgencyDTO().agencyId(280L));

    var persistedRelation = ArgumentCaptor.forClass(ConsultantAgency.class);
    verify(consultantAgencyService).saveConsultantAgency(persistedRelation.capture());
    verify(consultantAgencyRelationFinalizer)
        .finalizeConsultantAgencyRelation(consultant, persistedRelation.getValue());
  }

  @Test
  public void createNewConsultantAgency_Should_throwBadRequest_When_consultantDoesNotExist() {
    when(consultantRepository.findByIdAndDeleteDateIsNull("missing")).thenReturn(Optional.empty());

    assertThrows(
        BadRequestException.class,
        () ->
            consultantAgencyRelationCreatorService.createNewConsultantAgency(
                "missing", new CreateConsultantAgencyDTO().agencyId(2L)));

    verify(consultantAgencyService, never()).saveConsultantAgency(any());
  }

  @Test
  public void createNewConsultantAgency_Should_throwBadRequest_When_agencyDoesNotExist() {
    when(consultantRepository.findByIdAndDeleteDateIsNull(anyString()))
        .thenReturn(Optional.of(new Consultant()));
    when(agencyService.getAgency(99L)).thenReturn(null);

    assertThrows(
        BadRequestException.class,
        () ->
            consultantAgencyRelationCreatorService.createNewConsultantAgency(
                "consultant Id", new CreateConsultantAgencyDTO().agencyId(99L)));
  }

  @Test
  public void
      createNewConsultantAgency_Should_throwBadRequest_When_assignedAgenciesHaveDifferentConsultingType() {
    var consultant = new Consultant();
    consultant.setId("consultant Id");
    consultant.setTenantId(1L);
    consultant.setConsultantAgencies(Set.of(ConsultantAgency.builder().agencyId(3L).build()));

    AgencyDTO existingAgency = new AgencyDTO().consultingType(1).id(3L);
    AgencyDTO newAgency = new AgencyDTO().consultingType(2).id(2L);

    when(consultantRepository.findByIdAndDeleteDateIsNull("consultant Id"))
        .thenReturn(Optional.of(consultant));
    when(agencyService.getAgency(2L)).thenReturn(newAgency);
    when(agencyService.getAgency(3L)).thenReturn(existingAgency);
    when(consultingTypeManager.isConsultantBoundedToAgency(2)).thenReturn(true);

    assertThrows(
        BadRequestException.class,
        () ->
            consultantAgencyRelationCreatorService.createNewConsultantAgency(
                "consultant Id", new CreateConsultantAgencyDTO().agencyId(2L)));
  }

  @Test
  public void createConsultantAgencyRelations_Should_throwBadRequest_When_consultantHasNoRole() {
    when(identityRoleLookup.findAllByUserId("consultant Id")).thenReturn(List.of("other-role"));

    assertThrows(
        BadRequestException.class,
        () ->
            consultantAgencyRelationCreatorService.createConsultantAgencyRelations(
                "consultant Id",
                Set.of(1L),
                asSet("consultant", "tenant-admin", "user-admin"),
                LogService::logInfo));

    verify(identityRoleLookup).findAllByUserId("consultant Id");
    verify(identityClient, never()).userHasRole(anyString(), anyString());
    verify(consultantAgencyService, never()).saveConsultantAgency(any());
  }

  @Test
  public void
      createConsultantAgencyRelations_Should_throwBadRequestWithoutIdentityRead_When_rolesAreEmpty() {
    assertThrows(
        BadRequestException.class,
        () ->
            consultantAgencyRelationCreatorService.createConsultantAgencyRelations(
                "consultant Id", Set.of(1L), Set.of(), LogService::logInfo));

    verify(identityRoleLookup, never()).findAllByUserId(anyString());
    verify(consultantAgencyService, never()).saveConsultantAgency(any());
  }

  @Test
  public void createConsultantAgencyRelations_Should_createRelations_When_rolesArePresent() {
    AgencyDTO agencyDTO = new AgencyDTO().consultingType(1).id(1L);
    var consultant = new Consultant();
    consultant.setId("consultant Id");
    consultant.setTenantId(1L);

    when(identityRoleLookup.findAllByUserId("consultant Id"))
        .thenReturn(List.of("other-role", "tenant-admin"));
    when(consultantRepository.findByIdAndDeleteDateIsNull("consultant Id"))
        .thenReturn(Optional.of(consultant));
    when(agencyService.getAgency(1L)).thenReturn(agencyDTO);
    when(consultingTypeManager.getConsultingTypeSettings(1))
        .thenReturn(easyRandom.nextObject(ExtendedConsultingTypeResponseDTO.class));

    consultantAgencyRelationCreatorService.createConsultantAgencyRelations(
        "consultant Id",
        Set.of(1L),
        asSet("consultant", "tenant-admin", "user-admin"),
        LogService::logInfo);

    verify(identityRoleLookup).findAllByUserId("consultant Id");
    verify(identityClient, never()).userHasRole(anyString(), anyString());
    verify(consultantAgencyService).saveConsultantAgency(any(ConsultantAgency.class));
  }

  @Test
  public void
      completeConsultantAgencyAssigment_Should_markTeamConsultant_When_teamAgencyAssigned() {
    var consultant = new Consultant();
    consultant.setId("consultant Id");
    consultant.setStatus(ConsultantStatus.CREATED);
    consultant.setTeamConsultant(false);
    var agencyDTO = new AgencyDTO().id(2L).teamAgency(true);

    when(consultantRepository.findByIdAndDeleteDateIsNull("consultant Id"))
        .thenReturn(Optional.of(consultant));
    when(agencyService.getAgency(2L)).thenReturn(agencyDTO);

    var input =
        new CreateConsultantAgencyDTOInputAdapter(
            "consultant Id", new CreateConsultantAgencyDTO().agencyId(2L));

    consultantAgencyRelationCreatorService.completeConsultantAgencyAssigment(
        input, LogService::logInfo);

    assertThat(consultant.isTeamConsultant()).isTrue();
    verify(consultantRepository, org.mockito.Mockito.atLeastOnce()).save(consultant);
  }

  @Test
  public void createNewConsultantAgency_Should_assignKeycloakRoles_When_roleSetConfigured() {
    var consultant = new Consultant();
    consultant.setId("consultant Id");
    consultant.setTenantId(1L);
    AgencyDTO agencyDTO = new AgencyDTO().consultingType(0).id(15L);

    when(consultantRepository.findByIdAndDeleteDateIsNull("consultant Id"))
        .thenReturn(Optional.of(consultant));
    when(agencyService.getAgency(15L)).thenReturn(agencyDTO);
    when(consultingTypeManager.getConsultingTypeSettings(0))
        .thenReturn(
            givenConsultingTypeWithRoles(
                "main", List.of("consultant-role", "u25-consultant", "consultant-role")));

    CreateConsultantAgencyDTO createConsultantAgencyDTO =
        new CreateConsultantAgencyDTO().roleSetKey("main").agencyId(15L);

    consultantAgencyRelationCreatorService.createNewConsultantAgency(
        "consultant Id", createConsultantAgencyDTO);

    verify(identityRoleUpdater)
        .ensureRoles("consultant Id", Set.of("consultant-role", "u25-consultant"));
    verify(consultantAgencyService).saveConsultantAgency(any(ConsultantAgency.class));
  }

  @Test
  public void createNewConsultantAgency_Should_notCallIdentityUpdater_When_roleSetIsUnmapped() {
    var consultant = new Consultant();
    consultant.setId("consultant Id");
    consultant.setTenantId(1L);
    AgencyDTO agencyDTO = new AgencyDTO().consultingType(0).id(15L);

    when(consultantRepository.findByIdAndDeleteDateIsNull("consultant Id"))
        .thenReturn(Optional.of(consultant));
    when(agencyService.getAgency(15L)).thenReturn(agencyDTO);
    when(consultingTypeManager.getConsultingTypeSettings(0))
        .thenReturn(givenConsultingTypeWithRoles("main", List.of("consultant-role")));

    CreateConsultantAgencyDTO createConsultantAgencyDTO =
        new CreateConsultantAgencyDTO().roleSetKey("unmapped").agencyId(15L);

    consultantAgencyRelationCreatorService.createNewConsultantAgency(
        "consultant Id", createConsultantAgencyDTO);

    verify(identityRoleUpdater, never()).ensureRoles(anyString(), any());
    verify(consultantAgencyService).saveConsultantAgency(any(ConsultantAgency.class));
  }

  private ExtendedConsultingTypeResponseDTO givenConsultingTypeWithRoles(
      String roleSetName, List<String> roles) {
    var roleSets = new LinkedHashMap<String, List<String>>();
    roleSets.put(roleSetName, roles);

    var roleConsultant =
        new de.caritas.cob.userservice.api.manager.consultingtype.roles.Consultant();
    roleConsultant.setRoleSets(roleSets);

    var rolesDTO = new RolesDTO();
    rolesDTO.setConsultant(roleConsultant);

    var consultingTypeResponse = easyRandom.nextObject(ExtendedConsultingTypeResponseDTO.class);
    consultingTypeResponse.setRoles(rolesDTO);
    return consultingTypeResponse;
  }
}
